package com.floatclip.probe;

import android.content.SharedPreferences;
import com.floatclip.share.ShareSession;
import com.floatclip.share.DiscoverySockets;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.net.ssl.*;

/** Persistent FCP3 listener. New grants require an explicit, time-bounded pairing window. */
public final class LanSyncHost implements AutoCloseable {
    public interface Events {
        boolean pairingAllowed();
        boolean reconnectAllowed();
        void verify(String code,Consumer<Boolean> answer);
        ShareSession.Events messages(String credentialHash);
        void connected(String credentialHash,ShareSession session);
        void stopped(String message);
    }
    private final File cache;
    private final SharedPreferences trust;
    private final Events events;
    private final ExecutorService listenerWorker=Executors.newFixedThreadPool(3);
    private final ThreadPoolExecutor handshakes=new ThreadPoolExecutor(0,8,30,TimeUnit.SECONDS,new SynchronousQueue<>());
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor();
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private ServerSocket server;
    private DiscoverySockets discovery;
    private final byte[] discoveryId=LanPair.random(16);
    public LanSyncHost(File cache,SharedPreferences trust,Events events){this.cache=cache;this.trust=trust;this.events=events;}
    public void listen(String ip){listenerWorker.execute(()->serve(ip));}
    private void serve(String ip){
        try{
            InetAddress address=LanTls.checkedAddress(ip);
            InterfaceAddress subnet=NetworkInterface.getByInetAddress(address).getInterfaceAddresses().stream().filter(a->a.getAddress().equals(address)).findFirst().orElseThrow(IOException::new);
            String pin=LanTls.pin().toLowerCase(Locale.ROOT);
            SSLServerSocket listener=(SSLServerSocket)LanTls.context().getServerSocketFactory().createServerSocket();
            synchronized(this){if(closed){listener.close();return;}server=listener;}
            listener.setReuseAddress(true);listener.setEnabledProtocols(new String[]{"TLSv1.2"});listener.bind(new InetSocketAddress(address,LanPair.PORT),8);
            DiscoverySockets advertisement=new DiscoverySockets(address,subnet.getBroadcast(),LanPair.DISCOVERY);
            synchronized(this){if(closed){advertisement.close();return;}discovery=advertisement;}
            listenerWorker.execute(()->advertise(advertisement.local,advertisement,subnet,pin));
            if(advertisement.broadcast!=null)listenerWorker.execute(()->advertise(advertisement.broadcast,advertisement,subnet,pin));
            while(!closed){
                SSLSocket peer=(SSLSocket)listener.accept();
                if(!LanPair.sameSubnet(address,peer.getInetAddress(),subnet.getNetworkPrefixLength())||sockets.size()>=16){peer.close();continue;}
                sockets.add(peer);
                try{handshakes.execute(()->handshake(peer,pin));}catch(RejectedExecutionException busy){sockets.remove(peer);peer.close();}
            }
        }catch(Exception e){if(!closed){close();events.stopped("局域网连接不可用，等待网络恢复");}}
    }
    private void handshake(SSLSocket peer,String pin){
        boolean handedOff=false;
        ScheduledFuture<?> timeout=deadlines.schedule(()->closeSocket(peer),15,TimeUnit.SECONDS);
        try{
            peer.setSoTimeout(10000);peer.setTcpNoDelay(true);peer.startHandshake();
            DataInputStream in=new DataInputStream(peer.getInputStream());DataOutputStream out=new DataOutputStream(peer.getOutputStream());
            if(in.readInt()!=0x46435033)throw new IOException("FCP3 required");
            byte[] offered=new byte[16];in.readFully(offered);if(!MessageDigest.isEqual(offered,discoveryId))throw new IOException("Stale discovery");
            int length=in.readUnsignedByte();if(length!=0&&length!=32)throw new IOException("Invalid credential");
            byte[] token=new byte[length];in.readFully(token);byte[] client=new byte[32];in.readFully(client);
            String hash=length==32?LanPair.hex(MessageDigest.getInstance("SHA-256").digest(token)):"";
            boolean known=length==32&&trust.getBoolean("peer-"+hash,false);
            if(length!=0&&!known){out.writeByte(0);out.flush();return;}
            if(known&&!events.reconnectAllowed() || !known&&!events.pairingAllowed()){out.writeByte(3);out.flush();return;}
            timeout.cancel(false);timeout=deadlines.schedule(()->closeSocket(peer),60,TimeUnit.SECONDS);peer.setSoTimeout(60000);
            out.writeByte(known?1:2);byte[] nonce=LanPair.random(32);out.write(nonce);out.flush();
            CompletableFuture<Boolean> approved=new CompletableFuture<>();
            if(known)approved.complete(true);else events.verify(LanPair.fingerprint(pin,discoveryId,client,nonce),approved::complete);
            if(in.readUnsignedByte()!=1||!approved.get(60,TimeUnit.SECONDS)||closed)return;
            synchronized(trust){
                if(known&&(!trust.getBoolean("peer-"+hash,false)||!events.reconnectAllowed()))return;
                if(!known){
                    if(!events.pairingAllowed()||trust.getAll().keySet().stream().filter(k->k.startsWith("peer-")).count()>=8)return;
                    token=LanPair.random(32);hash=LanPair.hex(MessageDigest.getInstance("SHA-256").digest(token));
                    if(!trust.edit().putBoolean("peer-"+hash,true).commit())throw new IOException("Cannot persist authorization");
                }
                out.writeByte(1);out.write(token);out.flush();
            }
            if(closed)return;
            final String identity=hash;
            ShareSession.Events delegate=events.messages(identity);
            ShareSession session=new ShareSession(peer,cache,new ShareSession.Events(){
                public void text(UUID id,String text,boolean outgoing){delegate.text(id,text,outgoing);}
                public void offered(com.floatclip.share.ShareWire.Offer offer){delegate.offered(offer);}
                public void progress(UUID id,String name,long done,long total,boolean outgoing){delegate.progress(id,name,done,total,outgoing);}
                public void file(com.floatclip.share.ShareWire.Offer offer,File local,boolean outgoing){delegate.file(offer,local,outgoing);}
                public void notice(String message){delegate.notice(message);}
                public void failure(Exception e){delegate.failure(e);}
                public void sync(com.floatclip.share.ShareWire.Frame frame)throws Exception{delegate.sync(frame);}
                public void closed(){sockets.remove(peer);delegate.closed();}
            },true);
            delegate.attached(session);events.connected(identity,session);session.start();handedOff=true;
        }catch(Exception ignored){/* No secrets or clipboard contents in diagnostics. */}
        finally{timeout.cancel(false);if(!handedOff){sockets.remove(peer);closeSocket(peer);}}
    }
    private void advertise(DatagramSocket socket,DiscoverySockets replies,InterfaceAddress subnet,String pin){
        long next=0;
        while(!closed&&!socket.isClosed())try{
            byte[] data=new byte[513];DatagramPacket query=new DatagramPacket(data,data.length);socket.receive(query);
            if(query.getLength()>128||!LanPair.sameSubnet(subnet.getAddress(),query.getAddress(),subnet.getNetworkPrefixLength())||System.nanoTime()<next)continue;
            String text=new String(query.getData(),0,query.getLength(),StandardCharsets.US_ASCII);
            if(!text.matches("FLOATCLIP3\\|[0-9a-f]{32}"))continue;
            next=System.nanoTime()+100000000L;
            String name=android.os.Build.MODEL;name=name.substring(0,Math.min(40,name.length()));
            byte[] answer=(text+"|"+LanPair.PORT+"|"+pin+"|"+LanPair.hex(discoveryId)+"|"+Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.US_ASCII);
            replies.reply(new DatagramPacket(answer,answer.length,query.getAddress(),query.getPort()));
        }catch(Exception e){break;}
    }
    private static void closeSocket(Socket socket){try{socket.close();}catch(IOException ignored){}}
    @Override public synchronized void close(){
        if(closed)return;closed=true;
        if(server!=null)try{server.close();}catch(IOException ignored){}
        if(discovery!=null)discovery.close();for(Socket socket:sockets)closeSocket(socket);sockets.clear();
        listenerWorker.shutdownNow();handshakes.shutdownNow();deadlines.shutdownNow();
    }
}
