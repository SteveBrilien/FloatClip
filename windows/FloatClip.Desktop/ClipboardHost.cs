using System;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Threading;
using System.Windows.Media.Imaging;
using FloatClip.Share;

namespace FloatClip.Desktop;

/** Resident platform host. No visible window is required to capture, receive or retry. */
public sealed class ClipboardHost:IDisposable
{
    public static ClipboardHost? Current{get;private set;}
    readonly Dispatcher dispatcher=Dispatcher.CurrentDispatcher;
    readonly string root;
    readonly bool systemIntegration;
    public string PreferencesRoot=>root;
    readonly HwndSource messages;
    readonly DesktopShellPreferences preferences;
    readonly string preferencesPath;
    readonly System.Windows.Forms.NotifyIcon? tray;
    LocalClipboardWindow? popup;
    uint initialSequence,lastSequence;
    int hotkeyId=703;
    bool registered,disposed;
    public bool IsExiting=>disposed;
    public ClipboardVault Vault{get;}
    public DesktopUpdates Updates{get;}
    public NativeClipboardSync? Sync{get;}
    public string? SyncError{get;}
    public bool Capture{get;set;}=true;
    public event Action? Changed;
    public bool Pinned=>preferences.Pinned;
    public string HotkeyError{get;private set;}="";
    public string HotkeyLabel=>FormatHotkey(preferences.HotkeyModifiers,preferences.HotkeyKey);
    public string Status=>Sync?.Status??SyncError??"共享尚未准备好";
    const int ClipboardMessage=0x031D,HotkeyMessage=0x0312;
    [DllImport("user32.dll")]static extern bool AddClipboardFormatListener(IntPtr hwnd);
    [DllImport("user32.dll")]static extern bool RemoveClipboardFormatListener(IntPtr hwnd);
    [DllImport("user32.dll")]static extern bool RegisterHotKey(IntPtr hwnd,int id,uint modifiers,uint key);
    [DllImport("user32.dll")]static extern bool UnregisterHotKey(IntPtr hwnd,int id);
    [DllImport("user32.dll")]static extern uint GetClipboardSequenceNumber();
    [DllImport("user32.dll")]static extern bool GetWindowRect(IntPtr hwnd,out RectNative rect);
    [DllImport("user32.dll")]static extern bool SetWindowPos(IntPtr hwnd,IntPtr after,int x,int y,int width,int height,uint flags);
    [StructLayout(LayoutKind.Sequential)]struct RectNative{public int Left,Top,Right,Bottom;}

