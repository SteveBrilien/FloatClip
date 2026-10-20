using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text.Json;

namespace FloatClip.Share;

/** A durable, latest-value clipboard outbox. The caller supplies authenticated peers and OS adapters. */
public sealed class ClipboardSyncEngine
{
    public sealed class Peer
    {
        public SyncDevice? Device{get;set;}
        public Guid Epoch{get;set;}
        public bool Enabled{get;set;}=true;
        public bool Send{get;set;}
        public bool Receive{get;set;}
        public long Seen{get;set;}
        public Guid SeenId{get;set;}
        public byte[]? SeenHash{get;set;}
        public SyncEntry? Pending{get;set;}
        public Guid PendingEpoch{get;set;}
        public long QueuedAt{get;set;}
        public Peer Copy()=>(Peer)MemberwiseClone();
    }
    public sealed class State
    {
        public int Version{get;set;}=1;
        public bool Enabled{get;set;}
        public bool Send{get;set;}=true;
        public bool Receive{get;set;}=true;
        public bool Apply{get;set;}=true;
        public Guid Epoch{get;set;}=Guid.NewGuid();
        public long Clock{get;set;}
        public long HeadClock{get;set;}
        public Guid HeadOrigin{get;set;}
        public Dictionary<string,Peer> Peers{get;set;}=new();
        public State Copy(){var copy=(State)MemberwiseClone();copy.Peers=Peers.ToDictionary(p=>p.Key,p=>p.Value.Copy());return copy;}
    }
    readonly object gate=new();
    readonly SyncDevice local;
    readonly Action<byte[]> persist;
    readonly Action<string,Frame> send;
    readonly Action<SyncEntry> history,apply;
    readonly HashSet<string> connected=new(),greeted=new();
    State state;long applicationGrant;
    public ClipboardSyncEngine(SyncDevice local,Func<byte[]?> load,Action<byte[]> persist,Action<string,Frame> send,Action<SyncEntry> history,Action<SyncEntry> apply)
    {
        SyncProtocol.ValidateDevice(local);this.local=local;this.persist=persist;this.send=send;this.history=history;this.apply=apply;
        byte[]? bytes=load();if(bytes?.Length>900000)throw new IOException("同步状态过大");
        state=bytes==null?new State():JsonSerializer.Deserialize<State>(bytes)??throw new IOException("同步状态损坏");
        ValidateState();
    }
    void ValidateState()
    {
        if(state.Version!=1||state.Epoch==Guid.Empty||state.Clock<0||state.Clock>SyncProtocol.MaximumClock||state.HeadClock<0||state.HeadClock>state.Clock||state.Peers.Count>8)throw new IOException("同步状态无效");
        foreach(var row in state.Peers)
        {
            CheckKey(row.Key);var p=row.Value;
            if(p.Device!=null){SyncProtocol.ValidateDevice(p.Device);if(p.Epoch==Guid.Empty||p.Device.Id==local.Id)throw new IOException("设备授权状态无效");}
            if(p.Seen<0||p.Seen>SyncProtocol.MaximumClock||p.Seen>0&&(p.SeenId==Guid.Empty||p.SeenHash?.Length!=32))throw new IOException("接收游标无效");
            if(p.Pending!=null){SyncProtocol.ValidateEntry(p.Pending);if(p.Pending.Source.Id!=local.Id||p.Pending.Clock>state.Clock||p.PendingEpoch==Guid.Empty)throw new IOException("待发来源无效");}
        }
    }
    static void CheckKey(string key){if(!System.Text.RegularExpressions.Regex.IsMatch(key,"^[a-f0-9]{64}$"))throw new IOException("配对凭据标识无效");}
    void Save(State next){persist(JsonSerializer.SerializeToUtf8Bytes(next));state=next;}
    public State Snapshot(){lock(gate)return state.Copy();}
    public long ApplicationGrant{get{lock(gate)return applicationGrant;}}
    public bool MayApply(SyncEntry entry,long grant){lock(gate)return grant==applicationGrant&&MayApply(entry);}
    public bool MayApply(SyncEntry entry){lock(gate)return state.Enabled&&state.Receive&&state.Apply&&state.HeadClock==entry.Clock&&state.HeadOrigin==entry.Source.Id&&state.Peers.Values.Any(p=>p.Enabled&&p.Send&&p.Device?.Id==entry.Source.Id&&p.SeenId==entry.Id);}
    public bool MaySend(string key,Frame frame)
    {
        lock(gate)
        {
            if(!state.Enabled||!connected.Contains(key)||!state.Peers.TryGetValue(key,out var p))return false;
            if(frame.Type==SyncProtocol.Hello){bool on=state.Enabled&&p.Enabled;return frame.Data.AsSpan().SequenceEqual(SyncProtocol.EncodeHello(new(local,state.Epoch,on&&state.Send,on&&state.Receive)));}
            if(!p.Enabled)return false;
            if(frame.Type==SyncProtocol.Clip)return state.Send&&p.Receive&&p.Pending?.Id==frame.Id&&frame.Data.AsSpan().SequenceEqual(SyncProtocol.EncodeEntry(p.Pending,p.PendingEpoch));
            return frame.Type==SyncProtocol.Ack&&state.Receive&&p.Send&&p.SeenId==frame.Id;
        }
    }
    public void InvalidateApplications(){lock(gate)applicationGrant++;}
    public void RenewReceiveGrant(){lock(gate){var next=state.Copy();next.Epoch=Guid.NewGuid();Save(next);applicationGrant++;foreach(string key in connected.ToArray())Hello(key);}}
    public void Configure(bool enabled,bool sending,bool receiving,bool applying)
    {
        lock(gate)
        {
            var next=state.Copy();if(next.Enabled!=enabled||next.Receive!=receiving)next.Epoch=Guid.NewGuid();
            next.Enabled=enabled;next.Send=sending;next.Receive=receiving;next.Apply=applying;
            if(!enabled||!sending)foreach(var p in next.Peers.Values)p.Pending=null;
            bool revokeApply=state.Enabled!=enabled||state.Receive!=receiving||state.Apply!=applying;
            Save(next);if(revokeApply)applicationGrant++;
            if(!enabled){connected.Clear();greeted.Clear();}else foreach(string key in connected.ToArray())Hello(key);
        }
    }
    public void SetPeerEnabled(string key,bool enabled)
    {
        lock(gate)
        {
            if(!state.Peers.ContainsKey(key))throw new IOException("设备未授权");var next=state.Copy();var p=next.Peers[key];
            if(p.Enabled!=enabled)next.Epoch=Guid.NewGuid();p.Enabled=enabled;if(!enabled)p.Pending=null;bool changed=state.Peers[key].Enabled!=enabled;Save(next);if(changed)applicationGrant++;
            foreach(string peer in connected.ToArray())Hello(peer);
        }
    }
    public void Forget(string key){lock(gate){var next=state.Copy();next.Peers.Remove(key);Save(next);applicationGrant++;connected.Remove(key);greeted.Remove(key);}}
    /** One-time v1 migration: old Windows state used the remote TLS pin as peer key. */
    public void MigratePeerKey(string oldKey,string relationKey)
    {
        lock(gate)
        {
            CheckKey(oldKey);CheckKey(relationKey);if(oldKey==relationKey||!state.Peers.ContainsKey(oldKey))return;
            if(state.Peers.ContainsKey(relationKey))throw new IOException("同步状态包含重复的设备关系");
            var next=state.Copy();var peer=next.Peers[oldKey];next.Peers.Remove(oldKey);next.Peers[relationKey]=peer;Save(next);
            if(connected.Remove(oldKey))connected.Add(relationKey);if(greeted.Remove(oldKey))greeted.Add(relationKey);
        }
    }
    public void Connected(string key)
    {
        lock(gate)
        {
            CheckKey(key);if(!state.Enabled)return;
            if(!state.Peers.ContainsKey(key)){if(state.Peers.Count>=8)throw new IOException("最多 8 台已授权设备");var next=state.Copy();next.Peers[key]=new Peer();Save(next);}
            connected.Add(key);greeted.Remove(key);Hello(key);
        }
    }
    public void Disconnected(string key){lock(gate){connected.Remove(key);greeted.Remove(key);}}
    void Hello(string key)
    {
        bool on=state.Enabled&&state.Peers[key].Enabled;
        send(key,new(SyncProtocol.Hello,Guid.Empty,SyncProtocol.EncodeHello(new(local,state.Epoch,on&&state.Send,on&&state.Receive))));
    }
    public SyncEntry? Copied(string text,long now,bool excluded=false)
    {
        lock(gate)
        {
            if(!state.Enabled)return null;
            applicationGrant++;
            if(!state.Send||excluded)return null;
            var entry=new SyncEntry(Guid.NewGuid(),local,checked(state.Clock+1),now,text);SyncProtocol.ValidateEntry(entry);
            var next=state.Copy();next.Clock=entry.Clock;next.HeadClock=entry.Clock;next.HeadOrigin=local.Id;
            foreach(var p in next.Peers.Values)if(p.Enabled&&p.Device!=null&&p.Receive&&p.Epoch!=Guid.Empty){p.Pending=entry;p.PendingEpoch=p.Epoch;p.QueuedAt=now;}
            Save(next);foreach(string key in connected.ToArray())Flush(key,now);return entry;
        }
    }
    public void Accept(string key,Frame frame,long now)
    {
        lock(gate)
        {
            if(!state.Enabled||!connected.Contains(key))return;var current=state.Peers[key];
            if(frame.Type==SyncProtocol.Hello)
            {
                var hello=SyncProtocol.DecodeHello(frame);
                if(hello.Device.Id==local.Id||current.Device!=null&&current.Device.Id!=hello.Device.Id)throw new IOException("记住的设备身份已改变，需要重新授权");
                var next=state.Copy();var p=next.Peers[key];p.Device=hello.Device;p.Epoch=hello.Epoch;p.Send=hello.Send;p.Receive=hello.Receive;
                if(p.Pending!=null&&(!p.Receive||p.PendingEpoch!=p.Epoch))p.Pending=null;
                Save(next);greeted.Add(key);Flush(key,now);return;
            }
            if(!greeted.Contains(key))throw new IOException("同步握手尚未完成");
            if(frame.Type==SyncProtocol.Ack)
            {
                if(frame.Data.Length!=32)throw new IOException("同步确认无效");
                if(current.Pending?.Id==frame.Id)
                {
                    byte[] expected=Wire.Digest(SyncProtocol.EncodeEntry(current.Pending,current.PendingEpoch));
                    if(!CryptographicOperations.FixedTimeEquals(expected,frame.Data))throw new IOException("同步确认不匹配");
                    var next=state.Copy();next.Peers[key].Pending=null;Save(next);
                }
                return;
            }
            if(frame.Type!=SyncProtocol.Clip)throw new IOException("同步消息类型无效");
            var decoded=SyncProtocol.DecodeEntry(frame,current.Device!);var entry=decoded.Entry;
            if(!current.Enabled||!state.Receive||!current.Send||decoded.Epoch!=state.Epoch)return;
            byte[] hash=Wire.Digest(frame.Data);
            if(entry.Clock<current.Seen){send(key,new(SyncProtocol.Ack,entry.Id,hash));return;}
            if(entry.Clock==current.Seen)
            {
                if(entry.Id!=current.SeenId||!CryptographicOperations.FixedTimeEquals(hash,current.SeenHash!))throw new IOException("剪贴板序号冲突");
                send(key,new(SyncProtocol.Ack,entry.Id,hash));return;
            }
            var accepted=state.Copy();var peer=accepted.Peers[key];peer.Seen=entry.Clock;peer.SeenId=entry.Id;peer.SeenHash=hash;accepted.Clock=Math.Max(accepted.Clock,entry.Clock);
            bool wins=SyncProtocol.Compare(entry.Clock,entry.Source.Id,accepted.HeadClock,accepted.HeadOrigin)>0;
            if(wins){accepted.HeadClock=entry.Clock;accepted.HeadOrigin=entry.Source.Id;}
            history(entry);Save(accepted);if(wins&&state.Apply)apply(entry);
            send(key,new(SyncProtocol.Ack,entry.Id,hash));
        }
    }
    public void Retry(string key,long now){lock(gate)Flush(key,now);}
    void Flush(string key,long now)
    {
        if(!state.Enabled||!state.Send||!connected.Contains(key)||!greeted.Contains(key)||!state.Peers.TryGetValue(key,out var p)||!p.Enabled||!p.Receive||p.Pending==null)return;
        long age=now-p.QueuedAt;
        if(age<0||age>SyncProtocol.RetryTtl||p.Epoch!=p.PendingEpoch){var next=state.Copy();next.Peers[key].Pending=null;Save(next);return;}
        send(key,new(SyncProtocol.Clip,p.Pending.Id,SyncProtocol.EncodeEntry(p.Pending,p.PendingEpoch)));
    }
}
