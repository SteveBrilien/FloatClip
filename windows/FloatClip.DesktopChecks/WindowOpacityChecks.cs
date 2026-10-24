using System;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using FloatClip.Desktop;
using FloatClip.Desktop.Design;
using FloatClip.Desktop.Services;

static class WindowOpacityChecks
{
    const int ExtendedStyle=-20,Layered=0x80000;
    [DllImport("user32.dll",EntryPoint="GetWindowLongW")]static extern int Style(IntPtr window,int index);
    [DllImport("user32.dll",EntryPoint="SetWindowLongW")]static extern int Style(IntPtr window,int index,int value);
    [DllImport("user32.dll",SetLastError=true)][return:MarshalAs(UnmanagedType.Bool)]
    static extern bool SetLayeredWindowAttributes(IntPtr window,uint key,byte alpha,uint flags);
    [DllImport("user32.dll",SetLastError=true)][return:MarshalAs(UnmanagedType.Bool)]
    static extern bool GetLayeredWindowAttributes(IntPtr window,out uint key,out byte alpha,out uint flags);
    [DllImport("user32.dll")][return:MarshalAs(UnmanagedType.Bool)]
    static extern bool SetWindowPos(IntPtr window,IntPtr after,int x,int y,int width,int height,uint flags);
    static byte Alpha(Window window)
    {
        if(!GetLayeredWindowAttributes(new WindowInteropHelper(window).Handle,out _,out var alpha,out var flags)||(flags&2)==0)
        {
            int error=Marshal.GetLastPInvokeError(),before=Style(new WindowInteropHelper(window).Handle,ExtendedStyle);
            string attempt=WindowOpacityService.Apply(window,.72);
            bool after=GetLayeredWindowAttributes(new WindowInteropHelper(window).Handle,out _,out var recovered,out _);
            throw new Exception($"Ordinary HWND alpha must be readable by PowerToys; error={error}; style={before:X}; loaded={window.IsLoaded}; visible={window.IsVisible}; explicit={attempt}; recovered={after}/{recovered}");
        }
        return alpha;
    }
    // Mirror PowerToys' public ApplyWindowAlpha code, including its guard and style cycle.
    static void ExternalAlpha(Window window,int percent)
    {
        var handle=new WindowInteropHelper(window).Handle;int style=Style(handle,ExtendedStyle);
        if((style&Layered)!=0&&!GetLayeredWindowAttributes(handle,out _,out _,out _))
            throw new Exception("PowerToys would skip this per-pixel surface");
        if((style&Layered)!=0)
        {
            Style(handle,ExtendedStyle,style&~Layered);
            SetWindowPos(handle,IntPtr.Zero,0,0,0,0,0x37);
            style=Style(handle,ExtendedStyle);
        }
        Style(handle,ExtendedStyle,style|Layered);
        if(!SetLayeredWindowAttributes(handle,0,(byte)(255*percent/100),2))
            throw new Exception("External alpha API failed: "+Marshal.GetLastPInvokeError());
    }
    public static void Run(Application app,string root,Action<bool,string> check,Action<int> pump,Action<LocalClipboardWindow> refresh)
    {
        var preferences=new DesignPreferences{ClipboardBackground="window",WindowOpacity=.72};
        string data=Path.Combine(root,"native-opacity");Directory.CreateDirectory(data);preferences.Save(data);
        check(DesignPreferences.Load(data).ClipboardBackground=="window"&&DesignPreferences.Load(data).WindowOpacity==.72,"whole-window mode and opacity persist independently of background alpha");
        using var host=new ClipboardHost(data,false);host.SetPinned(true);host.ShowPopup();pump(180);
        var window=app.Windows.OfType<LocalClipboardWindow>().Single(w=>ReferenceEquals(typeof(LocalClipboardWindow).GetField("host",System.Reflection.BindingFlags.Instance|System.Reflection.BindingFlags.NonPublic)!.GetValue(w),host));
        check(!window.AllowsTransparency&&window.Opacity==1&&Alpha(window)==184,"whole-window mode uses readable native alpha, with no WPF per-pixel surface");
        ExternalAlpha(window,60);pump(80);
        check(Alpha(window)==153,"PowerToys style-cycle API changes FloatClip to 60 percent");
        foreach(string theme in new[]{"light","dark"})
        {
            DesignTheme.Apply(theme);ClipboardBackgroundService.Apply(window,preferences);window.OpenSettings();refresh(window);pump(80);
            check(Alpha(window)==153&&window.Opacity==1,theme+": theme, settings and content refresh preserve the external alpha");
        }
        window.FocusSearch();pump(0);
        check(window.Topmost&&window.IsKeyboardFocusWithin,"externally faded window retains app pin and keyboard input");
        check(WindowOpacityService.Apply(window,.55)==""&&Alpha(window)==140,"explicit in-app opacity adjustment uses the same native alpha API");
        check(WindowOpacityService.Apply(window,1)==""&&Alpha(window)==255,"explicit 100 percent restores the complete window");
        check(WindowOpacityService.Apply(window,double.NaN).Length>0&&Alpha(window)==255,"invalid opacity is rejected without changing the window");
        ExternalAlpha(window,40);window.Hide();window.Show();pump(50);
        check(Alpha(window)==102,"hide and show preserve a PowerToys adjustment");
        window.Width+=32;window.Height+=20;pump(80);
        check(Alpha(window)==102,"resize preserves the external alpha while WPF updates render settings");
        window.WindowState=WindowState.Minimized;pump(50);window.WindowState=WindowState.Normal;pump(100);
        check(Alpha(window)==102,"minimize and restore preserve the external alpha");
        host.Vault.AddText("window replacement fixture");var vault=host.Vault;
        window.OpenSettings();window.Width=420;
        typeof(LocalClipboardWindow).GetField("expandedNavigation",System.Reflection.BindingFlags.Instance|System.Reflection.BindingFlags.NonPublic)!.SetValue(window,true);
        typeof(LocalClipboardWindow).GetMethod("UpdateNavigation",System.Reflection.BindingFlags.Instance|System.Reflection.BindingFlags.NonPublic)!.Invoke(window,null);pump(80);
        System.Collections.Generic.IEnumerable<DependencyObject> Tree(DependencyObject value){yield return value;for(int i=0;i<System.Windows.Media.VisualTreeHelper.GetChildrenCount(value);i++)foreach(var child in Tree(System.Windows.Media.VisualTreeHelper.GetChild(value,i)))yield return child;}
        ComboBox Choice(LocalClipboardWindow value)=>Tree(value).OfType<ComboBox>().Single(c=>System.Windows.Automation.AutomationProperties.GetAutomationId(c)=="BackgroundMode");
        LocalClipboardWindow Current()=>app.Windows.OfType<LocalClipboardWindow>().Single(w=>ReferenceEquals(typeof(LocalClipboardWindow).GetField("host",System.Reflection.BindingFlags.Instance|System.Reflection.BindingFlags.NonPublic)!.GetValue(w),host));
        var rowStyle=app.FindResource("FloatClip.Setting.Row");
        check(Tree(window).OfType<Border>().Where(b=>b.Tag as string=="setting-row").All(b=>b.ActualHeight<=DesignTokens.Number("Setting.CompactMaxHeight")),"expanded navigation at 420 DIP keeps settings cards compact: "+string.Join("; ",Tree(window).OfType<Border>().Where(b=>b.Tag as string=="setting-row").Select(b=>string.Join("/",Tree(b).OfType<TextBlock>().Select(t=>t.Text))+" height="+b.ActualHeight+" width="+b.ActualWidth)));
        check(Choice(window).Items.Cast<string>().All(label=>!label.Contains("PowerToys")&&!label.Contains("重启")),"background picker uses short product labels without external-tool or restart wording");
        var old=window;var auxiliary=new Window{Owner=old,Content=new TextBlock{Text="auxiliary fixture"},Width=300,Height=200,ShowInTaskbar=false};auxiliary.Show();
        Choice(old).SelectedIndex=2;pump(260);window=Current();
        check(!ReferenceEquals(old,window)&&!old.IsVisible&&window.IsVisible&&window.AllowsTransparency,"switching to background transparency replaces only the visible window immediately");
        check(ReferenceEquals(vault,host.Vault)&&!host.IsExiting&&host.Vault.Load()["entries"]!.AsArray().Count==1,"window replacement keeps the same resident host, vault and history");
        check(auxiliary.Owner==window&&auxiliary.IsVisible,"background switch keeps an open auxiliary window alive");auxiliary.Close();
        check((Type)typeof(LocalClipboardWindow).GetField("currentPage",System.Reflection.BindingFlags.Instance|System.Reflection.BindingFlags.NonPublic)!.GetValue(window)! == typeof(SettingsPage)&&window.Width==420,"background replacement preserves settings page and window width");
        old=window;Choice(old).SelectedIndex=3;pump(260);window=Current();
        check(!ReferenceEquals(old,window)&&!window.AllowsTransparency&&window.Topmost&&Alpha(window)==184,"switching back to whole-window transparency restores native alpha without restarting the host");
        check(Tree(window).OfType<Border>().Where(b=>b.Tag as string=="setting-row").All(b=>b.ActualHeight<=DesignTokens.Number("Setting.CompactMaxHeight")),"compact card bounds remain stable after live mode switches");
        // The old background-only mode is still available and never receives an unsafe SLA call.
        string legacy=Path.Combine(root,"per-pixel-opacity");Directory.CreateDirectory(legacy);
        new DesignPreferences{ClipboardBackground="transparent"}.Save(legacy);
        using var legacyHost=new ClipboardHost(legacy,false);legacyHost.ShowPopup();pump(120);
        var legacyWindow=app.Windows.OfType<LocalClipboardWindow>().Single(w=>ReferenceEquals(typeof(LocalClipboardWindow).GetField("host",System.Reflection.BindingFlags.Instance|System.Reflection.BindingFlags.NonPublic)!.GetValue(w),legacyHost));
        var handle=new WindowInteropHelper(legacyWindow).Handle;int before=Style(handle,ExtendedStyle);
        check(legacyWindow.AllowsTransparency&&WindowOpacityService.Apply(legacyWindow,.5).Contains("切换")&&Style(handle,ExtendedStyle)==before,"legacy per-pixel transparency never receives incompatible layered API writes");
    }
}
