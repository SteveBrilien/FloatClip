using System;
using System.IO;
using System.Collections.Generic;
using System.Linq;
using FloatClip.Share;

static class Program
{
    static int checks;
    static void Yes(bool value){if(!value)throw new Exception("Sync check "+checks);checks++;}
    static void Reject(Action action){try{action();throw new Exception("Expected rejection");}catch(IOException){checks++;}}
    static readonly Guid Epoch=Guid.Parse("10213243-5465-7687-98a9-bacbdcedfe0f");
    static readonly SyncDevice A=new(Guid.Parse("00000000-0000-0000-0000-000000000001"),"手机","android");
    static readonly SyncDevice B=new(Guid.Parse("00000000-0000-0000-0000-000000000002"),"电脑","windows");
    const string AKey="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",BKey="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    sealed class Pair
    {
        public ClipboardSyncEngine A=null!,B=null!;
        public byte[]? StateA,StateB;
        public Queue<(bool Left,Frame Frame)> Wire=new();
        public Dictionary<Guid,SyncEntry> HistoryA=new(),HistoryB=new();
        public List<string> AppliedA=new(),AppliedB=new();
        public bool FailHistory,FailSave;
        public long Now=1000000;
        public Pair(){RestartA();RestartB();}
        public void RestartA()=>A=new(Program.A,()=>StateA,b=>StateA=b,(k,f)=>Wire.Enqueue((true,f)),e=>HistoryA.TryAdd(e.Id,e),e=>AppliedA.Add(e.Text));
        public void RestartB()=>B=new(Program.B,()=>StateB,b=>{if(FailSave)throw new IOException("Disk full");StateB=b;},(k,f)=>Wire.Enqueue((false,f)),e=>{if(FailHistory)throw new IOException("Vault failed");HistoryB.TryAdd(e.Id,e);},e=>AppliedB.Add(e.Text));
        public void Enable(){A.Configure(true,true,true,true);B.Configure(true,true,true,true);Connect();}
        public void Connect(){A.Connected(BKey);B.Connected(AKey);Pump();}
        public void Disconnect(){A.Disconnected(BKey);B.Disconnected(AKey);Wire.Clear();}
        public Frame Clip()=>Wire.First(p=>p.Frame.Type==SyncProtocol.Clip).Frame;
        public void Pump(){int n=0;while(Wire.TryDequeue(out var p)){if(n++>200)throw new Exception("Feedback loop");(p.Left?B:A).Accept(p.Left?AKey:BKey,p.Frame,Now);}}
    }
    public static void Main(string[] args)
    {
        byte[] pairClient=Enumerable.Range(0,32).Select(i=>(byte)(0xA0+i)).ToArray(),pairServer=Enumerable.Range(0,32).Select(i=>(byte)(0x20+i)).ToArray();
        Yes(TransportPairing.EncodeClientHello(pairClient).AsSpan().SequenceEqual(File.ReadAllBytes(Path.Combine(args[0],"bt-pair-client-hello.bin"))));
        Yes(TransportPairing.Fingerprint(pairClient,pairServer)==File.ReadAllText(Path.Combine(args[0],"bt-pair-code.txt")));
        using(var pairListener=new System.Net.Sockets.TcpListener(System.Net.IPAddress.Loopback,0))
        {
            pairListener.Start();int pairPort=((System.Net.IPEndPoint)pairListener.LocalEndpoint).Port;
            byte[]? serverSaved=null,clientSaved=null;Exception? pairError=null;
            var pairTask=System.Threading.Tasks.Task.Run(()=>{
                try{using var accepted=pairListener.AcceptTcpClient();TransportPairing.Accept(accepted.GetStream(),_=>true,value=>serverSaved=(byte[])value.Clone());}
                catch(Exception e){pairError=e;}
            });
            using var pairSocket=new System.Net.Sockets.TcpClient();pairSocket.Connect(System.Net.IPAddress.Loopback,pairPort);
            byte[] paired=TransportPairing.Connect(pairSocket.GetStream(),_=>true,value=>clientSaved=(byte[])value.Clone(),()=>clientSaved=null);
            pairTask.Wait(3000);Yes(pairError==null&&serverSaved!=null&&clientSaved!=null&&paired.AsSpan().SequenceEqual(serverSaved)&&paired.AsSpan().SequenceEqual(clientSaved));
        }
        byte[] btToken=Enumerable.Range(1,32).Select(i=>(byte)i).ToArray(),btClientNonce=Enumerable.Range(33,32).Select(i=>(byte)i).ToArray(),btServerNonce=Enumerable.Range(65,32).Select(i=>(byte)i).ToArray();
        var btMaterial=TransportCrypto.Derive(btToken,btClientNonce,btServerNonce);
        Yes(TransportCrypto.EncodeClientHello(btToken,btClientNonce).AsSpan().SequenceEqual(File.ReadAllBytes(Path.Combine(args[0],"bt-client-hello.bin"))));
        Yes(btMaterial.ServerProof.AsSpan().SequenceEqual(File.ReadAllBytes(Path.Combine(args[0],"bt-server-proof.bin"))));
        Yes(btMaterial.ClientProof.AsSpan().SequenceEqual(File.ReadAllBytes(Path.Combine(args[0],"bt-client-proof.bin"))));
        var btServer=TransportCrypto.Records.Server(btMaterial);Yes(btServer.Open(File.ReadAllBytes(Path.Combine(args[0],"bt-c2s-record.bin"))).AsSpan().SequenceEqual(File.ReadAllBytes(Path.Combine(args[0],"bt-payload.bin"))));
        var btClient=TransportCrypto.Records.Client(btMaterial);Yes(btClient.Open(File.ReadAllBytes(Path.Combine(args[0],"bt-s2c-record.bin"))).AsSpan().SequenceEqual(File.ReadAllBytes(Path.Combine(args[0],"bt-reply.bin"))));
        Reject(()=>btServer.Open(File.ReadAllBytes(Path.Combine(args[0],"bt-c2s-record.bin"))));
        using(var listener=new System.Net.Sockets.TcpListener(System.Net.IPAddress.Loopback,0))
        {
            listener.Start();int port=((System.Net.IPEndPoint)listener.LocalEndpoint).Port;
            Frame? received=null;Exception? serverError=null;
            var serverTask=System.Threading.Tasks.Task.Run(()=>{
                try{using var accepted=listener.AcceptTcpClient();var secure=TransportCrypto.Accept(accepted.GetStream(),key=>key==Convert.ToHexString(TransportCrypto.Relation(btToken)).ToLowerInvariant()?btToken:null);received=Wire.Read(secure.Stream);}
                catch(Exception e){serverError=e;}
            });
            using var client=new System.Net.Sockets.TcpClient();client.Connect(System.Net.IPAddress.Loopback,port);var clientSecure=TransportCrypto.Connect(client.GetStream(),btToken);
            Wire.Write(clientSecure.Stream,new(SyncProtocol.Ping,Guid.Empty,Array.Empty<byte>()));serverTask.Wait(3000);
            Yes(serverError==null&&received?.Type==SyncProtocol.Ping);
        }
        var sample=new SyncEntry(Guid.Parse("00112233-4455-6677-8899-aabbccddeeff"),A,1234,1700000000000,"FloatClip 同步 📋\n  exact text\r\n");
        byte[] payload=SyncProtocol.EncodeEntry(sample,Epoch);
        Yes(payload.AsSpan().SequenceEqual(File.ReadAllBytes(Path.Combine(args[0],"sync-clip-payload.bin"))));
        using(var framed=new MemoryStream()){FloatClip.Share.Wire.Write(framed,new(SyncProtocol.Clip,sample.Id,payload));Yes(framed.ToArray().AsSpan().SequenceEqual(File.ReadAllBytes(Path.Combine(args[0],"sync-clip.bin"))));}
        var parsed=SyncProtocol.DecodeEntry(new(SyncProtocol.Clip,sample.Id,payload),A);Yes(parsed.Entry==sample&&parsed.Epoch==Epoch);
        Reject(()=>SyncProtocol.DecodeEntry(new(SyncProtocol.Clip,sample.Id,payload),B));
        var p=new Pair();Yes(!p.A.Snapshot().Enabled);Yes(p.A.Copied("private",p.Now)==null);p.Enable();p.A.Copied("hello 中文",p.Now);var duplicate=p.Clip();p.Pump();
        Yes(p.HistoryB.Count==1&&p.AppliedB.SequenceEqual(new[]{"hello 中文"}));p.B.Accept(AKey,duplicate,p.Now);p.Pump();Yes(p.HistoryB.Count==1&&p.AppliedB.Count==1);p.A.Retry(BKey,p.Now);Yes(p.Wire.Count==0);
        var concurrent=new Pair();concurrent.Enable();concurrent.A.Copied("A",p.Now);concurrent.B.Copied("B",p.Now);concurrent.Pump();Yes(concurrent.AppliedA.SequenceEqual(new[]{"B"}));Yes(concurrent.AppliedB.Count==0);
        var restored=new Pair();restored.Enable();restored.Disconnect();restored.A.Copied("old",p.Now);restored.A.Copied("latest",++restored.Now);restored.RestartA();restored.Connect();Yes(restored.HistoryB.Count==1&&restored.AppliedB.SequenceEqual(new[]{"latest"}));
        restored.Disconnect();restored.RestartB();restored.Connect();restored.A.Retry(BKey,p.Now);Yes(restored.Wire.Count==0);
        var paused=new Pair();paused.Enable();paused.A.Copied("in flight",p.Now);var stale=paused.Clip();paused.Wire.Clear();paused.B.Configure(false,true,true,true);paused.B.Accept(AKey,stale,p.Now);Yes(paused.HistoryB.Count==0);
        paused.Disconnect();paused.B.Configure(true,true,true,true);paused.Connect();paused.B.Accept(AKey,stale,p.Now);Yes(paused.HistoryB.Count==0);Yes(paused.A.Copied("sensitive",p.Now,true)==null);
        var peerPause=new Pair();peerPause.Enable();peerPause.A.Copied("before pause",p.Now);stale=peerPause.Clip();peerPause.Wire.Clear();peerPause.B.SetPeerEnabled(AKey,false);peerPause.Pump();peerPause.B.SetPeerEnabled(AKey,true);peerPause.Pump();peerPause.B.Accept(AKey,stale,p.Now);Yes(peerPause.HistoryB.Count==0);
        var failure=new Pair();failure.Enable();failure.A.Copied("durable",p.Now);var retry=failure.Clip();failure.Wire.Clear();failure.FailHistory=true;Reject(()=>failure.B.Accept(AKey,retry,p.Now));Yes(failure.Wire.Count==0&&failure.HistoryB.Count==0);
        failure.FailHistory=false;failure.FailSave=true;Reject(()=>failure.B.Accept(AKey,retry,p.Now));Yes(failure.Wire.Count==0&&failure.AppliedB.Count==0);failure.FailSave=false;failure.B.Accept(AKey,retry,p.Now);failure.Pump();Yes(failure.HistoryB.Count==1&&failure.AppliedB.Count==1);
        byte[] changed=(byte[])duplicate.Data.Clone();changed[^1]^=1;Reject(()=>p.B.Accept(AKey,duplicate with{Data=changed},p.Now));
        var expired=new Pair();expired.Enable();expired.Disconnect();expired.A.Copied("expired",p.Now);expired.Now+=SyncProtocol.RetryTtl+1;expired.Connect();Yes(expired.HistoryB.Count==0);
        var policy=new Pair();policy.Enable();policy.B.Configure(true,true,false,true);policy.Pump();policy.A.Copied("not allowed",p.Now);policy.Pump();Yes(policy.HistoryB.Count==0);
        policy.B.Configure(true,true,true,false);policy.Pump();policy.A.Copied("history only",p.Now);policy.Pump();Yes(policy.HistoryB.Count==1&&policy.AppliedB.Count==0);
        var queued=new Pair();queued.Enable();queued.A.Copied("queued old",p.Now);var oldQueued=queued.Clip();Yes(queued.A.MaySend(BKey,oldQueued));queued.A.Copied("queued latest",p.Now);Yes(!queued.A.MaySend(BKey,oldQueued));queued.Wire=new(queued.Wire.Where(f=>f.Frame!=oldQueued));queued.Pump();
        var excluded=new Pair();excluded.Enable();excluded.Disconnect();excluded.A.Copied("must not reappear after image copy",excluded.Now);Yes(excluded.A.PendingPeers(excluded.Now).Contains(BKey));excluded.A.Copied("",++excluded.Now,true);Yes(excluded.A.PendingPeers(excluded.Now).Length==0);excluded.Connect();Yes(excluded.HistoryB.Count==0);
        var applied=queued.HistoryB.Values.Single(e=>e.Text=="queued latest");Yes(queued.B.MayApply(applied));long applyGrant=queued.B.ApplicationGrant;queued.B.Configure(true,true,true,false);queued.B.Configure(true,true,true,true);Yes(!queued.B.MayApply(applied,applyGrant));queued.B.SetPeerEnabled(AKey,false);Yes(!queued.B.MayApply(applied));queued.B.SetPeerEnabled(AKey,true);queued.B.Forget(AKey);Yes(!queued.B.MayApply(applied));
        var privateCopy=new Pair();privateCopy.Enable();privateCopy.A.Copied("remote before local",p.Now);privateCopy.Pump();var beforePrivate=privateCopy.HistoryB.Values.Single();privateCopy.B.Configure(true,false,true,true);privateCopy.Pump();long privateGrant=privateCopy.B.ApplicationGrant;for(int i=0;i<100;i++)privateCopy.B.Copied("new private local copy "+i,p.Now);Yes(!privateCopy.B.MayApply(beforePrivate,privateGrant));Yes(privateCopy.HistoryA.Count==0);privateCopy.A.Copied("remote after private copies",p.Now);privateCopy.Pump();Yes(privateCopy.AppliedB.SequenceEqual(new[]{"remote before local","remote after private copies"}));
        var barrier=new Pair();barrier.Enable();barrier.A.Copied("old receive consent",p.Now);var oldGrant=barrier.Clip();barrier.Wire.Clear();barrier.B.RenewReceiveGrant();barrier.Pump();barrier.B.Accept(AKey,oldGrant,p.Now);Yes(barrier.HistoryB.Count==0);barrier.A.Copied("new receive consent",p.Now);barrier.Pump();Yes(barrier.AppliedB.SequenceEqual(new[]{"new receive consent"}));
        var independent=new Pair();independent.Enable();independent.A.Copied("outgoing must survive receive-only renewal",p.Now);independent.A.RenewReceiveGrant();independent.Pump();Yes(independent.HistoryB.Count==1);
        var keyMigration=new Pair();keyMigration.A.Configure(true,true,true,true);keyMigration.A.Connected(BKey);keyMigration.A.MigratePeerKey(BKey,AKey);Yes(keyMigration.A.Snapshot().Peers.ContainsKey(AKey)&&!keyMigration.A.Snapshot().Peers.ContainsKey(BKey));keyMigration.A.Disconnected(AKey);
        var recovery=new Pair();recovery.Enable();recovery.Disconnect();
        recovery.A.Copied("retry without opening UI",p.Now);
        Yes(recovery.A.PendingPeers(p.Now).SequenceEqual(new[]{BKey}));
        recovery.RestartA();Yes(recovery.A.PendingPeers(p.Now).Contains(BKey));
        recovery.Connect();Yes(recovery.A.PendingPeers(p.Now).Length==0);
        recovery.Disconnect();recovery.A.Copied("expires offline",p.Now);
        Yes(recovery.A.PendingPeers(p.Now+SyncProtocol.RetryTtl+1).Length==0);
        recovery.RestartA();recovery.Connect();Yes(recovery.HistoryB.Count==1);
        recovery.Disconnect();recovery.A.Copied("revoked",p.Now);recovery.A.Configure(true,false,true,true);
        Yes(recovery.A.PendingPeers(p.Now).Length==0);
        SocketChecks.Run();
        Console.WriteLine("PASS clipboard sync C#: "+checks+" checks; Java-compatible payloads, replay, pause, restart, conflict, durable ACK and policy");
    }
}
