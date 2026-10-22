package com.floatclip.share;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Authenticated transport adapter calls this core only after pairing. No platform APIs or timers. */
public final class ClipboardSync {
    public static final int HELLO=9, CLIP=8, ACK=12, PING=10, PONG=11;
    public static final int MAX_BYTES=64000, MAX_PEERS=8;
    public static final long MAX_CLOCK=9007199254740990L, RETRY_TTL=300000L;
    private static final UUID ZERO=new UUID(0,0);
    public interface Storage { byte[] load() throws Exception; void save(byte[] bytes) throws Exception; }
    public interface Events {
        void send(String peer,ShareWire.Frame frame);
        /** Must be idempotent by entry.id, and throw if durable history storage fails. */
        void history(Entry entry) throws Exception;
        /** Called only for a new winning event; platform adapter must suppress its own clipboard write. */
        void apply(Entry entry);
    }
    public static final class Device {
        public final UUID id; public final String name,platform;
        public Device(UUID id,String name,String platform) {
            if(id==null || id.equals(ZERO) || name==null || name.trim().isEmpty() || name.length()>80 ||
                !Arrays.asList("android","windows","ipad","web").contains(platform)) throw new IllegalArgumentException("Invalid device");
            this.id=id; this.name=name; this.platform=platform;
        }
    }
    public static final class Entry {
        public final UUID id; public final Device source; public final long clock,createdAt; public final String text;
        public Entry(UUID id,Device source,long clock,long createdAt,String text) {
            if(id==null || id.equals(ZERO) || source==null || clock<1 || clock>MAX_CLOCK || createdAt<0 || text==null || text.trim().isEmpty() || text.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)
                throw new IllegalArgumentException("Invalid clipboard event");
            this.id=id; this.source=source; this.clock=clock; this.createdAt=createdAt; this.text=text;
        }
    }
    private static final class Peer {
        Device device; UUID epoch; boolean receive,send,enabled=true; long seen; UUID seenId; byte[] seenHash;
        Entry pending; UUID pendingEpoch; long queuedAt;
        Peer copy() { Peer p=new Peer();p.device=device;p.epoch=epoch;p.receive=receive;p.send=send;p.enabled=enabled;p.seen=seen;p.seenId=seenId;p.seenHash=seenHash;p.pending=pending;p.pendingEpoch=pendingEpoch;p.queuedAt=queuedAt;return p; }
    }
    private static final class State {
        boolean enabled,send=true,receive=true,apply=true; UUID epoch=UUID.randomUUID(); long clock,headClock; UUID headOrigin=ZERO;
        LinkedHashMap<String,Peer> peers=new LinkedHashMap<>();
        State copy(){State s=new State();s.enabled=enabled;s.send=send;s.receive=receive;s.apply=apply;s.epoch=epoch;s.clock=clock;s.headClock=headClock;s.headOrigin=headOrigin;for(Map.Entry<String,Peer> p:peers.entrySet())s.peers.put(p.getKey(),p.getValue().copy());return s;}
    }
    private final Device local; private final Storage storage; private final Events events;
    private final Set<String> connected=new HashSet<>(),greeted=new HashSet<>(); private State state; private long applicationGrant;
    public ClipboardSync(Device local,Storage storage,Events events) throws Exception {
        this.local=local;this.storage=storage;this.events=events;
        byte[] bytes=storage.load();state=bytes==null?new State():decode(bytes);
    }
    public synchronized boolean enabled(){return state.enabled;}
    public synchronized boolean sending(){return state.send;}
    public synchronized boolean receiving(){return state.receive;}
    public synchronized boolean applying(){return state.apply;}
    public static final class PeerView {public final String key;public final Device device;public final boolean enabled;PeerView(String key,Peer p){this.key=key;device=p.device;enabled=p.enabled;}}
    public synchronized List<PeerView> peerViews(){List<PeerView> views=new ArrayList<>();for(Map.Entry<String,Peer> p:state.peers.entrySet())if(p.getValue().device!=null)views.add(new PeerView(p.getKey(),p.getValue()));return views;}
    public synchronized Set<String> peerKeys(){return new HashSet<>(state.peers.keySet());}
    public synchronized int remembered(){return state.peers.size();}
    public synchronized long applicationGrant(){return applicationGrant;}
    public synchronized boolean mayApply(Entry entry,long grant){return grant==applicationGrant&&mayApply(entry);}
    public synchronized boolean mayApply(Entry entry){return state.enabled&&state.receive&&state.apply&&entry.clock==state.headClock&&entry.source.id.equals(state.headOrigin)&&state.peers.values().stream().anyMatch(p->p.enabled&&p.send&&p.device!=null&&p.device.id.equals(entry.source.id)&&entry.id.equals(p.seenId));}
    /** A transport worker must recheck after queueing: pause and newer copies invalidate queued data. */
    public synchronized boolean maySend(String key,ShareWire.Frame frame) throws IOException {
        Peer p=state.peers.get(key);if(!state.enabled||!connected.contains(key)||p==null)return false;
        if(frame.type==HELLO)return Arrays.equals(frame.data,hello(key).data);
        if(!p.enabled)return false;
        if(frame.type==CLIP)return state.send&&p.receive&&p.pending!=null&&p.pending.id.equals(frame.id)&&Arrays.equals(frame.data,payload(p.pending,p.pendingEpoch));
        return frame.type==ACK&&state.receive&&p.send&&frame.id.equals(p.seenId);
    }
    public synchronized void invalidateApplications(){applicationGrant++;}
    /** Durable receive consent can change even if an async adapter coalesced off/on into true. */
    public synchronized void renewReceiveGrant() throws Exception {
        State next=state.copy();next.epoch=UUID.randomUUID();save(next);applicationGrant++;
        for(String key:new ArrayList<>(connected))emitHello(key);
    }
    public synchronized void configure(boolean enabled,boolean send,boolean receive,boolean apply) throws Exception {
        State next=state.copy();
        // A new receive grant cannot revive packets queued under the old grant.
        if(next.enabled!=enabled || next.receive!=receive)next.epoch=UUID.randomUUID();
        next.enabled=enabled;next.send=send;next.receive=receive;next.apply=apply;
        if(!enabled || !send)for(Peer p:next.peers.values())p.pending=null;
        boolean revokeApply=state.enabled!=enabled||state.receive!=receive||state.apply!=apply;
        save(next);if(revokeApply)applicationGrant++;
        if(!enabled){connected.clear();greeted.clear();}
        else for(String key:new ArrayList<>(connected))emitHello(key);
    }
    public synchronized void peerEnabled(String key,boolean enabled) throws Exception {
        Peer current=state.peers.get(key);if(current==null)throw new IOException("Unknown trusted peer");
        State next=state.copy();Peer p=next.peers.get(key);if(p.enabled!=enabled)next.epoch=UUID.randomUUID();p.enabled=enabled;if(!enabled)p.pending=null;save(next);if(current.enabled!=enabled)applicationGrant++;
        for(String peer:new ArrayList<>(connected))emitHello(peer);
    }
    public synchronized void forget(String key) throws Exception {
        State next=state.copy();next.peers.remove(key);save(next);applicationGrant++;connected.remove(key);greeted.remove(key);
    }
    /** key is a credential/certificate hash supplied by an authenticated transport, never a device name. */
    public synchronized void connected(String key) throws Exception {
        checkKey(key);if(!state.enabled)return;
        if(!state.peers.containsKey(key)) {
            if(state.peers.size()>=MAX_PEERS)throw new IOException("Too many paired devices");
            State next=state.copy();next.peers.put(key,new Peer());save(next);
        }
        connected.add(key);greeted.remove(key);emitHello(key);
    }
    public synchronized void disconnected(String key){connected.remove(key);greeted.remove(key);}
    private ShareWire.Frame hello(String key) throws IOException {
        Peer p=state.peers.get(key);boolean on=state.enabled&&p.enabled;
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeByte(1);out.writeByte(on?(state.send?1:0)|(state.receive?2:0):0);uuid(out,state.epoch);device(out,local);
        return new ShareWire.Frame(HELLO,ZERO,bytes.toByteArray());
    }
    private void emitHello(String key)throws IOException{events.send(key,hello(key));}
    public synchronized Entry copied(String text,long now,boolean excluded) throws Exception {
        if(!state.enabled)return null;
        applicationGrant++;
        if(!state.send||excluded) {
            State next=state.copy();boolean changed=false;
            for(Peer peer:next.peers.values())if(peer.pending!=null){peer.pending=null;changed=true;}
            if(changed)save(next);
            return null;
        }
        if(state.clock>=MAX_CLOCK)throw new IOException("Clipboard clock exhausted");
        Entry entry=new Entry(UUID.randomUUID(),local,state.clock+1,now,text);State next=state.copy();
        next.clock=entry.clock;next.headClock=entry.clock;next.headOrigin=local.id;
        for(Peer p:next.peers.values())if(p.enabled&&p.device!=null&&p.receive&&p.epoch!=null){p.pending=entry;p.pendingEpoch=p.epoch;p.queuedAt=now;}
        save(next);
        for(String key:new ArrayList<>(connected))flush(key,now);
        return entry;
    }
    public synchronized void accept(String key,ShareWire.Frame frame,long now) throws Exception {
        if(!state.enabled||!connected.contains(key))return;
        Peer current=state.peers.get(key);
        if(frame.type==HELLO){
            if(!frame.id.equals(ZERO)||frame.data.length>512)throw new IOException("Invalid sync greeting");
            DataInputStream in=new DataInputStream(new ByteArrayInputStream(frame.data));if(in.readUnsignedByte()!=1)throw new IOException("Unsupported sync version");
            int flags=in.readUnsignedByte();if((flags&~3)!=0)throw new IOException("Invalid sync policy");
            UUID epoch=uuid(in);Device remote=device(in);end(in);
            if(remote.id.equals(local.id)||epoch.equals(ZERO)||(current.device!=null&&!current.device.id.equals(remote.id)))throw new IOException("Remembered device identity changed");
            State next=state.copy();Peer p=next.peers.get(key);p.device=remote;p.epoch=epoch;p.send=(flags&1)!=0;p.receive=(flags&2)!=0;
            if(p.pending!=null&&(!p.receive||!epoch.equals(p.pendingEpoch)))p.pending=null;
            save(next);greeted.add(key);flush(key,now);return;
        }
        if(!greeted.contains(key))throw new IOException("Sync greeting required");
        if(frame.type==ACK){
            if(frame.data.length!=32)throw new IOException("Invalid sync acknowledgment");
            if(current.pending!=null&&current.pending.id.equals(frame.id)){
                byte[] expected=ShareWire.digest(payload(current.pending,current.pendingEpoch));
                if(!MessageDigest.isEqual(expected,frame.data))throw new IOException("Sync acknowledgment mismatch");
                State next=state.copy();next.peers.get(key).pending=null;save(next);
            }
            return;
        }
        if(frame.type!=CLIP)throw new IOException("Unexpected sync packet");
        Decoded decoded=readEntry(frame,current.device);Entry entry=decoded.entry;
        if(!current.enabled||!state.receive||!current.send||!decoded.epoch.equals(state.epoch))return;
        byte[] hash=ShareWire.digest(frame.data);
        if(entry.clock<current.seen){events.send(key,new ShareWire.Frame(ACK,entry.id,hash));return;}
        if(entry.clock==current.seen){
            if(!entry.id.equals(current.seenId)||!MessageDigest.isEqual(hash,current.seenHash))throw new IOException("Conflicting clipboard sequence");
            events.send(key,new ShareWire.Frame(ACK,entry.id,hash));return;
        }
        State next=state.copy();Peer p=next.peers.get(key);p.seen=entry.clock;p.seenId=entry.id;p.seenHash=hash;
        next.clock=Math.max(next.clock,entry.clock);
        boolean wins=compare(entry.clock,entry.source.id,next.headClock,next.headOrigin)>0;
        if(wins){next.headClock=entry.clock;next.headOrigin=entry.source.id;}
        events.history(entry);save(next); // ACK means history + cursor are durable, not merely received bytes.
        if(wins&&state.apply)events.apply(entry);
        events.send(key,new ShareWire.Frame(ACK,entry.id,hash));
    }
    /** Durable outbox drives transport recovery even when no UI or socket is open. */
    public synchronized Set<String> pendingPeers(long now) throws Exception {
        Set<String> result=new LinkedHashSet<>();State next=null;
        for(Map.Entry<String,Peer> row:state.peers.entrySet()) {
            Peer p=row.getValue();if(p.pending==null)continue;
            long age=now-p.queuedAt;
            if(!state.enabled||!state.send||!p.enabled||!p.receive||age<0||age>RETRY_TTL||!Objects.equals(p.epoch,p.pendingEpoch)) {
                if(next==null)next=state.copy();next.peers.get(row.getKey()).pending=null;
            } else result.add(row.getKey());
        }
        if(next!=null)save(next);
        return result;
    }
    public synchronized void retry(String key,long now) throws Exception {flush(key,now);}
    private void flush(String key,long now) throws Exception {
        Peer p=state.peers.get(key);
        if(!state.enabled||!state.send||!connected.contains(key)||!greeted.contains(key)||p==null||!p.enabled||!p.receive||p.pending==null)return;
        long age=now-p.queuedAt;
        if(age<0||age>RETRY_TTL||!p.epoch.equals(p.pendingEpoch)) {State next=state.copy();next.peers.get(key).pending=null;save(next);return;}
        events.send(key,new ShareWire.Frame(CLIP,p.pending.id,payload(p.pending,p.pendingEpoch)));
    }
    public static int compare(long ac,UUID ai,long bc,UUID bi){int c=Long.compare(ac,bc);return c!=0?c:ai.toString().compareTo(bi.toString());}
    public static byte[] payload(Entry entry,UUID recipientEpoch) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeByte(1);uuid(out,entry.source.id);out.writeLong(entry.clock);out.writeLong(entry.createdAt);uuid(out,recipientEpoch);
        byte[] text=entry.text.getBytes(StandardCharsets.UTF_8);out.writeInt(text.length);out.write(text);return bytes.toByteArray();
    }
    private static final class Decoded { Entry entry;UUID epoch;Decoded(Entry e,UUID x){entry=e;epoch=x;} }
    private static Decoded readEntry(ShareWire.Frame frame,Device source) throws IOException {
        try{
            DataInputStream in=new DataInputStream(new ByteArrayInputStream(frame.data));if(in.readUnsignedByte()!=1||!uuid(in).equals(source.id))throw new IOException("Clipboard origin mismatch");
            long clock=in.readLong(),created=in.readLong();UUID epoch=uuid(in);int n=in.readInt();if(n<1||n>MAX_BYTES||in.available()!=n)throw new IOException("Clipboard size invalid");
            byte[] raw=new byte[n];in.readFully(raw);return new Decoded(new Entry(frame.id,source,clock,created,ShareWire.text(raw)),epoch);
        }catch(IllegalArgumentException e){throw new IOException("Invalid clipboard event",e);}
    }
    private void save(State next) throws Exception {storage.save(encode(next));state=next;}
    private static void checkKey(String key) throws IOException {if(key==null||!key.matches("[a-f0-9]{64}"))throw new IOException("Invalid trust identity");}
    private static void uuid(DataOutputStream out,UUID id)throws IOException{out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());}
    private static UUID uuid(DataInputStream in)throws IOException{return new UUID(in.readLong(),in.readLong());}
    private static void string(DataOutputStream out,String text)throws IOException{byte[] raw=text.getBytes(StandardCharsets.UTF_8);out.writeShort(raw.length);out.write(raw);}
    private static String string(DataInputStream in,int max)throws IOException{int n=in.readUnsignedShort();if(n>max)throw new IOException("String too large");byte[] b=new byte[n];in.readFully(b);return ShareWire.text(b);}
    private static void device(DataOutputStream out,Device d)throws IOException{uuid(out,d.id);string(out,d.name);string(out,d.platform);}
    private static Device device(DataInputStream in)throws IOException{try{return new Device(uuid(in),string(in,320),string(in,16));}catch(IllegalArgumentException e){throw new IOException("Invalid device",e);}}
    private static void end(DataInputStream in)throws IOException{if(in.available()!=0)throw new IOException("Trailing bytes");}
    private static byte[] encode(State s)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeInt(0x46535931);
        out.writeBoolean(s.enabled);out.writeBoolean(s.send);out.writeBoolean(s.receive);out.writeBoolean(s.apply);uuid(out,s.epoch);out.writeLong(s.clock);out.writeLong(s.headClock);uuid(out,s.headOrigin);out.writeByte(s.peers.size());
        for(Map.Entry<String,Peer> row:s.peers.entrySet()){
            string(out,row.getKey());Peer p=row.getValue();out.writeBoolean(p.enabled);out.writeBoolean(p.device!=null);
            if(p.device!=null){device(out,p.device);uuid(out,p.epoch);out.writeBoolean(p.send);out.writeBoolean(p.receive);}
            out.writeLong(p.seen);if(p.seen>0){uuid(out,p.seenId);out.write(p.seenHash);}
            out.writeBoolean(p.pending!=null);if(p.pending!=null){uuid(out,p.pending.id);out.writeLong(p.queuedAt);byte[] data=payload(p.pending,p.pendingEpoch);out.writeInt(data.length);out.write(data);}
        }
        return bytes.toByteArray();
    }
    private State decode(byte[] bytes)throws IOException{
        if(bytes.length>600000)throw new IOException("Sync state too large");DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readInt()!=0x46535931)throw new IOException("Sync state version");
        State s=new State();s.enabled=in.readBoolean();s.send=in.readBoolean();s.receive=in.readBoolean();s.apply=in.readBoolean();s.epoch=uuid(in);s.clock=in.readLong();s.headClock=in.readLong();s.headOrigin=uuid(in);
        if(s.clock<0||s.clock>MAX_CLOCK||s.headClock<0||s.headClock>s.clock||s.epoch.equals(ZERO))throw new IOException("Invalid sync clock");
        int n=in.readUnsignedByte();if(n>MAX_PEERS)throw new IOException("Too many peers");
        for(int i=0;i<n;i++){
            String key=string(in,64);checkKey(key);if(s.peers.containsKey(key))throw new IOException("Duplicate peer");Peer p=new Peer();p.enabled=in.readBoolean();
            if(in.readBoolean()){p.device=device(in);p.epoch=uuid(in);p.send=in.readBoolean();p.receive=in.readBoolean();if(p.epoch.equals(ZERO)||p.device.id.equals(local.id))throw new IOException("Invalid remembered identity");}
            p.seen=in.readLong();if(p.seen<0||p.seen>MAX_CLOCK)throw new IOException("Invalid received clock");if(p.seen>0){p.seenId=uuid(in);p.seenHash=new byte[32];in.readFully(p.seenHash);}
            if(in.readBoolean()){UUID id=uuid(in);p.queuedAt=in.readLong();int size=in.readInt();if(size<1||size>MAX_BYTES+64||size>in.available())throw new IOException("Invalid outbox");byte[] raw=new byte[size];in.readFully(raw);Decoded d=readEntry(new ShareWire.Frame(CLIP,id,raw),local);p.pending=d.entry;p.pendingEpoch=d.epoch;if(p.pending.clock>s.clock)throw new IOException("Outbox clock");}
            s.peers.put(key,p);
        }
        end(in);return s;
    }
}
