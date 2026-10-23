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
            using var a=new NativeClipboardSync(new ClipboardVault(Path.Combine(area,"a-vault")),(_,_)=>{},Path.Combine(area,"a"));
            var applied=new ConcurrentQueue<string>();using var b=new NativeClipboardSync(new ClipboardVault(Path.Combine(area,"b-vault")),(entry,_)=>applied.Enqueue(entry.Text),Path.Combine(area,"b"));
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
                a.SetPeerEnabled(key,false);Wait(()=>b.ConnectedCount==0,"native host pause disconnects only the selected peer");a.SetPeerEnabled(key,true);
                a.Copied("offline latest 👨‍👩‍👧‍👦");check(a.Snapshot().Peers[key].Pending!=null,"native host keeps offline text durable");
                sessions=Connect();Wait(()=>applied.Contains("offline latest 👨‍👩‍👧‍👦"),"native host reconnect delivers pending text with file adapter attached");
                Wait(()=>a.Snapshot().Peers[key].Pending==null,"native host reconnect ACK is committed");
            }finally{foreach(var session in sessions)session.Dispose();}
        }
    }
}
