using System;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using FloatClip.Desktop;
using FloatClip.Share;

static class Program
{
    static int checks;
    static void Check(bool pass,string label){if(!pass)throw new Exception(label);checks++;}
    static async Task Reject(Func<Task> action,string label){try{await action();}catch(IOException){checks++;return;}throw new Exception(label);}
    static async Task RejectMessage(Func<Task> action,string expected){try{await action();}catch(IOException e){Check(e.Message.Contains(expected),"specific update source error: "+expected);return;}throw new Exception("Missing update error: "+expected);}
    sealed class CheckHandler(Func<HttpRequestMessage,CancellationToken,Task<HttpResponseMessage>> send):HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request,CancellationToken token)=>send(request,token);
    }
    static async Task Main()
    {
        string root=Path.Combine(Path.GetTempPath(),"FloatClip-UpdateChecks-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try{
            byte[] bytes=Encoding.UTF8.GetBytes("generated update package");string hash=Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
            var release=new UpdateRelease(ShareUpdates.Build+1,"0.8.0","generated notes","https://github.com/SteveBrilien/FloatClip/releases/download/test/FloatClip.zip",hash,bytes.Length);
            byte[] Feed(string platformVersion="0.8.0")=>JsonSerializer.SerializeToUtf8Bytes(new{schema=1,channel="share-preview",version="0.7.1",notes="notes",windows=new{version=platformVersion,build=ShareUpdates.Build+1,url=release.Url,sha256=hash,bytes=bytes.Length,status="available"}});
            using(var validHttp=new HttpClient(new CheckHandler((request,_)=>Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK){RequestMessage=request,Content=new ByteArrayContent(Feed())})))){
                Check((await ShareUpdates.CheckWithClient(validHttp)).Version=="0.8.0","update response parsed through actual request path");
            }
            using(var unavailable=new HttpClient(new CheckHandler((request,_)=>Task.FromResult(new HttpResponseMessage(HttpStatusCode.ServiceUnavailable){RequestMessage=request}))))await RejectMessage(()=>ShareUpdates.CheckWithClient(unavailable),"HTTP 503");
            using(var timeout=new HttpClient(new CheckHandler((_,_)=>throw new TaskCanceledException("generated timeout"))))await RejectMessage(()=>ShareUpdates.CheckWithClient(timeout),"超时");
            using(var network=new HttpClient(new CheckHandler((_,_)=>throw new HttpRequestException("generated network failure"))))await RejectMessage(()=>ShareUpdates.CheckWithClient(network),"无法连接 GitHub 更新源");
            using(var cancelled=new HttpClient(new CheckHandler((_,token)=>Task.FromCanceled<HttpResponseMessage>(token)))){
                try{await ShareUpdates.CheckWithClient(cancelled,new CancellationToken(true));throw new Exception("caller cancellation swallowed");}
                catch(OperationCanceledException){checks++;}
            }
            Check(ShareUpdates.Parse(Feed()).Version=="0.8.0","Windows version overrides another platform's train version");
            Check(ShareUpdates.Compare(release)>0&&ShareUpdates.Compare(release with {Build=ShareUpdates.Build,Version=ShareUpdates.Version})==0,"new and same builds distinguished");
            Check(ShareUpdates.Compare(release with {Build=14,Version="0.7.1"})<0,"older public feed never downgrades preview");
            Check(ShareUpdates.Compare(release with {Build=17,Version="0.8.0"})<0,"inconsistent older build not offered");
            foreach(string url in new[]{"http://github.com/x","https://github.com:444/x","https://github.com@evil.example/x","https://evil.example/x","file:///tmp/x"})Check(!ShareUpdates.AllowedDownload(url,true),"unsafe update URL rejected");
            Check(ShareUpdates.AllowedDownload("https://release-assets.githubusercontent.com/x",true)&&!ShareUpdates.AllowedDownload("https://release-assets.githubusercontent.com/x",false),"GitHub asset redirect allowed only after a trusted origin");
            string packages=Path.Combine(root,"packages");string package=await ShareUpdates.ReceivePackage(release,new MemoryStream(bytes),packages,null,CancellationToken.None);
            Check(File.ReadAllBytes(package).SequenceEqual(bytes),"verified bytes committed");
            Check(await ShareUpdates.Download(release,packages,null,CancellationToken.None)==package,"verified cached package survives application restart and avoids another download");
            await Reject(()=>ShareUpdates.ReceivePackage(release with {Sha256=new string('0',64)},new MemoryStream(bytes),packages,null,CancellationToken.None),"wrong digest accepted");
            await Reject(()=>ShareUpdates.ReceivePackage(release with {Bytes=bytes.Length+1},new MemoryStream(bytes),packages,null,CancellationToken.None),"truncated package accepted");
            await Reject(()=>ShareUpdates.ReceivePackage(release with {Bytes=bytes.Length-1},new MemoryStream(bytes),packages,null,CancellationToken.None),"oversize package accepted");
            Check(File.ReadAllBytes(package).SequenceEqual(bytes)&&!Directory.EnumerateFiles(packages,"*.partial").Any(),"failure preserves prior package and clears partial data");
            int calls=0;var waiting=new TaskCompletionSource<UpdateRelease>(TaskCreationOptions.RunContinuationsAsynchronously);
            string prefs=Path.Combine(root,"prefs");
            using(var updates=new DesktopUpdates(prefs,_=>{calls++;return waiting.Task;})){
                Task first=updates.CheckAsync(),second=updates.CheckAsync(true);Check(ReferenceEquals(first,second)&&calls==1,"startup and manual checks share one request");
                waiting.SetResult(release);await first;Check(updates.Snapshot.Phase==UpdatePhase.Available&&updates.Snapshot.Notice,"new release produces startup notice");
                updates.Skip();Check(!updates.Snapshot.Notice,"skip dismisses notice");
            }
            using(var reopened=new DesktopUpdates(prefs,_=>Task.FromResult(release))){await reopened.CheckAsync();Check(!reopened.Snapshot.Notice,"skip survives application restart");await reopened.CheckAsync(true);Check(reopened.Snapshot.Notice,"manual check still reveals skipped release");reopened.SetAutomatic(false);}
            int automaticCalls=0;using(var disabled=new DesktopUpdates(prefs,_=>{automaticCalls++;return Task.FromResult(release);})){
                await disabled.CheckAsync();Check(!disabled.Automatic&&automaticCalls==0,"disabled automatic checks persist");await disabled.CheckAsync(true);Check(automaticCalls==1,"manual check works with automatic checks off");
            }
            using(var offline=new DesktopUpdates(Path.Combine(root,"offline"),_=>throw new IOException("offline"))){await offline.CheckAsync(true);Check(offline.Snapshot.Phase==UpdatePhase.Failed&&offline.Snapshot.Message=="offline","network failure never claims latest");}
            using(var ahead=new DesktopUpdates(Path.Combine(root,"ahead"),_=>Task.FromResult(release with {Version="0.7.1",Build=14}))){await ahead.CheckAsync();Check(ahead.Snapshot.Phase==UpdatePhase.Ahead&&!ahead.Snapshot.Notice,"unpublished preview labeled clearly");}
            string failureRoot=Path.Combine(root,"install-failure");Directory.CreateDirectory(failureRoot);File.WriteAllText(Path.Combine(failureRoot,"last-install.json"),"{\"ok\":false,\"error\":\"generated failure\"}");
            using(var failure=new DesktopUpdates(failureRoot,_=>throw new Exception("automatic check should not hide install error"))){await failure.CheckAsync();Check(failure.Snapshot.Phase==UpdatePhase.Failed&&failure.Snapshot.Notice&&failure.Snapshot.Message.Contains("generated failure"),"failed installation reported on next launch instead of being hidden by check");}
            int attempts=0;using(var retry=new DesktopUpdates(Path.Combine(root,"retry"),_=>Task.FromResult(release),(r,p,progress,token)=>{attempts++;return attempts==1?Task.FromException<string>(new IOException("bad package")):Task.FromResult(package);})){
                await retry.CheckAsync(true);await retry.DownloadAsync();Check(retry.Snapshot.Phase==UpdatePhase.Failed&&retry.Snapshot.Release==release,"download failure can be retried");
                await retry.DownloadAsync();Check(retry.Snapshot.Phase==UpdatePhase.Ready&&retry.Snapshot.Package==package,"successful retry enables update and restart");
            }
            Console.WriteLine("PASS Windows updates: "+checks+" checks; no live package installed.");
        }finally{Directory.Delete(root,true);}
    }
}
