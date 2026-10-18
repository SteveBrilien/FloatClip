package com.floatclip.share;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.floatclip.share.ShareWire.*;

/** One authenticated, foreground session. UI and platform file pickers stay outside. */
public final class ShareSession implements AutoCloseable {
    public interface Events {
        void text(UUID id,String text,boolean outgoing);
        void offered(Offer offer);
        void progress(UUID id,String name,long done,long total,boolean outgoing);
        void file(Offer offer,File local,boolean outgoing);
        void notice(String message);
        void closed();
    }
    private final Socket socket; private final File cache; private final Events events;
    private final Object writing=new Object(), state=new Object();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final ExecutorService fileWorker=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    private final LinkedHashMap<UUID,byte[]> pending=new LinkedHashMap<>();
    private final LinkedHashMap<UUID,byte[]> received=new LinkedHashMap<>();
    private final Map<UUID,String> texts=new HashMap<>();
    private Job outgoing; private Offer incoming; private File part;
    private FileOutputStream sink; private MessageDigest fileHash;
    private long count, lastProgress, nextFileByte; private int sequence;
    private static final class Job {
        final File source; final String name; Offer offer; boolean accepted, endSent;
        Job(File source,String name) { this.source=source; this.name=name; }
    }
    public ShareSession(Socket socket,File cache,Events events) throws IOException {
        this.socket=socket; this.cache=cache; this.events=events;
        if(!cache.isDirectory() && !cache.mkdirs()) throw new IOException("File cache unavailable");
        socket.setSoTimeout(180000); socket.setTcpNoDelay(true);
    }
    public void start() {
        timer.schedule(this::close,10,TimeUnit.MINUTES);
        Thread reader=new Thread(this::readLoop,"floatclip-share-reader"); reader.start();
    }
    private void write(int type,UUID id,byte[] data) throws IOException {
        synchronized(writing) {
            if(closed.get()) throw new IOException("Session closed");
            ShareWire.write(socket.getOutputStream(),new Frame(type,id,data));
        }
    }
    public void sendText(String value) throws IOException {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        if(bytes.length==0 || bytes.length>MAX_TEXT) throw new IOException("Text limit is 64 KiB");
        UUID id=UUID.randomUUID();
        synchronized(state) {
            if(pending.size()>=16) throw new IOException("Wait for outstanding messages");
            pending.put(id,digest(bytes)); texts.put(id,value);
        }
        try { write(TEXT,id,bytes); }
        catch(IOException e) { close(); throw e; }
    }
    public void sendFile(File source,String name) throws IOException {
        safeName(name);
        if(!source.isFile() || source.length()>MAX_FILE) throw new IOException("Choose a regular file up to 64 MiB");
        final Job job=new Job(source,name);
        synchronized(state) {
            if(outgoing!=null || incoming!=null) throw new IOException("One file at a time");
            outgoing=job;
        }
        fileWorker.execute(() -> {
            try {
                MessageDigest hash=sha(); long size=0;
                try(InputStream input=new FileInputStream(source)) {
                    byte[] buffer=new byte[CHUNK_SIZE]; int n;
                    while((n=input.read(buffer))!=-1) {
                        if(closed.get()) return;
                        size+=n; if(size>MAX_FILE) throw new IOException("File limit exceeded"); hash.update(buffer,0,n);
                    }
                }
                Offer offer=new Offer(UUID.randomUUID(),name,size,hash.digest());
                synchronized(state) { if(outgoing!=job || closed.get()) return; job.offer=offer; }
                events.progress(offer.id,name,0,size,true);
                events.notice("等待对方接收文件"); write(OFFER,offer.id,offer.encode());
            } catch(Exception e) { synchronized(state) { if(outgoing==job) outgoing=null; } events.notice("文件准备失败，请重新选择"); }
        });
    }
    public void accept(UUID id) throws IOException {
        synchronized(state) {
            if(incoming==null || !incoming.id.equals(id) || sink!=null) throw new IOException("Offer expired");
            long cached=0; File[] files=cache.listFiles(); if(files!=null) for(File file:files) cached+=file.length();
            if(cached+incoming.size>128L*1024*1024) throw new IOException("Cache full");
            if(cache.getUsableSpace()<incoming.size+1024*1024) throw new IOException("Insufficient free space");
            part=new File(cache,id+".part");
            if(!part.createNewFile()) throw new IOException("Temporary file already exists");
            sink=new FileOutputStream(part); fileHash=sha(); count=0; sequence=0;
        }
        write(ACCEPT,id,new byte[0]);
    }
    public void reject(UUID id) throws IOException {
        synchronized(state) {
            if(incoming==null || !incoming.id.equals(id) || sink!=null) throw new IOException("Offer expired");
            incoming=null;
        }
        write(REJECT,id,new byte[0]); events.notice("已拒绝文件");
    }
    private void sendBody(Job job) {
        try {
            Offer offer=job.offer; long done=0,last=0; int index=0; MessageDigest hash=sha();
            try(InputStream input=new FileInputStream(job.source)) {
                byte[] buffer=new byte[CHUNK_SIZE]; int n;
                while((n=input.read(buffer))!=-1) {
                    synchronized(state) { if(outgoing!=job || closed.get()) return; }
                    done+=n; if(done>offer.size) throw new IOException("Source file changed");
                    hash.update(buffer,0,n); pace(n);
                    byte[] data=new byte[n+4]; ByteBuffer.wrap(data).putInt(index++).put(buffer,0,n);
                    write(CHUNK,offer.id,data);
                    long now=System.nanoTime();
                    if(now-last>100000000L || done==offer.size) { last=now; events.progress(offer.id,offer.name,done,offer.size,true); }
                }
            }
            if(done!=offer.size || !MessageDigest.isEqual(hash.digest(),offer.hash)) throw new IOException("Source file changed");
            synchronized(state) { if(outgoing!=job) return; job.endSent=true; }
            write(END,offer.id,offer.hash);
        } catch(Exception e) { events.notice("文件传输中断，请重新发送"); close(); }
    }
    private void pace(int bytes) throws InterruptedException {
        long delay;
        synchronized(state) {
            long now=System.nanoTime(); nextFileByte=Math.max(now,nextFileByte)+bytes*1000000000L/(2*1024*1024);
            delay=nextFileByte-now;
        }
        if(delay>0) TimeUnit.NANOSECONDS.sleep(delay);
    }
    private void readLoop() {
        try {
            Frame f;
            while(!closed.get() && (f=ShareWire.read(socket.getInputStream()))!=null) handle(f);
        } catch(Exception e) { if(!closed.get()) events.notice("连接已结束，请重新连接"); }
        finally { close(); }
    }
    private void handle(Frame f) throws Exception {
        if(f.type==TEXT) {
            String value=ShareWire.text(f.data); byte[] hash=digest(f.data); boolean fresh;
            synchronized(state) {
                byte[] prior=received.get(f.id); fresh=prior==null;
                if(prior!=null && !MessageDigest.isEqual(prior,hash)) throw new IOException("Reused event identity");
                received.put(f.id,hash); if(received.size()>128) received.remove(received.keySet().iterator().next());
            }
            write(ACK,f.id,hash); if(fresh) events.text(f.id,value,false);
        } else if(f.type==ACK) {
            String value; Job completed=null;
            synchronized(state) {
                byte[] expected=pending.remove(f.id); value=texts.remove(f.id);
                if(expected!=null) { if(!MessageDigest.isEqual(expected,f.data)) throw new IOException("ACK mismatch"); }
                else if(outgoing!=null && outgoing.offer!=null && outgoing.offer.id.equals(f.id)) {
                    if(!outgoing.endSent) throw new IOException("Early file ACK");
                    if(!MessageDigest.isEqual(outgoing.offer.hash,f.data)) throw new IOException("File ACK mismatch");
                    completed=outgoing; outgoing=null;
                } else throw new IOException("Unexpected ACK");
            }
            if(value!=null) events.text(f.id,value,true);
            if(completed!=null) events.file(completed.offer,completed.source,true);
        } else if(f.type==OFFER) {
            Offer offer=Offer.decode(f); boolean busy;
            synchronized(state) { busy=incoming!=null || outgoing!=null; if(!busy) incoming=offer; }
            if(busy) write(REJECT,f.id,new byte[0]); else events.offered(offer);
        } else if(f.type==ACCEPT || f.type==REJECT) {
            Job job;
            synchronized(state) {
                job=outgoing;
                if(job==null || job.offer==null || !job.offer.id.equals(f.id) || job.accepted) throw new IOException("Unexpected decision");
                if(f.type==REJECT) outgoing=null; else job.accepted=true;
            }
            if(f.type==ACCEPT) fileWorker.execute(() -> sendBody(job)); else events.notice("对方未接收文件");
        } else if(f.type==CHUNK) {
            pace(f.data.length-4);
            synchronized(state) {
                if(incoming==null || !incoming.id.equals(f.id) || sink==null) throw new IOException("Unaccepted file body");
                ByteBuffer chunk=ByteBuffer.wrap(f.data);
                if(chunk.getInt()!=sequence++ || count+f.data.length-4>incoming.size) throw new IOException("Invalid file sequence");
                sink.write(f.data,4,f.data.length-4); fileHash.update(f.data,4,f.data.length-4); count+=f.data.length-4;
                long now=System.nanoTime();
                if(now-lastProgress>100000000L || count==incoming.size) {
                    lastProgress=now; events.progress(incoming.id,incoming.name,count,incoming.size,false);
                }
            }
        } else if(f.type==END) {
            Offer completed; File target;
            synchronized(state) {
                if(incoming==null || !incoming.id.equals(f.id) || sink==null || count!=incoming.size ||
                        !MessageDigest.isEqual(f.data,incoming.hash) || !MessageDigest.isEqual(fileHash.digest(),incoming.hash))
                    throw new IOException("Incomplete file");
                sink.getFD().sync(); sink.close(); sink=null;
                completed=incoming; target=new File(cache,completed.id+".ready");
                if(target.exists() || !part.renameTo(target)) throw new IOException("File commit failed");
                part=null; incoming=null;
            }
            write(ACK,f.id,completed.hash); events.file(completed,target,false);
        }
    }
    @Override public void close() {
        if(!closed.compareAndSet(false,true)) return;
        try { socket.close(); } catch(IOException ignored) { }
        fileWorker.shutdownNow(); timer.shutdownNow();
        synchronized(state) {
            try { if(sink!=null) sink.close(); } catch(IOException ignored) { }
            if(part!=null) part.delete(); sink=null; part=null; incoming=null; outgoing=null;
            pending.clear(); texts.clear();
        }
        events.closed();
    }
}
