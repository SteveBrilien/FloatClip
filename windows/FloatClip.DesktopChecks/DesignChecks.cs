using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using FloatClip.Desktop;
using FloatClip.Desktop.Design;
using FloatClip.Desktop.Services;
using FloatClip.Desktop.ViewModels;

static class DesignChecks
{
    static double Luminance(Color color)
    {
        double Channel(byte v){double s=v/255.0;return s<=.04045?s/12.92:Math.Pow((s+.055)/1.055,2.4);}
        return .2126*Channel(color.R)+.7152*Channel(color.G)+.0722*Channel(color.B);
    }
    static double Contrast(string foreground,string background)
    {
        double a=Luminance(DesignTokens.Brush(foreground).Color),b=Luminance(DesignTokens.Brush(background).Color);
        return (Math.Max(a,b)+.05)/(Math.Min(a,b)+.05);
    }
    sealed class FailedLibrary:IClipboardLibraryService{public int ClearUnprotected()=>throw new IOException("synthetic storage failure");}
    public static void Run(Application app,LocalClipboardWindow window,ClipboardHost host,string output,Action<bool,string> check,Action<int> pump)
    {
        foreach(string theme in new[]{"light","dark"})
        {
            DesignTheme.Apply(theme);
            foreach(var pair in new[]{("TextPrimary","Surface"),("TextSecondary","Surface"),("TextSecondary","Background"),("DisabledText","DisabledSurface"),("OnAccent","Accent"),("OnAccent","AccentHover"),("OnAccent","AccentPressed")})
                check(Contrast(pair.Item1,pair.Item2)>=4.5,$"{theme}: readable {pair.Item1} on {pair.Item2}");
            check(ReferenceEquals(app.Resources["FloatClip.Muted"],app.Resources["FloatClip.TextSecondary"]),theme+": legacy brush alias shares one semantic resource");
        }
        var dark=DesignTokens.Brush("Background").Color;DesignTheme.Apply("tokyo-night");
        check(dark==DesignTokens.Brush("Background").Color,"Dark and Tokyo Night use one palette");
        foreach(var motion in new[]{("Press",100), ("State",160), ("Panel",240)})
            check(((Duration)app.Resources["FloatClip.Motion."+motion.Item1]).TimeSpan.TotalMilliseconds==motion.Item2,"central motion token: "+motion.Item1);
        var preferences=DesignPreferences.Load(host.PreferencesRoot);bool prior=preferences.ReduceMotion;
        preferences.ReduceMotion=true;preferences.Save(host.PreferencesRoot);
        check(DesignTokens.Motion("Press")==TimeSpan.Zero&&DesignTokens.Motion("Panel")==TimeSpan.Zero,"reduced motion disables press and panel animation");
        preferences.ReduceMotion=prior;preferences.Save(host.PreferencesRoot);
        var material=MaterialPolicy.Evaluate(true);check(!string.IsNullOrWhiteSpace(material.Fallback),"floating material policy always states the solid fallback");

        var failed=new ClipboardPanelViewModel(new FailedLibrary());Exception? observed=null;bool changed=false;
        failed.OperationFailed+=error=>observed=error;failed.HistoryChanged+=()=>changed=true;failed.ClearHistoryCommand.Execute(null);
        check(observed is IOException&&!changed&&failed.StatusMessage.Contains("未完成"),"storage error is observable and does not report a successful clear");

        T Field<T>(string name)=>(T)typeof(LocalClipboardWindow).GetField(name,BindingFlags.NonPublic|BindingFlags.Instance)!.GetValue(window)!;
        void Call(string name,params object[] args)=>typeof(LocalClipboardWindow).GetMethod(name,BindingFlags.NonPublic|BindingFlags.Instance)!.Invoke(window,args);
        window.Activate();window.FocusSearch();pump(0);
        var search=Field<TextBox>("search");var list=Field<StackPanel>("list");
        var card=(Border)((Grid)((Grid)list.Children[0]).Children[2]).Children[0];
        card.RaiseEvent(new MouseButtonEventArgs(Mouse.PrimaryDevice,Environment.TickCount,MouseButton.Left){RoutedEvent=Mouse.PreviewMouseDownEvent});pump(0);
        check(!search.IsKeyboardFocusWithin,"clicking outside the search field releases keyboard focus");
        card.RaiseEvent(new MouseEventArgs(Mouse.PrimaryDevice,Environment.TickCount){RoutedEvent=Mouse.MouseEnterEvent});
        check(ReferenceEquals(card.Background,DesignTokens.Brush("Hover")),"card enter event applies shared hover surface");
        card.RaiseEvent(new MouseEventArgs(Mouse.PrimaryDevice,Environment.TickCount){RoutedEvent=Mouse.MouseLeaveEvent});
        var menu=card.ContextMenu!;menu.PlacementTarget=card;menu.IsOpen=true;pump(0);
        check(menu.IsOpen&&menu.Items.OfType<MenuItem>().All(item=>item.Style!=null),"real context-menu popup resolves every shared menu style");menu.IsOpen=false;

        double width=window.Width;string mode=Field<string>("mode");var modeField=typeof(LocalClipboardWindow).GetField("mode",BindingFlags.NonPublic|BindingFlags.Instance)!;
        string evidence=Path.Combine(output,"design");Directory.CreateDirectory(evidence);
        foreach(string theme in new[]{"light","dark"})
        {
            modeField.SetValue(window,theme);Call("ApplyPalette");Call("BuildHeader");Call("Refresh",true);
            foreach(double size in new[]{420.0,760.0,Math.Max(900,SystemParameters.WorkArea.Width-32)})
            {
                window.Width=size;window.UpdateLayout();pump(0);var surface=(FrameworkElement)window.Content;
                check(surface.ActualWidth<=window.ActualWidth+1&&search.ActualWidth>100,$"{theme}: layout remains within {size:F0} DIP window");
                foreach(int dpi in new[]{96,120,144,192})
                {
                    double scale=dpi/96.0;var bitmap=new RenderTargetBitmap((int)Math.Ceiling(surface.ActualWidth*scale),(int)Math.Ceiling(surface.ActualHeight*scale),dpi,dpi,PixelFormats.Pbgra32);bitmap.Render(surface);
                    var encoder=new PngBitmapEncoder();encoder.Frames.Add(BitmapFrame.Create(bitmap));using var file=File.Create(Path.Combine(evidence,$"clipboard-{theme}-{size:F0}dip-{dpi}dpi.png"));encoder.Save(file);
                }
            }
        }
        window.Width=width;modeField.SetValue(window,mode);Call("ApplyPalette");Call("BuildHeader");Call("Refresh",true);pump(0);
        var dpiScale=VisualTreeHelper.GetDpi(window);
        File.WriteAllText(Path.Combine(evidence,"scope.json"),JsonSerializer.Serialize(new{actualWindowDpi=new{dpiScale.DpiScaleX,dpiScale.DpiScaleY},offscreenDpi=new[]{96,120,144,192},physicalFourDpiInteractionVerified=false,hoverEventIsSynthetic=true,materialEnabled=false,utc=DateTime.UtcNow},new JsonSerializerOptions{WriteIndented=true}));
    }
}
