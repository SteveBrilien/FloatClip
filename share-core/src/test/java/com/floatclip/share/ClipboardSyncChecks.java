package com.floatclip.share;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Behavioral checks with dropped frames, process restart and injected durable-storage failures. */
public final class ClipboardSyncChecks {
    static int checks;static long now=1000000;
    static void yes(boolean b){if(!b)throw new AssertionError("Sync check "+checks);checks++;}
    interface Throwing {void run()throws Exception;}
    static void rejects(Throwing action)throws Exception{try{action.run();throw new AssertionError("Expected rejection");}catch(IOException|IllegalArgumentException expected){checks++;}}
    static final String AKEY="a".repeat(64),BKEY="b".repeat(64);
    static final ClipboardSync.Device A=new ClipboardSync.Device(UUID.fromString("00000000-0000-0000-0000-000000000001"),"手机","android");
    static final ClipboardSync.Device B=new ClipboardSync.Device(UUID.fromString("00000000-0000-0000-0000-000000000002"),"电脑","windows");
    static final class Memory implements ClipboardSync.Storage {
        byte[] data;boolean fail;public byte[] load(){return data;}
        public void save(byte[] value)throws IOException{if(fail)throw new IOException("Disk full");data=value.clone();}
    }
    static final class Packet {final boolean fromA;final ShareWire.Frame frame;Packet(boolean a,ShareWire.Frame f){fromA=a;frame=f;}}
    static final class Pair {
        final Memory sa=new Memory(),sb=new Memory();final ArrayDeque<Packet> wire=new ArrayDeque<>();
        final Map<UUID,ClipboardSync.Entry> ah=new HashMap<>(),bh=new HashMap<>();final List<String> aa=new ArrayList<>(),ba=new ArrayList<>();
        ClipboardSync a,b;boolean failHistory;
        Pair()throws Exception{restartA();restartB();}
        void restartA()throws Exception{a=new ClipboardSync(A,sa,events(true));}
        void restartB()throws Exception{b=new ClipboardSync(B,sb,events(false));}
        ClipboardSync.Events events(boolean left){return new ClipboardSync.Events(){
            public void send(String peer,ShareWire.Frame f){wire.add(new Packet(left,f));}
            public void history(ClipboardSync.Entry e)throws Exception{if(!left&&failHistory)throw new IOException("Vault unavailable");(left?ah:bh).putIfAbsent(e.id,e);}
            public void apply(ClipboardSync.Entry e){(left?aa:ba).add(e.text);}
        };}
        void enable()throws Exception{a.configure(true,true,true,true);b.configure(true,true,true,true);connect();}
        void connect()throws Exception{a.connected(BKEY);b.connected(AKEY);pump();}
        void disconnect(){a.disconnected(BKEY);b.disconnected(AKEY);wire.clear();}
        void pump()throws Exception{int n=0;while(!wire.isEmpty()){if(n++>200)throw new AssertionError("Clipboard feedback loop");Packet p=wire.remove();(p.fromA?b:a).accept(p.fromA?AKEY:BKEY,p.frame,now);}}
        Packet clip(){return wire.stream().filter(p->p.frame.type==ClipboardSync.CLIP).findFirst().orElseThrow(AssertionError::new);}
    }
    public static void main(String[] args)throws Exception{
        Pair p=new Pair();yes(!p.a.enabled());yes(p.a.copied("private",now,false)==null);yes(p.wire.isEmpty());
        p.enable();yes(p.a.remembered()==1&&p.b.remembered()==1);
        p.a.copied("中文 📋\nvalue",now,false);Packet original=p.clip();p.pump();yes(p.bh.size()==1&&p.ba.size()==1);yes(p.ba.get(0).equals("中文 📋\nvalue"));
        p.b.accept(AKEY,original.frame,now);p.pump();yes(p.bh.size()==1&&p.ba.size()==1);
        p.a.retry(BKEY,now);yes(p.wire.isEmpty());

        Pair concurrent=new Pair();concurrent.enable();concurrent.a.copied("A",now,false);concurrent.b.copied("B",now,false);concurrent.pump();
        yes(concurrent.aa.equals(List.of("B")));yes(concurrent.ba.isEmpty());yes(concurrent.ah.size()==1&&concurrent.bh.size()==1);
        concurrent.a.copied("later A",now,false);concurrent.pump();yes(concurrent.ba.equals(List.of("later A")));

        Pair restart=new Pair();restart.enable();restart.disconnect();restart.a.copied("offline old",now,false);restart.a.copied("offline latest",++now,false);restart.restartA();restart.connect();
        yes(restart.bh.size()==1&&restart.ba.equals(List.of("offline latest")));
        restart.disconnect();restart.restartB();restart.connect();restart.a.retry(BKEY,now);yes(restart.wire.isEmpty());

        Pair paused=new Pair();paused.enable();paused.a.copied("in flight",now,false);Packet stale=paused.clip();paused.wire.clear();paused.b.configure(false,true,true,true);
        paused.b.accept(AKEY,stale.frame,now);yes(paused.bh.isEmpty());paused.disconnect();paused.b.configure(true,true,true,true);paused.connect();
        paused.b.accept(AKEY,stale.frame,now);yes(paused.bh.isEmpty());yes(paused.a.copied("sensitive",now,true)==null);yes(paused.wire.isEmpty());
        paused.a.configure(false,true,true,true);yes(paused.a.copied("copied while paused",now,false)==null);paused.a.configure(true,true,true,true);paused.connect();yes(paused.bh.isEmpty());

        Pair perPeer=new Pair();perPeer.enable();perPeer.a.copied("old permission",now,false);Packet beforeOff=perPeer.clip();perPeer.wire.clear();
        perPeer.b.peerEnabled(AKEY,false);perPeer.pump();perPeer.b.peerEnabled(AKEY,true);perPeer.pump();perPeer.b.accept(AKEY,beforeOff.frame,now);yes(perPeer.bh.isEmpty());

        Pair failure=new Pair();failure.enable();failure.a.copied("durable",now,false);Packet retry=failure.clip();failure.wire.clear();failure.failHistory=true;
        rejects(()->failure.b.accept(AKEY,retry.frame,now));yes(failure.wire.isEmpty()&&failure.bh.isEmpty());failure.failHistory=false;failure.sb.fail=true;
        rejects(()->failure.b.accept(AKEY,retry.frame,now));yes(failure.bh.size()==1&&failure.wire.isEmpty()&&failure.ba.isEmpty());failure.sb.fail=false;
        failure.b.accept(AKEY,retry.frame,now);failure.pump();yes(failure.bh.size()==1&&failure.ba.size()==1);

        byte[] changed=original.frame.data.clone();changed[changed.length-1]^=1;
        rejects(()->p.b.accept(AKEY,new ShareWire.Frame(ClipboardSync.CLIP,original.frame.id,changed),now));
        byte[] forged=original.frame.data.clone();forged[16]^=1;
        rejects(()->p.b.accept(AKEY,new ShareWire.Frame(ClipboardSync.CLIP,original.frame.id,forged),now));
        Pair expired=new Pair();expired.enable();expired.disconnect();expired.a.copied("expired",now,false);now+=ClipboardSync.RETRY_TTL+1;expired.connect();yes(expired.bh.isEmpty());
        Pair receiveOff=new Pair();receiveOff.enable();receiveOff.b.configure(true,true,false,true);receiveOff.pump();receiveOff.a.copied("not permitted",now,false);receiveOff.pump();yes(receiveOff.bh.isEmpty());
        receiveOff.b.configure(true,true,true,false);receiveOff.pump();receiveOff.a.copied("history only",now,false);receiveOff.pump();yes(receiveOff.bh.size()==1&&receiveOff.ba.isEmpty());
        Pair sendOff=new Pair();sendOff.enable();sendOff.a.configure(true,false,true,true);sendOff.pump();yes(sendOff.a.copied("do not send",now,false)==null);yes(sendOff.bh.isEmpty());
        Pair queued=new Pair();queued.enable();queued.a.copied("queued old",now,false);Packet oldQueued=queued.clip();yes(queued.a.maySend(BKEY,oldQueued.frame));queued.a.copied("queued latest",now,false);yes(!queued.a.maySend(BKEY,oldQueued.frame));queued.wire.remove(oldQueued);queued.pump();
        ClipboardSync.Entry applied=queued.bh.values().stream().filter(e->e.text.equals("queued latest")).findFirst().orElseThrow();yes(queued.b.mayApply(applied));long applyGrant=queued.b.applicationGrant();queued.b.configure(true,true,true,false);queued.b.configure(true,true,true,true);yes(!queued.b.mayApply(applied,applyGrant));queued.b.peerEnabled(AKEY,false);yes(!queued.b.mayApply(applied));queued.b.peerEnabled(AKEY,true);queued.b.forget(AKEY);yes(!queued.b.mayApply(applied));
        Pair privateCopy=new Pair();privateCopy.enable();privateCopy.a.copied("remote before local",now,false);privateCopy.pump();ClipboardSync.Entry beforePrivate=privateCopy.bh.values().iterator().next();privateCopy.b.configure(true,false,true,true);privateCopy.pump();long privateGrant=privateCopy.b.applicationGrant();for(int i=0;i<100;i++)privateCopy.b.copied("new private local copy "+i,now,false);yes(!privateCopy.b.mayApply(beforePrivate,privateGrant));yes(privateCopy.ah.isEmpty());privateCopy.a.copied("remote after private copies",now,false);privateCopy.pump();yes(privateCopy.ba.equals(List.of("remote before local","remote after private copies")));
        Pair barrier=new Pair();barrier.enable();barrier.a.copied("old receive consent",now,false);Packet oldGrant=barrier.clip();barrier.wire.clear();barrier.b.renewReceiveGrant();barrier.pump();barrier.b.accept(AKEY,oldGrant.frame,now);yes(barrier.bh.isEmpty());barrier.a.copied("new receive consent",now,false);barrier.pump();yes(barrier.ba.equals(List.of("new receive consent")));
        Pair independent=new Pair();independent.enable();independent.a.copied("outgoing must survive receive-only renewal",now,false);independent.a.renewReceiveGrant();independent.pump();yes(independent.bh.size()==1);
        Memory damaged=new Memory();damaged.data=new byte[]{1,2,3};rejects(()->new ClipboardSync(A,damaged,p.events(true)));

        Path vectors=Paths.get(args[0]);Files.createDirectories(vectors);
        UUID epoch=UUID.fromString("10213243-5465-7687-98a9-bacbdcedfe0f");
        ClipboardSync.Entry sample=new ClipboardSync.Entry(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),A,1234,1700000000000L,"FloatClip 同步 📋\n  exact text\r\n");
        Files.write(vectors.resolve("sync-clip-payload.bin"),ClipboardSync.payload(sample,epoch));
        ByteArrayOutputStream framed=new ByteArrayOutputStream();ShareWire.write(framed,new ShareWire.Frame(ClipboardSync.CLIP,sample.id,ClipboardSync.payload(sample,epoch)));Files.write(vectors.resolve("sync-clip.bin"),framed.toByteArray());
        System.out.println("PASS clipboard sync Java: "+checks+" behavioral checks; pause, replay, conflict, restart, policy and durable ACK");
    }
}
