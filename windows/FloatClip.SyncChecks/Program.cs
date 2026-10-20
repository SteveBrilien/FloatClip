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
        var applied=queued.HistoryB.Values.Single(e=>e.Text=="queued latest");Yes(queued.B.MayApply(applied));long applyGrant=queued.B.ApplicationGrant;queued.B.Configure(true,true,true,false);queued.B.Configure(true,true,true,true);Yes(!queued.B.MayApply(applied,applyGrant));queued.B.SetPeerEnabled(AKey,false);Yes(!queued.B.MayApply(applied));queued.B.SetPeerEnabled(AKey,true);queued.B.Forget(AKey);Yes(!queued.B.MayApply(applied));
        var privateCopy=new Pair();privateCopy.Enable();privateCopy.A.Copied("remote before local",p.Now);privateCopy.Pump();var beforePrivate=privateCopy.HistoryB.Values.Single();privateCopy.B.Configure(true,false,true,true);privateCopy.Pump();privateCopy.B.Copied("new private local copy",p.Now);Yes(!privateCopy.B.MayApply(beforePrivate));Yes(privateCopy.HistoryA.Count==0);
        SocketChecks.Run();
        Console.WriteLine("PASS clipboard sync C#: "+checks+" checks; Java-compatible payloads, replay, pause, restart, conflict, durable ACK and policy");
    }
}
