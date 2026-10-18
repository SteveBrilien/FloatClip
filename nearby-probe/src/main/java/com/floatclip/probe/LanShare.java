package com.floatclip.probe;

import com.floatclip.share.ShareSession;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Android network adapter. Only a selected local interface is ever bound. */
final class LanShare implements AutoCloseable {
    interface Events {
        void invitation(String code);
        void connected(ShareSession session);
        void stopped(String message);
    }
    private final File cache; private final Events events; private final ShareSession.Events messages;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    private ServerSocket listener; private Socket socket; private ShareSession session;
    private ScheduledFuture<?> expiry; private int epoch;
    LanShare(File cache,Events events,ShareSession.Events messages) { this.cache=cache; this.events=events; this.messages=messages; }
    synchronized void listen(String ip) {
        stop(); final int generation=epoch;
        worker.execute(() -> serve(generation,ip));
    }
    private void serve(int generation,String ip) {
        try {
            InetAddress address=LanTls.checkedAddress(ip);
            SSLContext context=LanTls.context(); String pin=LanTls.pin();
            byte[] secret=new byte[32]; new SecureRandom().nextBytes(secret);
            StringBuilder hex=new StringBuilder(); for(byte b:secret) hex.append(String.format(Locale.ROOT,"%02x",b&255));
            SSLServerSocket server=(SSLServerSocket)context.getServerSocketFactory().createServerSocket();
            try {
                server.setReuseAddress(true); server.bind(new InetSocketAddress(address,46238),1);
                server.setEnabledProtocols(new String[]{"TLSv1.2"}); server.setSoTimeout(90000);
                synchronized(this) {
                    if(generation!=epoch) return;
                    listener=server; expiry=timer.schedule(() -> fail(generation,"邀请已过期"),90,TimeUnit.SECONDS);
                }
                events.invitation("floatclip://v1/"+ip+"/46238/"+pin+"/"+hex);
                SSLSocket peer=(SSLSocket)server.accept();
                synchronized(this) {
                    if(generation!=epoch) { peer.close(); return; }
                    socket=peer; listener=null; server.close(); expiry.cancel(false);
                    expiry=timer.schedule(() -> fail(generation,"连接超时"),15,TimeUnit.SECONDS);
                }
                peer.setSoTimeout(10000); peer.setTcpNoDelay(true); peer.startHandshake();
                DataInputStream input=new DataInputStream(peer.getInputStream());
                if(input.readInt()!=0x46435331) throw new IOException("Protocol mismatch");
                byte[] offered=new byte[32]; input.readFully(offered);
                if(!MessageDigest.isEqual(secret,offered)) throw new IOException("Invitation rejected");
                Arrays.fill(secret,(byte)0); peer.getOutputStream().write(1); peer.getOutputStream().flush();
                synchronized(this) {
                    if(generation!=epoch) return;
                    expiry.cancel(false); expiry=null;
                    session=new ShareSession(peer,cache,messages);
                    events.connected(session); session.start();
                }
            } finally { server.close(); }
        } catch(Exception e) { fail(generation,"连接未建立，请重新生成邀请"); }
    }
    private synchronized void fail(int generation,String message) {
        if(generation!=epoch) return;
        stop(); events.stopped(message);
    }
    synchronized void stop() {
        epoch++;
        if(expiry!=null) { expiry.cancel(false); expiry=null; }
        if(session!=null) { session.close(); session=null; }
        try { if(socket!=null) socket.close(); } catch(IOException ignored) { }
        try { if(listener!=null) listener.close(); } catch(IOException ignored) { }
        socket=null; listener=null;
    }
    @Override public void close() { stop(); worker.shutdownNow(); timer.shutdownNow(); }
}
