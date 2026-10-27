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
    static async Task Main(string[] args)
    {
        string root=Path.Combine(Path.GetTempPath(),"FloatClip-UpdateChecks-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try{
            if(OperatingSystem.IsWindows()){
                using var handler=UpdateHttpClient.CreateHandler();
                Check(handler is WinHttpHandler native&&!native.AutomaticRedirection&&native.WindowsProxyUsePolicy==WindowsProxyUsePolicy.UseWinInetProxy,"Windows updates use the native system proxy without automatic redirects");
            }
            byte[] bytes=Encoding.UTF8.GetBytes("generated update package");string hash=Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
            var release=new UpdateRelease(ShareUpdates.Build+1,"0.8.0","generated notes","https://github.com/SteveBrilien/FloatClip/releases/download/test/FloatClip.zip",hash,bytes.Length);
            byte[] Feed(string platformVersion="0.8.0")=>JsonSerializer.SerializeToUtf8Bytes(new{schema=1,channel="share-preview",version="0.7.1",notes="notes",windows=new{version=platformVersion,build=ShareUpdates.Build+1,url=release.Url,sha256=hash,bytes=bytes.Length,status="available"}});
            using(var validHttp=new HttpClient(new CheckHandler((request,_)=>Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK){RequestMessage=request,Content=new ByteArrayContent(Feed())})))){
                Check((await ShareUpdates.CheckWithClient(validHttp,verify:data=>data)).Version=="0.8.0","update response parsed through actual request path");
            }
            using(var unavailable=new HttpClient(new CheckHandler((request,_)=>Task.FromResult(new HttpResponseMessage(HttpStatusCode.ServiceUnavailable){RequestMessage=request}))))await RejectMessage(()=>ShareUpdates.CheckWithClient(unavailable),"HTTP 503");
            using(var detailed=new HttpClient(new CheckHandler((request,_)=>Task.FromResult(new HttpResponseMessage(request.RequestUri!.Host=="clip.wmy-cloud.cn"?HttpStatusCode.BadGateway:HttpStatusCode.ServiceUnavailable){RequestMessage=request})))){
                try{await ShareUpdates.CheckWithClient(detailed);throw new Exception("source errors discarded");}
                catch(IOException error){Check(error.Message.Contains("公网更新源返回 HTTP 502")&&error.Message.Contains("GitHub 备用更新源返回 HTTP 503"),"failure reports retain both source diagnoses");}
            }
            using(var timeout=new HttpClient(new CheckHandler((_,_)=>throw new TaskCanceledException("generated timeout"))))await RejectMessage(()=>ShareUpdates.CheckWithClient(timeout),"超时");
            using(var network=new HttpClient(new CheckHandler((_,_)=>throw new HttpRequestException("generated network failure"))))await RejectMessage(()=>ShareUpdates.CheckWithClient(network),"无法连接GitHub 备用更新源");
            using(var cancelled=new HttpClient(new CheckHandler((_,token)=>Task.FromCanceled<HttpResponseMessage>(token)))){
                try{await ShareUpdates.CheckWithClient(cancelled,new CancellationToken(true));throw new Exception("caller cancellation swallowed");}
                catch(OperationCanceledException){checks++;}
            }
            int originCalls=0;
            using(var fallback=new HttpClient(new CheckHandler((request,_)=>{originCalls++;return Task.FromResult(new HttpResponseMessage(originCalls==1?HttpStatusCode.ServiceUnavailable:HttpStatusCode.OK){RequestMessage=request,Content=new ByteArrayContent(Feed())});})))
                Check((await ShareUpdates.CheckWithClient(fallback,verify:data=>data)).Version=="0.8.0"&&originCalls==2,"unavailable primary falls back to GitHub");
            int signatureCalls=0;
            using(var tampered=new HttpClient(new CheckHandler((request,_)=>{signatureCalls++;return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK){RequestMessage=request,Content=new ByteArrayContent(Encoding.UTF8.GetBytes("{}"))});}))) {
                await Reject(()=>ShareUpdates.CheckWithClient(tampered),"missing signature rejected");Check(signatureCalls==1,"signature failure does not trigger fallback");
            }
            byte[] liveFixture=File.ReadAllBytes("updates/native-updates.json");
            using var fixtureEnvelope=JsonDocument.Parse(liveFixture);string fixtureToken=fixtureEnvelope.RootElement.GetProperty("nativeUpdates").GetProperty("share-preview").GetString()!;string rawPayload=fixtureToken.Split('.')[1].Replace('-','+').Replace('_','/');using var fixturePayload=JsonDocument.Parse(Convert.FromBase64String(rawPayload.PadRight((rawPayload.Length+3)/4*4,'=')));long fixtureTime=fixturePayload.RootElement.GetProperty("issuedAt").GetInt64()+1;
            var expectedFixture=ShareUpdates.Parse(System.Text.Encoding.UTF8.GetBytes(fixturePayload.RootElement.GetProperty("release").GetRawText()));
            var verifiedFixture=ShareUpdates.Parse(UpdateManifestVerifier.Verify(liveFixture,now:fixtureTime));
            Check(verifiedFixture==expectedFixture,"Python RS256 publication verifies with the compiled Windows public key and preserves the signed platform release");
            await Reject(()=>Task.FromResult(UpdateManifestVerifier.Verify(liveFixture,now:DateTimeOffset.UtcNow.AddYears(2).ToUnixTimeSeconds())),"expired signature metadata rejected");
            using(var rsa=RSA.Create(2048)) {
                string B64(byte[] data)=>Convert.ToBase64String(data).TrimEnd('=').Replace('+','-').Replace('/','_');
                byte[] Signed(string algorithm="RS256",string channel="share-preview",long? issued=null) {
                    var header=B64(JsonSerializer.SerializeToUtf8Bytes(new{alg=algorithm,kid=UpdateTrust.KeyId,typ="JOSE"}));long time=issued??DateTimeOffset.UtcNow.ToUnixTimeSeconds();
                    var payload=B64(JsonSerializer.SerializeToUtf8Bytes(new{schema=1,issuedAt=time,expiresAt=time+3600,release=JsonSerializer.Deserialize<JsonElement>(Feed())}));
                    string input=header+"."+payload;string token=input+"."+B64(rsa.SignData(Encoding.ASCII.GetBytes(input),HashAlgorithmName.SHA256,RSASignaturePadding.Pkcs1));
                    return JsonSerializer.SerializeToUtf8Bytes(new{nativeUpdates=new System.Collections.Generic.Dictionary<string,string>{{channel,token}}});
                }
                string publicKey=rsa.ExportSubjectPublicKeyInfoPem();
                Check(ShareUpdates.Parse(UpdateManifestVerifier.Verify(Signed(),publicKey:publicKey)).Version=="0.8.0","valid signed test release accepted");
                await Reject(()=>Task.FromResult(UpdateManifestVerifier.Verify(Signed("none"),publicKey:publicKey)),"algorithm confusion rejected");
                await Reject(()=>Task.FromResult(UpdateManifestVerifier.Verify(Signed(issued:DateTimeOffset.UtcNow.AddHours(1).ToUnixTimeSeconds()),publicKey:publicKey)),"future issue date rejected");
                await Reject(()=>Task.FromResult(UpdateManifestVerifier.Verify(Signed(),publicKey:UpdateTrust.PublicKey)),"wrong signer rejected");
                await Reject(()=>Task.FromResult(UpdateManifestVerifier.Verify(Signed(channel:"share-dev"),channel:"share-dev",publicKey:publicKey)),"channel mismatch rejected");
            }
            if(args.Contains("--verify-file")) {int position=Array.IndexOf(args,"--verify-file");Check(ShareUpdates.Parse(UpdateManifestVerifier.Verify(File.ReadAllBytes(args[position+1]))).Build>0,"prepared public metadata verifies before upload");}
            if(args.Contains("--live")) {var verified=await ShareUpdates.Check();Console.WriteLine("LIVE verified publication: "+verified.Version+" / "+verified.Build);}
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
            using(var sameVersionBuild=new DesktopUpdates(Path.Combine(root,"same-version-build"),_=>Task.FromResult(release with {Version=ShareUpdates.Version,Build=ShareUpdates.Build+1}))){await sameVersionBuild.CheckAsync(true);Check(sameVersionBuild.Snapshot.Phase==UpdatePhase.Available&&sameVersionBuild.Snapshot.Message.Contains("build "+(ShareUpdates.Build+1)),"same version higher build is offered and displayed");}
            using(var current=new DesktopUpdates(Path.Combine(root,"current-build"),_=>Task.FromResult(release with {Version=ShareUpdates.Version,Build=ShareUpdates.Build}))){await current.CheckAsync(true);Check(current.Snapshot.Phase==UpdatePhase.Current&&current.Snapshot.Message.Contains("build "+ShareUpdates.Build),"current status includes local build");}
            using(var offline=new DesktopUpdates(Path.Combine(root,"offline"),_=>throw new IOException("offline"))){await offline.CheckAsync(true);Check(offline.Snapshot.Phase==UpdatePhase.Failed&&offline.Snapshot.Message=="offline","network failure never claims latest");}
            using(var ahead=new DesktopUpdates(Path.Combine(root,"ahead"),_=>Task.FromResult(release with {Version="0.7.1",Build=14}))){await ahead.CheckAsync();Check(ahead.Snapshot.Phase==UpdatePhase.Ahead&&!ahead.Snapshot.Notice,"unpublished preview labeled clearly");Check(ahead.Snapshot.Message.Contains("build 14"),"ahead status includes published build");}
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
