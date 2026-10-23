package com.floatclip.share;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Loopback-only Java counterpart for the C# persistent-session integration check. */
public final class ClipboardSyncSocketPeer {
    static final String KEY="b".repeat(64);
    static volatile ShareSession session;
    static volatile ClipboardSync core;
    static byte[] saved;
    static final Set<UUID> history=ConcurrentHashMap.newKeySet();
    static final ExecutorService writer=Executors.newSingleThreadExecutor();
    static ClipboardSync create()throws Exception{return new ClipboardSync(new ClipboardSync.Device(UUID.fromString("00000000-0000-0000-0000-000000000001"),"Java phone","android"),new ClipboardSync.Storage(){public byte[] load(){return saved;}public void save(byte[] bytes){saved=bytes;}},new ClipboardSync.Events(){
        public void send(String key,ShareWire.Frame frame){writer.execute(()->{try{ShareSession active=session;if(active!=null&&core.maySend(KEY,frame))active.sendSync(frame);}catch(Exception e){throw new RuntimeException(e);}});}
        public void history(ClipboardSync.Entry entry){if(!entry.text.equals(history.isEmpty()?"C# first 中文":"offline latest"))throw new AssertionError("Wrong replay content");history.add(entry.id);}
        public void apply(ClipboardSync.Entry entry){writer.execute(()->{try{core.copied("Java reply "+history.size()+" 📋",System.currentTimeMillis(),false);}catch(Exception e){throw new RuntimeException(e);}});}
    });}
    public static void main(String[] args)throws Exception{
        int port=Integer.parseInt(args[0]);File cache=new File(args[1]);core=create();core.configure(true,true,true,true);
        try{
            for(int round=0;round<2;round++){
                if(round==1)core=create(); // Cursor and receive grant survive an application-core restart.
                Socket socket=new Socket();socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port),5000);CountDownLatch closed=new CountDownLatch(1);
                session=new ShareSession(socket,cache,new ShareSession.Events(){
                    public void text(UUID id,String text,boolean out){}
                    public void offered(ShareWire.Offer offer){throw new AssertionError("Unexpected file");}
                    public void progress(UUID id,String name,long done,long total,boolean out){}
                    public void file(ShareWire.Offer offer,File file,boolean out){}
                    public void notice(String message){}
                    public void sync(ShareWire.Frame frame)throws Exception{core.accept(KEY,frame,System.currentTimeMillis());}
                    public void closed(){core.disconnected(KEY);closed.countDown();}
                },true);
                core.connected(KEY);session.start();
                if(!closed.await(15,TimeUnit.SECONDS))throw new IOException("Socket check timed out");
            }
            if(history.size()!=2)throw new AssertionError("Unexpected history count "+history.size());
            System.out.println("PASS Java persistent socket peer: restart, two rounds, durable cursor and no echo");
        }finally{if(session!=null)session.close();writer.shutdownNow();}
    }
}
