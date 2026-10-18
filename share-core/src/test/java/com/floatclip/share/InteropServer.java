package com.floatclip.share;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Loopback-only Java/C# protocol acceptance. This test does not claim TLS or Android UI acceptance. */
public final class InteropServer implements ShareSession.Events {
    ShareSession session; File source; byte[] hash; int checks; boolean pass; CountDownLatch done=new CountDownLatch(1);
    static final String A="FloatClip-Android\n中文 📋\n\tcode  \r\n",W="FloatClip-Windows\n中文 📋\n\tcode  \r\n";
    synchronized void check() { if(++checks==5) { pass=true; done.countDown(); } }
    public void text(UUID id,String value,boolean outgoing) { if(value.equals(outgoing?A:W)) check(); else done.countDown(); }
    public void offered(ShareWire.Offer offer) { try {
        if(offer.name.equals("FloatClip-reject.bin") && offer.size==0) { session.reject(offer.id); check(); }
        else if(offer.name.equals("FloatClip-Windows.bin") && offer.size==1048593 && MessageDigest.isEqual(offer.hash,hash)) session.accept(offer.id);
        else done.countDown();
    } catch(Exception e) { done.countDown(); } }
    public void progress(UUID id,String name,long count,long total,boolean outgoing) { }
    public void file(ShareWire.Offer offer,File file,boolean outgoing) { try {
        if(!MessageDigest.isEqual(ShareWire.digest(Files.readAllBytes(file.toPath())),hash)) { done.countDown(); return; }
        if(!outgoing) session.sendFile(source,"FloatClip-Android.bin"); check();
    } catch(Exception e) { done.countDown(); } }
    public void notice(String message) { }
    public void closed() { done.countDown(); }
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory("floatclip-java-interop-"); InteropServer events=new InteropServer();
        byte[] bytes=new byte[1048593]; for(int i=0;i<bytes.length;i++) bytes[i]=(byte)(i*31+7);
        events.hash=ShareWire.digest(bytes); events.source=Files.write(dir.resolve("source.bin"),bytes).toFile();
        try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            listener.setSoTimeout(60000); System.out.println(listener.getLocalPort()); System.out.flush();
            events.session=new ShareSession(listener.accept(),dir.resolve("cache").toFile(),events);
            events.session.start(); events.session.sendText(A);
            if(!events.done.await(60,TimeUnit.SECONDS) || !events.pass) throw new AssertionError("Interop failed");
            System.out.println("PASS JAVA/CS: two-way Unicode text; two-way files; refusal");
        } finally {
            if(events.session!=null) events.session.close();
            try(java.util.stream.Stream<Path> paths=Files.walk(dir)) { paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException ignored){}}); }
        }
    }
}