    // An isolated data root and disabled integration let desktop checks exercise the real
    // view without capturing the user's clipboard, connecting peers or registering a hotkey.
    public ClipboardHost(string? dataRoot=null,bool systemIntegration=true,DesktopUpdates? updates=null)
    {
        if(systemIntegration&&dataRoot!=null||!systemIntegration&&string.IsNullOrWhiteSpace(dataRoot))
            throw new ArgumentException("隔离验收必须使用独立数据目录并关闭系统集成");
        this.systemIntegration=systemIntegration;
        root=dataRoot??Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"FloatClip","Host");
        Current=this;
        Directory.CreateDirectory(root);
        preferencesPath=Path.Combine(root,"desktop-shell.json");
        preferences=DesktopShellPreferences.Load(preferencesPath);
        if(systemIntegration&&updates!=null)throw new ArgumentException("正式宿主使用已配置的更新源");
        Updates=updates??new DesktopUpdates(Path.Combine(root,"Updates"));
        try{Capture=JsonNode.Parse(File.ReadAllText(Path.Combine(root,"clipboard-ui.json")))?["capture"]?.GetValue<bool>()??true;}catch{}
        Vault=new ClipboardVault(dataRoot==null?null:Path.Combine(dataRoot,"Library"));
        try{Vault.PruneOrphanedImages();}catch{} // Never destroy the vault if a prior run left damaged metadata.
        try
        {
            if(systemIntegration){Sync=new NativeClipboardSync(Vault,IncomingClipboard);
                Sync.HistoryChanged+=NotifyChanged;Sync.Changed+=NotifyChanged;}
        }
        catch{SyncError="共享状态暂时无法读取，请保留数据并检查存储。";}
        messages=new HwndSource(new HwndSourceParameters("FloatClip.ClipboardHost"){ParentWindow=new IntPtr(-3),WindowStyle=0});
        if(!systemIntegration)return;
        messages.AddHook(Hook);
        initialSequence=GetClipboardSequenceNumber();
        if(!AddClipboardFormatListener(messages.Handle))throw new IOException("无法监听系统剪贴板");
        registered=RegisterHotKey(messages.Handle,hotkeyId,0x4000|preferences.HotkeyModifiers,preferences.HotkeyKey);
        if(!registered)HotkeyError="快捷键已被占用，可在设置中更改";
        using var stream=Application.GetResourceStream(new Uri("pack://application:,,,/Assets/FloatClip.ico"))!.Stream;
        tray=new System.Windows.Forms.NotifyIcon{Text="FloatClip",Icon=new System.Drawing.Icon(stream,System.Windows.Forms.SystemInformation.SmallIconSize),Visible=true};
        var menu=new System.Windows.Forms.ContextMenuStrip();
        menu.Items.Add("打开悬浮剪贴板",null,(_,_)=>ShowPopup());
        menu.Items.Add("设置",null,(_,_)=>ShowSettings());
        menu.Items.Add("暂停 / 恢复收录",null,(_,_)=>{Capture=!Capture;SaveCapture();NotifyChanged();});
        menu.Items.Add("退出 FloatClip",null,(_,_)=>Application.Current.Shutdown());
        tray.ContextMenuStrip=menu;tray.DoubleClick+=(_,_)=>ShowPopup();
    }
    public void Start(bool background)
    {
        if(systemIntegration)ReadClipboard();Sync?.Start();
        if(!background||Pinned)ShowPopup(activate:!background);
        if(systemIntegration)_=Updates.CheckAsync();
    }
    IntPtr Hook(IntPtr window,int message,IntPtr w,IntPtr l,ref bool handled)
    {
        if(message==ClipboardMessage)ReadClipboard();
        else if(message==HotkeyMessage&&w.ToInt32()==hotkeyId)
        {
            if(popup?.IsVisible==true&&popup.IsActive&&!Pinned)popup.Hide();else ShowPopup();
            handled=true;
        }
        return IntPtr.Zero;
    }
    void SaveCapture()
    {
        string path=Path.Combine(root,"clipboard-ui.json");
        JsonObject options;
        try{options=JsonNode.Parse(File.ReadAllText(path)) as JsonObject??new();}catch{options=new();}
        options["capture"]=Capture;File.WriteAllText(path,options.ToJsonString());
    }
    public void NotifyChanged()
    {
        if(!disposed)dispatcher.BeginInvoke(new Action(()=>{if(!disposed)Changed?.Invoke();}));
    }
    void ReadClipboard(int retry=0)
    {
        if(disposed||(!Capture&&Sync?.Snapshot().Enabled!=true))return;
        try
        {
            uint sequence=GetClipboardSequenceNumber();
            if(sequence!=0&&sequence==lastSequence)return;
            if(ClipboardWrites.IsOwnWrite()){lastSequence=sequence;return;}
            if(Clipboard.ContainsData("ExcludeClipboardContentFromMonitorProcessing"))
            {Sync?.Copied("",true);lastSequence=sequence;return;}
            if(Clipboard.ContainsImage()||Clipboard.ContainsData(ClipboardImages.PngFormat)||Clipboard.ContainsData(DataFormats.GetDataFormat(17).Name)||Clipboard.ContainsData(DataFormats.Dib))
            {
                Sync?.Copied("",true);
                if(Capture)
                {
                    var image=ClipboardImages.Read(Clipboard.GetDataObject());
                    if(image!=null)Vault.AddImage(image.Value.Png,image.Value.Width,image.Value.Height);
                }
                lastSequence=sequence;
                NotifyChanged();return;
            }
            if(Clipboard.ContainsText())
            {
                string value=Clipboard.GetText();
                if(string.IsNullOrWhiteSpace(value)){Sync?.Copied("",true);lastSequence=sequence;return;}
                if(Capture)Vault.AddText(value);
                lastSequence=sequence;
                if(sequence!=initialSequence)Sync?.Copied(value);
                NotifyChanged();return;
            }
            Sync?.Copied("",true);lastSequence=sequence;
        }
        catch(COMException)
        {
            if(retry<4){var timer=new DispatcherTimer{Interval=TimeSpan.FromMilliseconds(75)};
                timer.Tick+=(_,_)=>{timer.Stop();ReadClipboard(retry+1);};timer.Start();}
        }
        catch{HotkeyError="剪贴板暂未保存，请检查本机存储";NotifyChanged();}
    }
    void IncomingClipboard(SyncEntry entry,long grant)
    {
        dispatcher.BeginInvoke(new Action(()=>{
            if(disposed||Sync?.MayApply(entry,grant)!=true)return;
            uint current=GetClipboardSequenceNumber();
            if(current!=0&&current!=lastSequence){ReadClipboard();return;}
            if(Sync.MayApply(entry,grant))try{ClipboardWrites.Write(entry.Text);lastSequence=GetClipboardSequenceNumber();}catch{}
        }));
    }
    public void CopyByUser(string text)
    {
        if(systemIntegration){ClipboardWrites.Write(text);lastSequence=GetClipboardSequenceNumber();}
        Sync?.Copied(text);NotifyChanged();
    }
    public void CopyImageByUser(byte[] pngBytes)
    {
        if(systemIntegration){ClipboardWrites.WriteImage(pngBytes);lastSequence=GetClipboardSequenceNumber();}
        Sync?.Copied("",true);NotifyChanged();
    }
    public void ShowPopup(bool activate=true)
    {
        if(disposed)return;
        popup??=new LocalClipboardWindow(this,true);
        popup.Topmost=Pinned;popup.ShowActivated=activate;
        popup.Show();PlacePopup();
        if(activate){popup.Activate();popup.FocusSearch();}
    }
    // Legacy entry points activate the same clipboard surface.
    public void ShowLibrary()=>ShowPopup();
    public void ShowDevices(){ShowPopup();popup?.OpenDevices();}
    public void ShowSettings()
    {
        ShowPopup();popup?.OpenSettings();
    }
    void PlacePopup()
    {
        if(popup==null)return;
        var handle=new WindowInteropHelper(popup).Handle;
        if(!GetWindowRect(handle,out var rect))return;
        var cursor=System.Windows.Forms.Cursor.Position;
        var anchor=Pinned&&preferences.Left is int x&&preferences.Top is int y?new System.Drawing.Point(x,y):cursor;
        var area=System.Windows.Forms.Screen.FromPoint(anchor).WorkingArea;
        int width=rect.Right-rect.Left,height=rect.Bottom-rect.Top;
        int left=Pinned&&preferences.Left!=null?preferences.Left.Value:cursor.X+12;
        int top=Pinned&&preferences.Top!=null?preferences.Top.Value:cursor.Y+12;
        left=Math.Clamp(left,area.Left,Math.Max(area.Left,area.Right-width));
        top=Math.Clamp(top,area.Top,Math.Max(area.Top,area.Bottom-height));
        SetWindowPos(handle,IntPtr.Zero,left,top,0,0,0x0015);
    }
    public void RememberPlacement()
    {
        if(popup==null||!Pinned)return;
        if(GetWindowRect(new WindowInteropHelper(popup).Handle,out var r))
        {preferences.Left=r.Left;preferences.Top=r.Top;preferences.Save(preferencesPath);}
    }
    public void SetPinned(bool pinned)
    {
        bool previous=preferences.Pinned;preferences.Pinned=pinned;
        try{preferences.Save(preferencesPath);}catch{preferences.Pinned=previous;throw;}
        if(popup!=null)popup.Topmost=pinned;
        RememberPlacement();NotifyChanged();
    }
    public void SetHotkey(uint modifiers,uint key)
    {
        if(!DesktopShellPreferences.ValidHotkey(modifiers,key))throw new IOException("请选择包含 Ctrl、Alt 或 Win 的组合键；Win + V 由系统使用");
        if(registered&&modifiers==preferences.HotkeyModifiers&&key==preferences.HotkeyKey)return;
        int nextId=hotkeyId==703?704:703;
        if(systemIntegration&&!RegisterHotKey(messages.Handle,nextId,0x4000|modifiers,key))throw new IOException("这个快捷键已被占用，请换一个组合");
        uint oldModifiers=preferences.HotkeyModifiers,oldKey=preferences.HotkeyKey;
        preferences.HotkeyModifiers=modifiers;preferences.HotkeyKey=key;
        try{preferences.Save(preferencesPath);}
        catch{preferences.HotkeyModifiers=oldModifiers;preferences.HotkeyKey=oldKey;UnregisterHotKey(messages.Handle,nextId);throw;}
        if(registered)UnregisterHotKey(messages.Handle,hotkeyId);
        hotkeyId=nextId;registered=true;HotkeyError="";NotifyChanged();
    }
    static string FormatHotkey(uint mods,uint key)
    {
        string name=KeyInterop.KeyFromVirtualKey((int)key).ToString();
        return ((mods&2)!=0?"Ctrl + ":"")+((mods&1)!=0?"Alt + ":"")+((mods&4)!=0?"Shift + ":"")+((mods&8)!=0?"Win + ":"")+name;
    }
    public void Dispose()
    {
        if(disposed)return;
        try{RememberPlacement();}catch{}
        disposed=true;Current=null;
        popup?.Close();
        Sync?.Dispose();if(tray!=null){tray.Visible=false;tray.Dispose();}
        Updates.Dispose();
        if(systemIntegration)RemoveClipboardFormatListener(messages.Handle);
        if(registered)UnregisterHotKey(messages.Handle,hotkeyId);
        messages.RemoveHook(Hook);messages.Dispose();
    }
}
