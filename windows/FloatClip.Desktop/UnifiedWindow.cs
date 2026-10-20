using System;
using System.IO;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Microsoft.Win32;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.Wpf;

namespace FloatClip.Desktop;

public sealed class UnifiedWindow:Window
{
    public const string Version="0.7.1",Origin="https://clip.wmy-cloud.cn";
    readonly ClipboardVault vault=new();
    readonly string root=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"FloatClip","Host");
    WebView2? browser;
    Transfer? transfer;
    sealed class Transfer{public required string Token,Name,Path;public long Size,Bytes;}
    public UnifiedWindow(){Title="FloatClip";Width=1120;Height=820;MinWidth=620;MinHeight=540;WindowStartupLocation=WindowStartupLocation.CenterScreen;FontFamily=new FontFamily("Microsoft YaHei UI, Segoe UI");Background=Brush("#F4F7FC");Loaded+=async(_,_)=>await Open();Closed+=(_,_)=>{CancelTransfer();browser?.Dispose();};}
    static SolidColorBrush Brush(string color)=>new((Color)ColorConverter.ConvertFromString(color));
    public static bool IsTrusted(string? url)=>HostPolicy.IsTrusted(url);
    async System.Threading.Tasks.Task Open()
    {
        try{
            browser?.Dispose();Directory.CreateDirectory(root);var view=new WebView2();browser=view;Content=view;
            var environment=await CoreWebView2Environment.CreateAsync(null,Path.Combine(root,"Profile"));
            await view.EnsureCoreWebView2Async(environment);var core=view.CoreWebView2;
            core.Settings.UserAgent=core.Settings.UserAgent+" FloatClipNative/1 Windows/0.7.1";
            core.Settings.AreHostObjectsAllowed=false;core.Settings.IsWebMessageEnabled=true;core.Settings.AreDevToolsEnabled=false;core.Settings.IsStatusBarEnabled=false;
            core.NavigationStarting+=(_,e)=>{if(IsTrusted(e.Uri))return;e.Cancel=true;if(e.IsUserInitiated)External(e.Uri);};
            core.NewWindowRequested+=(_,e)=>{e.Handled=true;if(e.IsUserInitiated)External(e.Uri);};
            core.PermissionRequested+=(_,e)=>e.State=CoreWebView2PermissionState.Deny;
            core.WebMessageReceived+=(_,e)=>{
                if(!IsTrusted(e.Source)||!IsTrusted(core.Source))return;
                string id="";object? data=null;bool ok=false;string? error=null;
                try{
                    string raw=e.WebMessageAsJson;if(Encoding.UTF8.GetByteCount(raw)>18*1024*1024)throw new IOException("本机请求过大");
                    var request=JsonNode.Parse(raw)!.AsObject();id=request["id"]!.GetValue<string>();if(!Guid.TryParse(id,out Guid parsedRequestId))throw new IOException("请求编号无效");
                    data=Invoke(request["op"]!.GetValue<string>(),request["args"]?.AsObject()??new());ok=true;
                }catch(Exception ex){error=ex is IOException?ex.Message:"本机操作未完成，请检查剪贴板或存储权限";}
                try{core.PostWebMessageAsJson(JsonSerializer.Serialize(new{id,ok,data,error}));}catch{}
            };
            core.ProcessFailed+=(_,_)=>Fallback("共享页面的运行组件已停止，请重新打开。",false);
            view.NavigationCompleted+=(_,e)=>{if(!e.IsSuccess)Fallback("无法打开共享页面，请检查网络或先使用本机功能。",false);};
            core.Navigate(Origin+"/?screen=devices");
        }catch(WebView2RuntimeNotFoundException){Fallback("首次使用需要 Microsoft Edge WebView2 Runtime。安装后重新打开 FloatClip。",true);}
        catch{Fallback("跨网设备页面暂不可用；本机剪贴板和已授权设备仍可继续使用。",true);}
    }
    object? Invoke(string op,JsonObject a)
    {
        switch(op){
            case "info":return new{bridge=1,version=Version,platform="windows",device=vault.Device(),legacy=true};
            case "vault.load":return vault.Load();
            case "vault.commit":return vault.Commit(a["revision"]!.GetValue<string>(),a["entries"]!.AsArray());
            case "clipboard.read":{string value=Clipboard.ContainsText()?Clipboard.GetText():"";if(value.Length>1000000)throw new IOException("剪贴板文字过长");return value;}
            case "clipboard.write":{string value=a["text"]!.GetValue<string>();if(value.Length>1000000)throw new IOException("文字过长");ClipboardWrites.Write(value);return true;}
            case "theme.set":{
                string mode=a["theme"]!.GetValue<string>();browser!.CoreWebView2.Profile.PreferredColorScheme=mode switch{"dark"=>CoreWebView2PreferredColorScheme.Dark,"light"=>CoreWebView2PreferredColorScheme.Light,"system"=>CoreWebView2PreferredColorScheme.Auto,_=>throw new IOException("主题无效")};
                File.WriteAllText(Path.Combine(root,"theme.txt"),mode);Background=Brush(mode=="dark"?"#0C1422":"#F4F7FC");return true;}
            case "legacy.open":{
                string page=a["page"]!.GetValue<string>();if(page!="clipboard")throw new IOException("旧连接页面已移除");new LocalClipboardWindow(vault).Show();return true;}
            case "file.begin":{
                if(transfer!=null)throw new IOException("请先完成当前文件保存");string name=a["name"]!.GetValue<string>();long size=a["size"]!.GetValue<long>();
                if(size<0||size>64L*1024*1024||string.IsNullOrWhiteSpace(name))throw new IOException("文件信息无效");
                foreach(char c in Path.GetInvalidFileNameChars())name=name.Replace(c,'_');name=System.Text.RegularExpressions.Regex.Replace(name,"[\\u202a-\\u202e\\u2066-\\u2069]","_");name=name.TrimEnd('.',' ');if(name.Length>180)name=name[..180];if(name.Length==0)name="FloatClip-file";
                string token=Guid.NewGuid().ToString();string cache=Path.Combine(root,"Save");Directory.CreateDirectory(cache);string path=Path.Combine(cache,token+".part");
                using(var file=new FileStream(path,FileMode.CreateNew,FileAccess.Write)){}transfer=new(){Token=token,Name=name,Path=path,Size=size};return new{token};}
            case "file.chunk":{
                var t=GetTransfer(a);if(a["offset"]!.GetValue<long>()!=t.Bytes)throw new IOException("文件顺序无效");string encoded=a["base64"]!.GetValue<string>();if(encoded.Length>22000)throw new IOException("文件分片过大");byte[] bytes=Convert.FromBase64String(encoded);
                if(bytes.Length is <1 or >16384||t.Bytes+bytes.Length>t.Size)throw new IOException("文件大小不匹配");using(var stream=new FileStream(t.Path,FileMode.Append,FileAccess.Write))stream.Write(bytes);t.Bytes+=bytes.Length;return true;}
            case "file.finish":{
                var t=GetTransfer(a);if(t.Bytes!=t.Size)throw new IOException("文件尚未完整接收");
                try{var dialog=new SaveFileDialog{Title="保存 FloatClip 文件",FileName=t.Name,OverwritePrompt=true};if(dialog.ShowDialog(this)!=true)return new{cancelled=true};using var input=File.OpenRead(t.Path);using var output=new FileStream(dialog.FileName,FileMode.Create,FileAccess.Write);input.CopyTo(output);output.Flush(true);return new{saved=true};}
                finally{CancelTransfer();}}
            case "file.cancel":if(transfer?.Token==a["token"]?.GetValue<string>())CancelTransfer();return true;
            default:throw new IOException("不支持的本机操作");
        }
    }
    Transfer GetTransfer(JsonObject a){if(transfer==null||a["token"]?.GetValue<string>()!=transfer.Token)throw new IOException("文件保存已结束");return transfer;}
    void CancelTransfer(){if(transfer!=null){try{File.Delete(transfer.Path);}catch{}transfer=null;}}
    static void External(string url){if(Uri.TryCreate(url,UriKind.Absolute,out var u)&&u.Scheme=="https"&&u.UserInfo.Length==0)try{Process.Start(new ProcessStartInfo(url){UseShellExecute=true});}catch{}}
    void Fallback(string message,bool install)
    {
        var panel=new StackPanel{MaxWidth=520,Margin=new(32),VerticalAlignment=VerticalAlignment.Center};
        panel.Children.Add(new TextBlock{Text="FloatClip",FontSize=32,Foreground=Brush("#19283F"),Margin=new(0,0,0,18)});
        panel.Children.Add(new TextBlock{Text=message,FontSize=16,TextWrapping=TextWrapping.Wrap,Foreground=Brush("#596C85"),Margin=new(0,0,0,20)});
        void Button(string label,Action action){var b=new Button{Content=label,Padding=new(18,11,18,11),Margin=new(0,5,0,5),HorizontalContentAlignment=HorizontalAlignment.Center};b.Click+=(_,_)=>{try{action();}catch{MessageBox.Show(this,"暂时无法打开，请稍后重试。","FloatClip");}};panel.Children.Add(b);}
        Button("重新打开跨网设备",()=>_ = Open());Button("本机剪贴板",()=>new LocalClipboardWindow(vault).Show());
        if(install)Button("安装 Microsoft WebView2",()=>External("https://developer.microsoft.com/microsoft-edge/webview2/"));
        Button("在浏览器打开",()=>External(Origin));Content=panel;
    }
}
