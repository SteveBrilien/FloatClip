package com.floatclip.share;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static com.floatclip.share.ShareWire.*;

public final class CoreChecks {
    static int checks;
    static void yes(boolean value) { checks++; if(!value) throw new AssertionError("check "+checks); }
    static byte[] frame(Frame f) throws Exception { ByteArrayOutputStream out=new ByteArrayOutputStream(); write(out,f); return out.toByteArray(); }
    static void rejected(RunnableWithException action) throws Exception { try { action.run(); throw new AssertionError("Expected rejection"); } catch(IOException expected) { checks++; } }
    interface RunnableWithException { void run() throws Exception; }
    static class Events implements ShareSession.Events {
        ShareSession session; CountDownLatch text=new CountDownLatch(2),file=new CountDownLatch(2),refused=new CountDownLatch(1),closed=new CountDownLatch(1);
        boolean reject; volatile String receivedText; volatile File receivedFile;
        public void text(UUID id,String value,boolean outgoing) { if(!outgoing) receivedText=value; text.countDown(); }
        public void offered(Offer offer) { try { if(reject) session.reject(offer.id); else session.accept(offer.id); } catch(Exception e) { throw new RuntimeException(e); } }
        public void progress(UUID id,String name,long done,long total,boolean outgoing) { }
        public void file(Offer offer,File local,boolean outgoing) { if(!outgoing) receivedFile=local; file.countDown(); }
        public void notice(String message) { if(message.equals("对方未接收文件")) refused.countDown(); }
        public void closed() { closed.countDown(); }
    }
    public static void main(String[] args) throws Exception {
        Path vectors=Paths.get(args[0]); Files.createDirectories(vectors);
        UUID id=UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
        byte[] text="FloatClip Share\n 中文 📋\n\tcode  \r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] encoded=frame(new Frame(TEXT,id,text)); Files.write(vectors.resolve("share-text.bin"),encoded);
        for(int n=1;n<encoded.length;n++) { final byte[] cut=Arrays.copyOf(encoded,n); rejected(()->read(new ByteArrayInputStream(cut))); }
        Frame valid=read(new FilterInputStream(new ByteArrayInputStream(encoded)) { @Override public int read(byte[] b,int o,int n)throws IOException{return super.read(b,o,Math.min(n,1));} });
        yes(valid.id.equals(id) && Arrays.equals(valid.data,text));
        byte[] corrupt=encoded.clone(); corrupt[corrupt.length-1]^=1; rejected(()->read(new ByteArrayInputStream(corrupt)));
        byte[] oversize=encoded.clone(); Arrays.fill(oversize,22,26,(byte)0xff); rejected(()->read(new ByteArrayInputStream(oversize)));
        rejected(()->ShareWire.text(new byte[]{(byte)0xc3}));
        for(String name:new String[]{"../a","a/b","C:\\a","CON.txt","a.","a ","..","a\u0000b"}) rejected(()->safeName(name));
        Offer offer=new Offer(id,"生成文件.bin",text.length,digest(text));
        Files.write(vectors.resolve("share-offer.bin"),frame(new Frame(OFFER,id,offer.encode())));
        yes(Offer.decode(new Frame(OFFER,id,offer.encode())).name.equals(offer.name));
        rejected(()->new Offer(id,"file",MAX_FILE+1,new byte[32]));
        Path temp=Files.createTempDirectory("floatclip-core-check-");
        byte[] data=new byte[131073]; for(int i=0;i<data.length;i++) data[i]=(byte)(i*31+7);
        File source=Files.write(temp.resolve("source.bin"),data).toFile();
        try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            Socket a=new Socket("127.0.0.1",listener.getLocalPort()),b=listener.accept(); Events ea=new Events(),eb=new Events();
            ea.session=new ShareSession(a,temp.resolve("a").toFile(),ea); eb.session=new ShareSession(b,temp.resolve("b").toFile(),eb);
            ea.session.start(); eb.session.start(); ea.session.sendText(new String(text,java.nio.charset.StandardCharsets.UTF_8)); eb.session.sendText("  opposite\r\n");
            yes(ea.text.await(5,TimeUnit.SECONDS) && eb.text.await(5,TimeUnit.SECONDS)); yes(ea.receivedText.equals("  opposite\r\n"));
            ea.session.sendFile(source,"生成文件.bin"); long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while(eb.receivedFile==null && System.nanoTime()<limit) { if(eb.closed.await(20,TimeUnit.MILLISECONDS)) throw new AssertionError("closed"); }
            yes(eb.receivedFile!=null);
            yes(Arrays.equals(data,Files.readAllBytes(eb.receivedFile.toPath())));
            // Wait for final sender ACK before offering in the opposite direction.
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); while(ea.file.getCount()!=1 && System.nanoTime()<until) Thread.sleep(5); yes(ea.file.getCount()==1);
            eb.session.sendFile(source,"back.bin"); yes(ea.file.await(8,TimeUnit.SECONDS) && eb.file.await(8,TimeUnit.SECONDS));
            yes(Arrays.equals(data,Files.readAllBytes(ea.receivedFile.toPath())));
            eb.reject=true; ea.session.sendFile(source,"rejected.bin"); yes(ea.refused.await(5,TimeUnit.SECONDS)); ea.session.close(); eb.session.close();
        }
        // A peer that disconnects partway through a file must leave no completed or partial object.
        try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            Socket sender=new Socket("127.0.0.1",listener.getLocalPort()),receiver=listener.accept(); Events events=new Events();
            File dir=temp.resolve("partial").toFile(); events.session=new ShareSession(receiver,dir,events); events.session.start();
            Offer partial=new Offer(UUID.randomUUID(),"partial.bin",100,new byte[32]); write(sender.getOutputStream(),new Frame(OFFER,partial.id,partial.encode()));
            yes(read(sender.getInputStream()).type==ACCEPT); write(sender.getOutputStream(),new Frame(CHUNK,partial.id,new byte[8])); sender.close();
            yes(events.closed.await(5,TimeUnit.SECONDS)); yes(Objects.requireNonNull(dir.list()).length==0);
        }
        try(java.util.stream.Stream<Path> paths=Files.walk(temp)) { paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException ignored){}}); }
        System.out.println("PASS Share core: "+checks+" checks; frames, two-way text/files, rejection, partial cleanup");
    }
}
