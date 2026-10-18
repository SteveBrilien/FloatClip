package com.floatclip.probe;

import android.content.SharedPreferences;
import com.floatclip.share.ShareSession;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.net.ssl.*;

/** Foreground, bounded discovery on ONE explicitly selected physical interface. */
final class LanPair implements AutoCloseable {
    interface Events {
        void available(String ip);
        void verify(String code, Consumer<Boolean> answer);
        void connected(ShareSession value);
        void stopped(String message);
    }
    static final int PORT=46239, DISCOVERY=46240;
    private final File cache; private final SharedPreferences trust;
    private final Events events; private final ShareSession.Events messages;
    private final ExecutorService workers=Executors.newFixedThreadPool(2);
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    private volatile boolean closed; private ServerSocket listener; private DatagramSocket discovery;
    private Socket socket; private ShareSession session;
    LanPair(File cache, SharedPreferences trust, Events events, ShareSession.Events messages) {
        this.cache=cache; this.trust=trust; this.events=events; this.messages=messages;
    }
    void listen(String ip) { workers.execute(()->serve(ip)); }
    static byte[] random(int n) { byte[] value=new byte[n]; new SecureRandom().nextBytes(value); return value; }
    static String hex(byte[] value) { StringBuilder s=new StringBuilder(); for(byte b:value) s.append(String.format(Locale.ROOT,"%02x",b&255)); return s.toString(); }
    static String fingerprint(String pin,byte[] id,byte[] client,byte[] server) throws Exception {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        hash.update("FloatClip-Pair-v2".getBytes(StandardCharsets.US_ASCII)); hash.update(pin.getBytes(StandardCharsets.US_ASCII));
        hash.update(id); hash.update(client); hash.update(server);
        String value=hex(hash.digest()).substring(0,16).toUpperCase(Locale.ROOT);
        return value.substring(0,4)+" "+value.substring(4,8)+" "+value.substring(8,12)+" "+value.substring(12,16);
    }
    private void serve(String ip) {
        try {
            InetAddress address=LanTls.checkedAddress(ip); SSLContext tls=LanTls.context(); String pin=LanTls.pin().toLowerCase(Locale.ROOT);
            byte[] id=random(16); String idHex=hex(id);
            SSLServerSocket server=(SSLServerSocket)tls.getServerSocketFactory().createServerSocket();
            synchronized(this) { if(closed) { server.close(); return; } listener=server; }
            server.setReuseAddress(true); server.bind(new InetSocketAddress(address,PORT),1);
            server.setEnabledProtocols(new String[]{"TLSv1.2"}); server.setSoTimeout(90000);
            DatagramSocket udp=new DatagramSocket(null);
            synchronized(this) { if(closed) { udp.close(); return; } discovery=udp; }
            udp.setReuseAddress(true); udp.bind(new InetSocketAddress(address,DISCOVERY)); udp.setSoTimeout(1000);
            InterfaceAddress subnet=NetworkInterface.getByInetAddress(address).getInterfaceAddresses().stream().filter(a->a.getAddress().equals(address)).findFirst().orElseThrow(IOException::new);
            workers.execute(()->advertise(udp,subnet,pin,idHex));
            ScheduledFuture<?> expiry=timer.schedule(()->stopWith("发现已暂停，点击重新允许连接"),90,TimeUnit.SECONDS);
            events.available(ip);
            int attempts=0;
            while(!closed && attempts++<3) {
                SSLSocket peer=(SSLSocket)server.accept();
                synchronized(this) { if(closed) { peer.close(); return; } socket=peer; }
                if(!sameSubnet(address,peer.getInetAddress(),subnet.getNetworkPrefixLength())) { peer.close(); continue; }
                ScheduledFuture<?> handshake=timer.schedule(()->{ try { peer.close(); } catch(IOException ignored) { } },15,TimeUnit.SECONDS);
                try {
                    peer.setSoTimeout(10000); peer.setTcpNoDelay(true); peer.startHandshake();
                    DataInputStream in=new DataInputStream(peer.getInputStream()); DataOutputStream out=new DataOutputStream(peer.getOutputStream());
                    if(in.readInt()!=0x46435032) throw new IOException();
                    byte[] offered=new byte[16]; in.readFully(offered); if(!MessageDigest.isEqual(id,offered)) throw new IOException();
                    int count=in.readUnsignedByte(); if(count!=0 && count!=32) throw new IOException();
                    byte[] token=new byte[count]; in.readFully(token); byte[] client=new byte[32]; in.readFully(client);
                    boolean known=count==32 && trust.contains("peer-"+hex(MessageDigest.getInstance("SHA-256").digest(token)));
                    if(count!=0 && !known) { out.writeByte(0); out.flush(); throw new IOException(); }
                    handshake.cancel(false);
                    ScheduledFuture<?> confirm=timer.schedule(()->{ try { peer.close(); } catch(IOException ignored) { } },60,TimeUnit.SECONDS);
                    try {
                        peer.setSoTimeout(60000);
                        out.writeByte(known ? 1 : 2); byte[] nonce=random(32); out.write(nonce); out.flush();
                        CompletableFuture<Boolean> approved=new CompletableFuture<>();
                        if(known) approved.complete(true); else events.verify(fingerprint(pin,id,client,nonce),approved::complete);
                        if(in.readUnsignedByte()!=1 || !approved.get(60,TimeUnit.SECONDS)) { out.writeByte(0); out.flush(); throw new IOException(); }
                        synchronized(this) {
                        if(closed) return;
                        if(!known) {
                            if(trust.getAll().size()>=8) throw new IOException("Pair list full");
                            token=random(32); trust.edit().putBoolean("peer-"+hex(MessageDigest.getInstance("SHA-256").digest(token)),true).apply();
                        }
                        out.writeByte(1); out.write(token); out.flush();
                        }
                        synchronized(this) {
                            if(closed) return;
                            expiry.cancel(false); udp.close(); server.close(); listener=null; discovery=null;
                            session=new ShareSession(peer,cache,messages); events.connected(session); session.start();
                        }
                        return;
                    } finally { confirm.cancel(false); }
                } catch(Exception ignored) { peer.close(); }
                finally { handshake.cancel(false); }
            }
            stopWith("配对未完成，点击重新允许连接");
        } catch(Exception e) { if(!closed) stopWith("连接未建立，请检查 Wi-Fi 或热点后重试"); }
    }
    private void advertise(DatagramSocket udp,InterfaceAddress subnet,String pin,String id) {
        long nextReply=0;
        while(!closed && !udp.isClosed()) try {
            byte[] data=new byte[513]; DatagramPacket packet=new DatagramPacket(data,data.length); udp.receive(packet);
            if(packet.getLength()>128 || !sameSubnet(subnet.getAddress(),packet.getAddress(),subnet.getNetworkPrefixLength())) continue;
            String query=new String(packet.getData(),0,packet.getLength(),StandardCharsets.US_ASCII);
            if(!query.matches("FLOATCLIP2\\|[0-9a-f]{32}") || System.nanoTime()<nextReply) continue;
            nextReply=System.nanoTime()+100_000_000L;
            String name=android.os.Build.MODEL; if(name.length()>40) name=name.substring(0,40);
            byte[] answer=(query+"|"+PORT+"|"+pin+"|"+id+"|"+Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.US_ASCII);
            udp.send(new DatagramPacket(answer,answer.length,packet.getAddress(),packet.getPort()));
        } catch(SocketTimeoutException ignored) { } catch(Exception e) { break; }
    }
    static boolean sameSubnet(InetAddress a,InetAddress b,int prefix) {
        byte[] x=a.getAddress(),y=b.getAddress(); if(x.length!=4 || y.length!=4 || !b.isSiteLocalAddress()) return false;
        for(int i=0;i<4;i++) { int bits=Math.max(0,Math.min(8,prefix-i*8)),mask=(0xff<<(8-bits))&255; if((x[i]&mask)!=(y[i]&mask)) return false; } return true;
    }
    private void stopWith(String value) { close(); events.stopped(value); }
    @Override public synchronized void close() {
        if(closed) return; closed=true;
        if(session!=null) session.close();
        try { if(socket!=null) socket.close(); } catch(IOException ignored) { }
        try { if(listener!=null) listener.close(); } catch(IOException ignored) { }
        if(discovery!=null) discovery.close(); workers.shutdownNow(); timer.shutdownNow();
    }
}
