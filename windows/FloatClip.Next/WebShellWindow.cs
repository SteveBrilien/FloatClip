using System;
using System.IO;
using System.Linq;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.Wpf;
using Microsoft.Win32;
using FloatClip.Desktop;
using FloatClip.Share;

namespace FloatClip.Next;
public sealed class WebShellWindow:Window
{
    public const string Origin="https://floatclip.local";
    readonly ClipboardHost host;readonly bool isolated;
    readonly WebView2 browser=new();readonly FileShelfStore shelf;
    readonly DispatcherTimer refresh=new(){Interval=TimeSpan.FromMilliseconds(160)};
    readonly CancellationTokenSource lifetime=new();CancellationTokenSource? operation;
    readonly Dictionary<string,string> thumbnails=new();
    readonly TaskCompletionSource ready=new(TaskCreationOptions.RunContinuationsAsynchronously);
    JsonObject preferences=new();string theme="system",page="clipboard",taskName="",taskState="";double taskProgress;bool closed;
    public Task Ready=>ready.Task;
    public WebView2 Browser=>browser;
    public WebShellWindow(ClipboardHost host,bool isolated=false)
    {
        this.host=host;this.isolated=isolated;shelf=host.Sync?.Files.Store??new FileShelfStore(Path.Combine(host.PreferencesRoot,"file-shelf.json"));
        Title="FloatClip 0.8.0 · 界面迁移预览";Width=920;Height=740;MinWidth=460;MinHeight=440;WindowStartupLocation=WindowStartupLocation.CenterScreen;WindowStyle=WindowStyle.None;ResizeMode=ResizeMode.CanResize;UseLayoutRounding=true;
        System.Windows.Shell.WindowChrome.SetWindowChrome(this,new(){CaptionHeight=0,ResizeBorderThickness=new(6),GlassFrameThickness=new(0)});
        Content=browser;ReadPreferences();ApplyPalette();
        Loaded+=async(_,_)=>{try{await Initialize();}catch(Exception e){ready.TrySetException(e);Content=new System.Windows.Controls.TextBlock{Text="界面启动失败："+e.Message,TextWrapping=TextWrapping.Wrap,Margin=new(24)};}};
        host.Changed+=Schedule;host.Updates.Changed+=Schedule;if(host.Sync!=null){host.Sync.Files.Changed+=Schedule;host.Sync.Transfers.Changed+=Schedule;}
        refresh.Tick+=(_,_)=>{refresh.Stop();Publish();};
        Closing+=(_,e)=>{if(!host.IsExiting&&!isolated){e.Cancel=true;host.RememberPlacement();Hide();}};
        Closed+=(_,_)=>{closed=true;lifetime.Cancel();operation?.Cancel();refresh.Stop();host.Changed-=Schedule;host.Updates.Changed-=Schedule;if(host.Sync!=null){host.Sync.Files.Changed-=Schedule;host.Sync.Transfers.Changed-=Schedule;}browser.Dispose();};
    }
    void ReadPreferences(){try{preferences=JsonNode.Parse(File.ReadAllText(Path.Combine(host.PreferencesRoot,"clipboard-ui.json"))) as JsonObject??new();}catch{}try{theme=File.ReadAllText(Path.Combine(host.PreferencesRoot,"theme.txt")).Trim();}catch{}if(!new[]{"system","light","dark","tokyo-night"}.Contains(theme))theme="system";}
    void SavePreferences(){File.WriteAllText(Path.Combine(host.PreferencesRoot,"clipboard-ui.json"),preferences.ToJsonString());File.WriteAllText(Path.Combine(host.PreferencesRoot,"theme.txt"),theme);}
    void ApplyPalette()
    {
        bool dark=theme is "dark" or "tokyo-night"||theme=="system"&&(int?)Registry.GetValue("HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize","AppsUseLightTheme",1)==0;
        bool tokyo=theme=="tokyo-night";SolidColorBrush B(string light,string night)=>new((Color)ColorConverter.ConvertFromString(dark?night:light));
        var bg=B("#F5F6F8",tokyo?"#1A1B26":"#181B20");var surface=B("#FFFFFF",tokyo?"#24283B":"#24282F");var text=B("#20252E",tokyo?"#E4E9FF":"#EEF1F6");var muted=B("#5E6A7D",tokyo?"#BBC4E5":"#BDC6D6");var line=B("#E4E8EE",tokyo?"#414868":"#3A424E");var tint=B("#EAF1FD",tokyo?"#283457":"#293A52");var accent=B("#366AC7",tokyo?"#7AA2F7":"#91BBFF");
        var resources=Application.Current.Resources;foreach(var p in new Dictionary<string,Brush>{{"Background",bg},{"Surface",surface},{"Text",text},{"Muted",muted},{"Line",line},{"Tint",tint},{"Accent",accent}})resources["FloatClip."+p.Key]=p.Value;
        DesktopFluent.Apply(dark,bg,surface,text,muted,line,tint,accent);Background=bg;
    }
    async Task Initialize()
    {
        string assets=Path.Combine(AppContext.BaseDirectory,"UI");if(!File.Exists(Path.Combine(assets,"index.html"))||!File.Exists(Path.Combine(assets,"NotoColorEmoji-Regular.ttf")))throw new IOException("缺少 UI 目录，请完整解压试用包");
        var environment=await CoreWebView2Environment.CreateAsync(null,Path.Combine(host.PreferencesRoot,"WebView2"));await browser.EnsureCoreWebView2Async(environment);
        var core=browser.CoreWebView2;core.SetVirtualHostNameToFolderMapping("floatclip.local",assets,CoreWebView2HostResourceAccessKind.DenyCors);
        core.Settings.AreHostObjectsAllowed=false;core.Settings.AreDevToolsEnabled=isolated;core.Settings.AreDefaultContextMenusEnabled=false;core.Settings.IsStatusBarEnabled=false;core.Settings.IsZoomControlEnabled=false;
        core.NavigationStarting+=(_,e)=>{if(!Trusted(e.Uri))e.Cancel=true;};core.FrameNavigationStarting+=(_,e)=>e.Cancel=true;
        core.NewWindowRequested+=(_,e)=>e.Handled=true;core.PermissionRequested+=(_,e)=>e.State=CoreWebView2PermissionState.Deny;core.DownloadStarting+=(_,e)=>e.Cancel=true;
        core.WebMessageReceived+=Receive;
        core.NavigationCompleted+=(_,e)=>{if(e.IsSuccess){Publish();ready.TrySetResult();}else ready.TrySetException(new IOException("本地界面加载失败"));};
        core.Navigate(Origin+"/index.html");
    }
    public static bool Trusted(string value)=>Uri.TryCreate(value,UriKind.Absolute,out var uri)&&uri.Scheme=="https"&&uri.Host=="floatclip.local"&&uri.IsDefaultPort&&uri.UserInfo.Length==0;
    public void Navigate(string value){if(!new[]{"clipboard","files","devices","settings","updates"}.Contains(value))return;page=value;Schedule();}
    void Schedule(){if(closed)return;Dispatcher.BeginInvoke(new Action(()=>{if(!closed&&!refresh.IsEnabled)refresh.Start();}));}
    static string Format(long value)=>DateTimeOffset.FromUnixTimeMilliseconds(Math.Max(0,value)).ToLocalTime().ToString("yyyy/MM/dd HH:mm");
    string Thumbnail(JsonObject row)
    {
        string key=row["imageRef"]?.ToString()??"";if(thumbnails.TryGetValue(key,out var image))return image;
        try{using var input=new MemoryStream(host.Vault.ReadImage(row));var bitmap=new BitmapImage();bitmap.BeginInit();bitmap.CacheOption=BitmapCacheOption.OnLoad;bitmap.DecodePixelWidth=400;bitmap.StreamSource=input;bitmap.EndInit();var encoder=new PngBitmapEncoder();encoder.Frames.Add(BitmapFrame.Create(bitmap));using var output=new MemoryStream();encoder.Save(output);image="data:image/png;base64,"+Convert.ToBase64String(output.ToArray());if(thumbnails.Count>=32)thumbnails.Clear();thumbnails[key]=image;return image;}catch{return "";}
    }
    public object Snapshot()
    {
        var own=host.Vault.Device()["id"]!.ToString();var snapshot=host.Sync?.Snapshot();
        var clips=host.Vault.Load()["entries"]!.AsArray().OfType<JsonObject>().OrderByDescending(ClipboardVault.IsPinned).ThenByDescending(c=>c["updatedAt"]!.GetValue<long>()).Take(200).Select(c=>new{id=c["id"]!.ToString(),text=c["text"]?.ToString() is string t?t[..Math.Min(t.Length,1200)]:"",source=c["source"]?["id"]?.ToString()==own?"本机":c["source"]?["name"]?.ToString()??"设备",time=Format(c["updatedAt"]!.GetValue<long>()),pinned=ClipboardVault.IsPinned(c),locked=ClipboardVault.IsLocked(c),image=ClipboardVault.Kind(c)=="image"?Thumbnail(c):""}).ToArray();
        var files=new List<object>();foreach(var f in shelf.Load())files.Add(new{id="local:"+FileCatalog.LocalId(f),name=f.Name,ext=Path.GetExtension(f.Name).TrimStart('.').ToLowerInvariant(),size=f.Size,modified=f.LastWriteUtcTicks>0?new DateTime(f.LastWriteUtcTicks,DateTimeKind.Utc).ToLocalTime().ToString("yyyy/MM/dd HH:mm"):"",source="本机",local=true,online=f.IsAvailable,kept=f.Kept,status=f.IsAvailable?"本机文件":"源文件已移除",done=0L});
        if(host.Sync!=null)foreach(var f in host.Sync.Files.Snapshot())files.Add(new{id=f.Key,name=f.Name,ext=Path.GetExtension(f.Name).TrimStart('.').ToLowerInvariant(),size=f.Size,modified=Format(f.Modified),source=host.Sync.Files.PeerName(f.Peer),local=host.Sync.Files.IsLocal(f),online=f.Online,kept=f.Kept,status=host.Sync.Files.IsLocal(f)?"已下载":f.Online?"未下载":"来源离线",done=f.Done});
        var peers=host.Sync?.Pairing.Keys().Select(key=>{snapshot!.Peers.TryGetValue(key,out var peer);return new{id=key,name=peer?.Device?.Name??host.Sync.Pairing.Describe(key)?.Name??"未命名设备",enabled=peer?.Enabled??true,transport=host.Sync.ConnectionTransport(key)??"",pending=peer?.Pending!=null};}).ToArray();
        var update=host.Updates.Snapshot;
        var activeDownload=host.Sync?.Files.Snapshot().FirstOrDefault(f=>f.Name==taskName&&f.Done>0&&taskState=="进行中");
        double progress=activeDownload==null?taskProgress:activeDownload.Size==0?100:100d*activeDownload.Done/activeDownload.Size;
        return new{version="0.8.0",preview=true,page,theme,pinned=host.Pinned,capture=host.Capture,font=preferences["clipFontSize"]?.GetValue<double>()??14,sharing=snapshot?.Enabled??false,send=snapshot?.Send??false,receive=snapshot?.Receive??false,apply=snapshot?.Apply??false,automatic=host.Sync?.Automatic??false,status=host.Status,clips,files,peers=peers??Array.Empty<object>(),transfers=host.Sync?.Transfers.Snapshot().Select(t=>new{id=t.Id.ToString(),name=t.Name,phase=t.Phase.ToString(),progress=t.Total==0?0:100d*t.Done/t.Total,message=t.Message}).Take(8).ToArray(),updates=new{phase=update.Phase.ToString(),message=update.Message,progress=update.Progress,automatic=host.Updates.Automatic},task=taskName.Length==0?null:new{name=taskName,state=taskState,progress}};
    }
    void Publish(){if(closed||browser.CoreWebView2==null||!Trusted(browser.Source?.AbsoluteUri??""))return;try{browser.CoreWebView2.PostWebMessageAsJson(JsonSerializer.Serialize(new{type="snapshot",data=Snapshot()}));}catch(Exception e){PostError(e.Message);}}
    void PostError(string message){if(browser.CoreWebView2!=null)browser.CoreWebView2.PostWebMessageAsJson(JsonSerializer.Serialize(new{type="error",message}));}
    JsonObject FindClip(string id)=>host.Vault.Load()["entries"]!.AsArray().OfType<JsonObject>().FirstOrDefault(c=>c["id"]?.ToString()==id)??throw new IOException("记录已变化，请重试");
    string LocalPath(string id){if(id.StartsWith("local:",StringComparison.Ordinal))return shelf.Load().FirstOrDefault(f=>"local:"+FileCatalog.LocalId(f)==id)?.Path??throw new IOException("文件已移除");var row=host.Sync?.Files.Snapshot().FirstOrDefault(f=>f.Key==id);return row!=null&&host.Sync!.Files.IsLocal(row)?row.LocalPath:throw new IOException("请先下载文件");}
    async void Receive(object? sender,CoreWebView2WebMessageReceivedEventArgs e)
    {
        if(!Trusted(e.Source))return;
        try{if(e.WebMessageAsJson.Length>16384)throw new IOException("请求过大");using var json=JsonDocument.Parse(e.WebMessageAsJson);var r=json.RootElement;string op=r.GetProperty("op").GetString()??"";string id=r.TryGetProperty("id",out var v)?v.GetString()??"":"";
            switch(op){
            case "ready":break;
            case "navigate":Navigate(r.GetProperty("page").GetString()??"");break;
            case "hide":Hide();break;
            case "drag":if(!isolated){ReleaseCapture();SendMessage(new WindowInteropHelper(this).Handle,0xA1,(IntPtr)2,IntPtr.Zero);}break;
            case "pin-window":host.SetPinned(!host.Pinned);break;
            case "copy":var clip=FindClip(id);if(ClipboardVault.Kind(clip)=="image")host.CopyImageByUser(host.Vault.ReadImage(clip));else host.CopyByUser(clip["text"]!.ToString());break;
            case "clip-delete":case "clip-pin":case "clip-lock":var saved=host.Vault.Load();var rows=saved["entries"]!.AsArray();var row=rows.OfType<JsonObject>().FirstOrDefault(c=>c["id"]?.ToString()==id)??throw new IOException("记录已移除");if(op=="clip-delete")rows.Remove(row);else{string field=op=="clip-pin"?"pinned":"locked";row[field]=!(row[field]?.GetValue<bool>()??false);}host.Vault.Commit(saved["revision"]!.ToString(),rows);host.Vault.PruneOrphanedImages();host.NotifyChanged();break;
            case "clear":host.Vault.ClearUnprotected();host.Vault.PruneOrphanedImages();host.NotifyChanged();break;
            case "clip-detail":var detail=FindClip(id);browser.CoreWebView2.PostWebMessageAsJson(JsonSerializer.Serialize(new{type="detail",text=detail["text"]?.ToString()??"",image=ClipboardVault.Kind(detail)=="image"?Thumbnail(detail):""}));break;
            case "configure":Configure(r);break;
            case "reconnect":host.Sync?.ReconnectNow();break;
            case "peer-toggle":host.Sync?.SetPeerEnabled(id,r.GetProperty("value").GetBoolean());break;
            case "peer-forget":if(MessageBox.Show(this,"移除后需要重新核对识别码。","移除配对",MessageBoxButton.OKCancel)==MessageBoxResult.OK)host.Sync?.Forget(id);break;
            case "pair-bluetooth":await PairBluetooth();break;
            case "discover":await Discover();break;
            case "pair-lan":await PairLan(id);break;
            case "cancel":operation?.Cancel();break;
            case "accept-transfer":host.Sync?.Transfers.Accept(Guid.Parse(id));break;
            case "decline-transfer":host.Sync?.Transfers.Decline(Guid.Parse(id));break;
            case "cancel-transfer":host.Sync?.Transfers.Cancel(Guid.Parse(id));break;
            case "add-file":var dialog=new OpenFileDialog{Multiselect=true};if(dialog.ShowDialog(this)==true){foreach(string path in dialog.FileNames)shelf.Add(path);host.Sync?.Files.Publish();}break;
            case "download":case "save-file":await SaveFiles(r,op=="save-file");break;
            case "preview":new FilePreviewWindow(LocalPath(id)){Owner=this}.Show();break;
            case "reveal":var info=new ProcessStartInfo("explorer.exe"){UseShellExecute=true};info.ArgumentList.Add("/select,"+LocalPath(id));Process.Start(info);break;
            case "forward":if(host.Sync==null)throw new IOException("请先连接局域网设备");string pathToSend=LocalPath(id);var peer=host.Sync.Transfers.Peers().FirstOrDefault(p=>p.Key==r.GetProperty("peer").GetString())??throw new IOException("设备已离线");host.Sync.Transfers.Send(peer.Key,pathToSend);break;
            case "file-remove":case "file-keep":if(id.StartsWith("local:")){var entry=shelf.Load().First(f=>"local:"+FileCatalog.LocalId(f)==id);if(op=="file-keep")shelf.Keep(entry.Path,!entry.Kept);else shelf.Remove(entry.Path);host.Sync?.Files.Publish();}else{var remote=host.Sync?.Files.Snapshot().First(f=>f.Key==id)??throw new IOException("文件不存在");if(op=="file-keep")host.Sync!.Files.Keep(remote,!remote.Kept);else host.Sync!.Files.Clear(new[]{remote},true,false);}break;
            case "check-update":await host.Updates.CheckAsync(true);break;
            case "download-update":await host.Updates.DownloadAsync();break;
            case "install-update":throw new IOException("0.8.0 迁移预览暂使用独立试用包，正式升级入口待打包验证后开放");
            default:throw new IOException("不支持的操作");}
            Publish();
        }catch(OperationCanceledException){PostError("已取消");Publish();}catch(Exception error){PostError(error is IOException?error.Message:"操作未完成，请重试");Publish();}
    }
    void Configure(JsonElement request)
    {
        string key=request.GetProperty("key").GetString()??"";var value=request.GetProperty("value");
        if(key=="theme"){string selected=value.GetString()??"";if(!new[]{"system","light","dark","tokyo-night"}.Contains(selected))throw new IOException("主题无效");theme=selected;SavePreferences();ApplyPalette();return;}
        if(key=="font"){double number=value.GetDouble();if(number<12||number>24)throw new IOException("字号无效");preferences["clipFontSize"]=number;SavePreferences();return;}
        bool enabled=value.GetBoolean();if(key=="capture"){host.Capture=enabled;preferences["capture"]=enabled;SavePreferences();return;}if(key=="checkOnStart"){host.Updates.SetAutomatic(enabled);return;}
        var sync=host.Sync??throw new IOException("共享暂不可用");var s=sync.Snapshot();if(!new[]{"sharing","send","receive","apply","automatic"}.Contains(key))throw new IOException("设置无效");sync.Configure(key=="sharing"?enabled:s.Enabled,key=="send"?enabled:s.Send,key=="receive"?enabled:s.Receive,key=="apply"?enabled:s.Apply,key=="automatic"?enabled:sync.Automatic);
    }
    bool Confirm(string code)=>Dispatcher.Invoke(()=>MessageBox.Show(this,"请核对手机显示的识别码：\n\n"+code+"\n\n一致后在手机允许连接。","设备配对",MessageBoxButton.OKCancel)==MessageBoxResult.OK);
    readonly Dictionary<string,NearbyDevice> discovered=new();
    async Task Connection(string name,Func<CancellationToken,Task> action){using var token=BeginOperation(name);try{await action(token.Token);taskState="已完成";}catch{taskState=token.IsCancellationRequested?"已取消":"未完成";throw;}finally{operation=null;Schedule();}}
    Task PairBluetooth(){if(host.Sync==null)throw new IOException("共享暂不可用");return Connection("查找蓝牙设备",token=>host.Sync.PairBluetoothAsync(Confirm,token));}
    Task Discover()=>Connection("查找局域网设备",async token=>{discovered.Clear();foreach(var network in Nearby.Networks())foreach(var device in await Nearby.Discover(network,token,true))discovered[device.Address.ToString()]=device;browser.CoreWebView2.PostWebMessageAsJson(JsonSerializer.Serialize(new{type="discovery",devices=discovered.Select(p=>new{id=p.Key,name=p.Value.Name}).ToArray()}));});
    Task PairLan(string id){if(host.Sync==null||!discovered.TryGetValue(id,out var device))throw new IOException("请先查找设备");return Connection("正在配对",token=>host.Sync.PairAsync(device,Confirm,token));}
    CancellationTokenSource BeginOperation(string name){if(operation!=null&&!operation.IsCancellationRequested&&taskState=="进行中")throw new IOException("请等待当前操作完成或取消");operation=CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token);taskName=name;taskState="进行中";taskProgress=0;Schedule();return operation;}
    async Task SaveFiles(JsonElement request,bool rename)
    {
        string[] ids=request.GetProperty("ids").EnumerateArray().Select(v=>v.GetString()??"").ToArray();if(ids.Length is <1 or >64)throw new IOException("请选择 1 到 64 个文件");string? folder=null,target=null;
        if(rename&&ids.Length==1){string name=ids[0].StartsWith("local:")?shelf.Load().First(f=>"local:"+FileCatalog.LocalId(f)==ids[0]).Name:host.Sync!.Files.Snapshot().First(f=>f.Key==ids[0]).Name;var save=new SaveFileDialog{FileName=name,OverwritePrompt=true};if(save.ShowDialog(this)!=true)return;target=save.FileName;}
        else{var choose=new OpenFolderDialog{Title="保存到文件夹"};if(choose.ShowDialog(this)!=true)return;folder=choose.FolderName;}
        using var token=BeginOperation("下载文件");int index=0;
        try{foreach(string id in ids){token.Token.ThrowIfCancellationRequested();string local;if(id.StartsWith("local:"))local=LocalPath(id);else{var item=host.Sync?.Files.Snapshot().First(f=>f.Key==id)??throw new IOException("来源不可用");taskName=item.Name;Schedule();local=await host.Sync!.Files.Download(item,token.Token);}string destination=target??Path.Combine(folder!,id.StartsWith("local:")?shelf.Load().First(f=>"local:"+FileCatalog.LocalId(f)==id).Name:host.Sync!.Files.Snapshot().First(f=>f.Key==id).Name);if(!string.Equals(Path.GetFullPath(local),Path.GetFullPath(destination),StringComparison.OrdinalIgnoreCase)){if(File.Exists(destination)&&MessageBox.Show(this,"覆盖已有文件 "+Path.GetFileName(destination)+"？","另存为",MessageBoxButton.OKCancel)!=MessageBoxResult.OK)continue;File.Copy(local,destination,true);}taskProgress=++index*100d/ids.Length;Schedule();}taskState="已完成";}catch{taskState=token.IsCancellationRequested?"已取消":"未完成";throw;}finally{operation=null;Schedule();}
    }
    [DllImport("user32.dll")]static extern bool ReleaseCapture();[DllImport("user32.dll")]static extern IntPtr SendMessage(IntPtr window,int message,IntPtr w,IntPtr l);
}
