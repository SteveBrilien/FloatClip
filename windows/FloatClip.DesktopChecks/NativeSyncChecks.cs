using System;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Collections.Concurrent;
using FloatClip.Desktop;
using FloatClip.Share;

static class NativeSyncChecks
{
    public static void Run(string root,Action<bool,string> check)
    {
        foreach(bool catalog in new[]{false,true}){
            string area=Path.Combine(root,catalog?"native-fcp4":"native-fcp3");
            var aVault=new ClipboardVault(Path.Combine(area,"a-vault"));var bVault=new ClipboardVault(Path.Combine(area,"b-vault"));
            using var a=new NativeClipboardSync(aVault,(_,_)=>{},Path.Combine(area,"a"));
            var applied=new ConcurrentQueue<string>();using var b=new NativeClipboardSync(bVault,(entry,_)=>applied.Enqueue(entry.Text),Path.Combine(area,"b"));
            byte[] token=System.Security.Cryptography.RandomNumberGenerator.GetBytes(32);string key=a.Pairing.PutBluetooth(token,1);b.Pairing.PutBluetooth(token,2);
            a.Configure(true,true,true,true,false);b.Configure(true,true,true,true,false);
            void Wait(Func<bool> condition,string message){var until=DateTime.UtcNow.AddSeconds(10);while(!condition()){if(DateTime.UtcNow>until)throw new Exception(message+" · "+a.Status+" / "+b.Status);System.Threading.Thread.Sleep(10);}check(true,message);}
            Session[] Connect(bool? upgrade=null){bool files=upgrade??catalog;var listener=new TcpListener(IPAddress.Loopback,0);listener.Start();var client=new TcpClient();client.Connect(IPAddress.Loopback,((IPEndPoint)listener.LocalEndpoint).Port);var server=listener.AcceptTcpClient();listener.Stop();var aa=new Session(client.GetStream(),client.Dispose,Path.Combine(area,"a/SyncCache"),true,files);var bb=new Session(server.GetStream(),server.Dispose,Path.Combine(area,"b/SyncCache"),true,files);var attach=typeof(NativeClipboardSync).GetMethod("AttachSession",BindingFlags.Instance|BindingFlags.NonPublic)!;attach.Invoke(a,new object[]{key,aa,files?"lan":"bluetooth"});attach.Invoke(b,new object[]{key,bb,files?"lan":"bluetooth"});return new[]{aa,bb};}
            var sessions=Connect();
            try{
                Wait(()=>a.Snapshot().Peers.TryGetValue(key,out var pa)&&pa.Receive&&b.Snapshot().Peers.TryGetValue(key,out var pb)&&pb.Receive,"native host completes authenticated-session HELLO (catalog="+catalog+")");
                a.Copied("native host 😀 中文");Wait(()=>applied.Contains("native host 😀 中文"),"native host text reaches receive/apply adapter");
                Wait(()=>a.Snapshot().Peers[key].Pending==null,"native host clears outbox only after ACK");
                if(!catalog){
                    string file=Path.Combine(area,"phone-screenshot.png");System.IO.File.WriteAllBytes(file,new byte[]{1,2,3,4});a.Files.Store.Add(file);
                    sessions=Connect(true);
                    Wait(()=>a.ConnectionTransport(key)=="lan"&&b.ConnectionTransport(key)=="lan","LAN replaces an existing Bluetooth text session without reauthorizing the relationship");
                    a.Files.Publish();Wait(()=>b.Files.Snapshot().Any(f=>f.Name=="phone-screenshot.png"&&f.Online),"file metadata appears after transport upgrade without downloading its content");
                    a.Copied("after LAN upgrade 😀");Wait(()=>applied.Contains("after LAN upgrade 😀")&&a.Snapshot().Peers[key].Pending==null,"text/ACK continue after file-capable LAN upgrade");
                }
                check(!a.ImageSharing&&!b.ImageSharing,"image sharing starts disabled independently of text");
                a.SetImageSharing(true);b.SetImageSharing(true);
                var pixels=new byte[]{0,0,255,255};var bitmap=System.Windows.Media.Imaging.BitmapSource.Create(1,1,96,96,System.Windows.Media.PixelFormats.Pbgra32,null,pixels,4);bitmap.Freeze();
                byte[] png=ClipboardImages.Encode(bitmap);
                var image=aVault.AddImage(png,1,1)["entries"]!.AsArray().OfType<System.Text.Json.Nodes.JsonObject>().Single(c=>ClipboardVault.Kind(c)=="image");
                a.CopiedImage(image);
                Wait(()=>bVault.Load()["entries"]!.AsArray().OfType<System.Text.Json.Nodes.JsonObject>().Any(c=>ClipboardVault.Kind(c)=="image"),"manual established session delivers clipboard image without automatic reconnect");
                check(a.ConnectedCount==1&&b.ConnectedCount==1,"copying an image preserves the existing connection and file channel");
                var received=bVault.Load()["entries"]!.AsArray().OfType<System.Text.Json.Nodes.JsonObject>().Single(c=>ClipboardVault.Kind(c)=="image");
                check(bVault.ReadImage(received).SequenceEqual(png),"received image bytes survive encrypted storage without alteration");
                a.Copied("text after image");Wait(()=>applied.Contains("text after image")&&a.Snapshot().Peers[key].Pending==null,"text resumes on the same session after image transfer");
                a.SetPeerEnabled(key,false);Wait(()=>b.ConnectedCount==0,"native host pause disconnects only the selected peer");a.SetPeerEnabled(key,true);
                a.Copied("offline latest 👨‍👩‍👧‍👦");check(a.Snapshot().Peers[key].Pending!=null,"native host keeps offline text durable");
                sessions=Connect();Wait(()=>applied.Contains("offline latest 👨‍👩‍👧‍👦"),"native host reconnect delivers pending text with file adapter attached");
                Wait(()=>a.Snapshot().Peers[key].Pending==null,"native host reconnect ACK is committed");
            }finally{foreach(var session in sessions)session.Dispose();}
        }
    }
}
