using System;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Threading;
using System.Windows.Media.Imaging;
using Microsoft.Win32;
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
    public bool IsIsolated=>!systemIntegration;
    public int UiFailureCount{get;private set;}
    public void ReportUiFailure(Exception error)
    {
        UiFailureCount++;
        try{File.AppendAllText(Path.Combine(root,"ui-errors.log"),DateTimeOffset.UtcNow.ToString("O")+Environment.NewLine+error+Environment.NewLine);}
        catch{} // Reporting must not hide the original failure or touch the clipboard vault.
    }
    readonly HwndSource messages;
    readonly DesktopShellPreferences preferences;
    readonly string preferencesPath;
    readonly System.Windows.Forms.NotifyIcon? tray;
    LocalClipboardWindow? popup;
    ClipboardPasteTarget? pasteTarget;
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
    public bool DesktopResident=>preferences.DesktopResident;
    public string WindowMode=>Pinned?"pinned":DesktopResident?"desktop":"popup";
    public DesktopStoragePreferences Storage{get;}
    public string HotkeyError{get;private set;}="";
    public string HotkeyLabel=>FormatHotkey(preferences.HotkeyModifiers,preferences.HotkeyKey);
    public string Status
    {
        get
        {
            string status=Sync?.Status??SyncError??"共享尚未准备好";
            return Vault.RecoveryWarning is { } warning?status+" · "+warning:status;
        }
    }
    const int ClipboardMessage=0x031D,HotkeyMessage=0x0312;
    [DllImport("user32.dll")]static extern bool AddClipboardFormatListener(IntPtr hwnd);
    [DllImport("user32.dll")]static extern bool RemoveClipboardFormatListener(IntPtr hwnd);
    [DllImport("user32.dll")]static extern bool RegisterHotKey(IntPtr hwnd,int id,uint modifiers,uint key);
    [DllImport("user32.dll")]static extern bool UnregisterHotKey(IntPtr hwnd,int id);
    [DllImport("user32.dll")]static extern uint GetClipboardSequenceNumber();
    [DllImport("user32.dll")]static extern bool GetWindowRect(IntPtr hwnd,out RectNative rect);
    [DllImport("user32.dll")]static extern bool SetWindowPos(IntPtr hwnd,IntPtr after,int x,int y,int width,int height,uint flags);
    [DllImport("user32.dll")]static extern bool GetWindowPlacement(IntPtr hwnd,ref NativePlacement placement);
    [DllImport("user32.dll")]static extern bool SetWindowPlacement(IntPtr hwnd,in NativePlacement placement);
    [StructLayout(LayoutKind.Sequential)]struct NativePlacement
    {
        public uint Length,Flags,ShowCommand;
        public int MinX,MinY,MaxX,MaxY;
        public RectNative Normal;
    }
    static bool TryGetWindowPlacement(IntPtr window,out NativePlacement value)
    {
        value=new(){Length=(uint)Marshal.SizeOf<NativePlacement>()};
        return GetWindowPlacement(window,ref value);
    }
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
        string libraryRoot=dataRoot==null?Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"FloatClip","Library"):Path.Combine(dataRoot,"Library");
        Storage=DesktopStoragePreferences.Load(root,libraryRoot,!systemIntegration);
        if(systemIntegration&&updates!=null)throw new ArgumentException("正式宿主使用已配置的更新源");
        Updates=updates??new DesktopUpdates(Path.Combine(root,"Updates"));
        try{Capture=JsonNode.Parse(File.ReadAllText(Path.Combine(root,"clipboard-ui.json")))?["capture"]?.GetValue<bool>()??true;}catch{}
        string recoveryRoot=systemIntegration
            ?Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"FloatClipRecovery")
            :Path.Combine(dataRoot!,"Recovery");
        Vault=new ClipboardVault(libraryRoot,imageDirectoryOverride:Storage.ImageCachePath,recoveryDirectory:recoveryRoot);
        Vault.RequestRecoveryCheckpoint();
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
        var menu=new System.Windows.Forms.ContextMenuStrip{ShowImageMargin=false,ShowCheckMargin=true};
        var statusItem=new System.Windows.Forms.ToolStripMenuItem{Enabled=false};
        var clipboardItem=menu.Items.Add("打开剪贴板",null,(_,_)=>ShowPopup());
        var filesItem=menu.Items.Add("共享文件",null,(_,_)=>ShowFiles());
        var devicesItem=menu.Items.Add("我的设备",null,(_,_)=>ShowDevices());
        menu.Items.Add(new System.Windows.Forms.ToolStripSeparator());
        var captureItem=new System.Windows.Forms.ToolStripMenuItem("收录文字和图片",null,(_,_)=>{Capture=!Capture;SaveCapture();NotifyChanged();}){CheckOnClick=false};
        var residentItem=new System.Windows.Forms.ToolStripMenuItem("桌面驻留",null,(_,_)=>SetWindowMode(DesktopResident?"popup":"desktop")){CheckOnClick=false};
        var topmostItem=new System.Windows.Forms.ToolStripMenuItem("始终置顶",null,(_,_)=>SetWindowMode(Pinned?"popup":"pinned")){CheckOnClick=false};
        menu.Items.Add(captureItem);menu.Items.Add(residentItem);menu.Items.Add(topmostItem);
        menu.Items.Add(new System.Windows.Forms.ToolStripSeparator());
        menu.Items.Add("设置",null,(_,_)=>ShowSettings());
        menu.Items.Add("退出 FloatClip",null,(_,_)=>Application.Current.Shutdown());
        menu.Items.Insert(0,statusItem);menu.Items.Insert(1,new System.Windows.Forms.ToolStripSeparator());
        menu.Opening+=(_,_)=>{
            bool dark=(Registry.GetValue(@"HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize","AppsUseLightTheme",1) as int?)==0;
            menu.Renderer=new System.Windows.Forms.ToolStripProfessionalRenderer(new TrayColorTable(dark));
            menu.BackColor=dark?System.Drawing.Color.FromArgb(36,40,59):System.Drawing.Color.White;
            menu.ForeColor=dark?System.Drawing.Color.FromArgb(228,233,255):System.Drawing.Color.FromArgb(32,37,46);
            statusItem.Text=(Capture?"正在收录":"收录已暂停")+" · "+(Sync?.ConnectedCount??0)+" 台设备在线";
            captureItem.Checked=Capture;residentItem.Checked=DesktopResident;topmostItem.Checked=Pinned;
        };
        tray.ContextMenuStrip=menu;tray.DoubleClick+=(_,_)=>ShowPopup();
    }
    public void Start(bool background)
    {
        if(systemIntegration)ReadClipboard();Sync?.Start();
        if(!background||Pinned||DesktopResident)ShowPopup(activate:!background);
        if(systemIntegration)_=Updates.CheckAsync();
    }
    IntPtr Hook(IntPtr window,int message,IntPtr w,IntPtr l,ref bool handled)
    {
        if(message==ClipboardMessage)ReadClipboard();
        else if(message==HotkeyMessage&&w.ToInt32()==hotkeyId)
        {
            if(popup?.IsPanelPresented==true&&popup.IsActive&&WindowMode=="popup")popup.HideWithMotion();else ShowPopup();
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
                var image=ClipboardImages.Read(Clipboard.GetDataObject());
                if(image!=null&&(Capture||Sync?.ImageSharing==true))
                {
                    string hash=Convert.ToHexString(SHA256.HashData(image.Value.Png)).ToLowerInvariant();
                    bool existed=Vault.Load()["entries"]!.AsArray().OfType<JsonObject>()
                        .Any(c=>ClipboardVault.Kind(c)=="image"&&c["imageSha256"]?.GetValue<string>()==hash);
                    var snapshot=Vault.AddImage(image.Value.Png,image.Value.Width,image.Value.Height);
                    if(!existed)
                    {
                        var row=snapshot["entries"]!.AsArray().OfType<JsonObject>().FirstOrDefault(c=>
                            ClipboardVault.Kind(c)=="image"&&c["imageSha256"]?.GetValue<string>()==hash);
                        if(row!=null)Sync?.CopiedImage(row);
                    }
                }
                else Sync?.Copied("",true);
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
        // The item already exists in history. ClipboardWrites tags the native write so
        // the observer does not re-import it or echo it to the original device.
        NotifyChanged();
    }
    public void ShowPopup(bool activate=true)
    {
        if(disposed)return;
        var target=activate&&systemIntegration?ClipboardPasteTarget.Capture():null;
        popup??=new LocalClipboardWindow(this,true);
        popup.Topmost=Pinned;
        if(!popup.IsPanelPresented)
        {
            pasteTarget=target;
            if(!popup.IsVisible)
            {
                popup.WindowStartupLocation=WindowStartupLocation.Manual;
                var helper=new WindowInteropHelper(popup);
                helper.EnsureHandle();
                PlacePopup();
            }
            else if(popup.WindowState==WindowState.Minimized)
            {
                var helper=new WindowInteropHelper(popup);
                if(TryGetWindowPlacement(helper.Handle,out var placement))
                {
                    var position=PopupPosition(placement.Normal.Right-placement.Normal.Left,placement.Normal.Bottom-placement.Normal.Top);
                    // WINDOWPLACEMENT uses workspace coordinates for taskbar windows.
                    var area=System.Windows.Forms.Screen.FromPoint(position);
                    int x=position.X-(area.WorkingArea.Left-area.Bounds.Left);
                    int y=position.Y-(area.WorkingArea.Top-area.Bounds.Top);
                    placement.Normal=new(){Left=x,Top=y,Right=x+placement.Normal.Right-placement.Normal.Left,
                        Bottom=y+placement.Normal.Bottom-placement.Normal.Top};
                    SetWindowPlacement(helper.Handle,in placement);
                }
            }
        }
        else if(!popup.IsActive)pasteTarget=target;
        popup.ShowWithMotion(activate);
    }

    internal void PasteIntoPreviousEditor(Action<bool> done)
    {
        var target=pasteTarget;
        if(!systemIntegration||target==null||!target.RestoreFocus()){done(false);return;}
        uint sequence=lastSequence;
        dispatcher.BeginInvoke(DispatcherPriority.Input,new Action(()=>
            done(!disposed&&GetClipboardSequenceNumber()==sequence&&target.PasteIfStillFocused())));
    }
    internal void RecreatePopup(LocalClipboardWindow requested)
    {
        if(disposed||!ReferenceEquals(popup,requested))return;
        var state=requested.CapturePresentation();
        var replacement=new LocalClipboardWindow(this,true);
        replacement.RestorePresentation(state);replacement.ShowActivated=false;
        try{
            if(state.Visible)replacement.Show();else new WindowInteropHelper(replacement).EnsureHandle();
            requested.MoveAuxiliaryWindows(replacement);popup=replacement;requested.CloseForReplacement();
            if(state.Active)replacement.Activate();
        }catch{replacement.CloseForReplacement();throw;}
    }
    // Legacy entry points activate the same clipboard surface.
    public void ShowLibrary()=>ShowPopup();
    public void ShowDevices(){ShowPopup();popup?.OpenDevices();}
    public void ShowFiles(){ShowPopup();popup?.OpenFiles();}
    public void ShowSettings()
    {
        ShowPopup();popup?.OpenSettings();
    }
    void PlacePopup()
    {
        if(popup==null)return;
        var handle=new WindowInteropHelper(popup).Handle;
        if(!GetWindowRect(handle,out var rect))return;
        var position=PopupPosition(rect.Right-rect.Left,rect.Bottom-rect.Top);
        SetWindowPos(handle,IntPtr.Zero,position.X,position.Y,0,0,0x0015);
    }

    System.Drawing.Point PopupPosition(int width,int height)
    {
        var cursor=System.Windows.Forms.Cursor.Position;
        bool persistent=Pinned||DesktopResident;
        var anchor=persistent&&preferences.Left is int x&&preferences.Top is int y?new System.Drawing.Point(x,y):pasteTarget?.CaretBottom??cursor;
        var area=System.Windows.Forms.Screen.FromPoint(anchor).WorkingArea;
        int left=persistent&&preferences.Left!=null?preferences.Left.Value:anchor.X+(pasteTarget==null?12:0);
        int top=persistent&&preferences.Top!=null?preferences.Top.Value:anchor.Y+12;
        if(!persistent&&pasteTarget!=null&&top+height>area.Bottom&&pasteTarget.CaretTop.Y-height-12>=area.Top)
            top=pasteTarget.CaretTop.Y-height-12;
        return new(Math.Clamp(left,area.Left,Math.Max(area.Left,area.Right-width)),
            Math.Clamp(top,area.Top,Math.Max(area.Top,area.Bottom-height)));
    }
    public void RememberPlacement()
    {
        if(popup==null||popup.WindowState!=WindowState.Normal||(!Pinned&&!DesktopResident))return;
        if(GetWindowRect(new WindowInteropHelper(popup).Handle,out var r))
        {preferences.Left=r.Left;preferences.Top=r.Top;preferences.Save(preferencesPath);}
    }
    public void SetPinned(bool pinned)=>SetWindowMode(pinned?"pinned":"popup");
    public void SetWindowMode(string mode)
    {
        if(mode is not ("popup" or "desktop" or "pinned"))throw new IOException("窗口模式无效");
        bool oldPinned=preferences.Pinned,oldResident=preferences.DesktopResident;
        preferences.Pinned=mode=="pinned";preferences.DesktopResident=mode=="desktop";
        try{preferences.Save(preferencesPath);}catch{preferences.Pinned=oldPinned;preferences.DesktopResident=oldResident;throw;}
        if(popup!=null){popup.Topmost=preferences.Pinned;if(mode!="popup"&&!popup.IsVisible)ShowPopup();}
        RememberPlacement();NotifyChanged();
    }
    public void SetImageCachePath(string value)
    {
        string previous=Storage.ImageCachePath,target=DesktopStoragePreferences.NormalizeDirectory(value,previous);
        if(string.Equals(previous,target,StringComparison.OrdinalIgnoreCase))return;
        Vault.SetImageDirectory(target);Storage.ImageCachePath=target;
        try{Storage.Save(root);}catch{Storage.ImageCachePath=previous;try{Vault.SetImageDirectory(previous);}catch{}throw;}
        NotifyChanged();
    }
    public void SetDefaultSavePath(string value)
    {
        string previous=Storage.DefaultSavePath;Storage.DefaultSavePath=DesktopStoragePreferences.NormalizeDirectory(value,previous);
        try{Storage.Save(root);}catch{Storage.DefaultSavePath=previous;throw;}
        NotifyChanged();
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
        Vault.FlushRecoveryCheckpoint();
        Updates.Dispose();
        if(systemIntegration)RemoveClipboardFormatListener(messages.Handle);
        if(registered)UnregisterHotKey(messages.Handle,hotkeyId);
        messages.RemoveHook(Hook);messages.Dispose();
    }
}
