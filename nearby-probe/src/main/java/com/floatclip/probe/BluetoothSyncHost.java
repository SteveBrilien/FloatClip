package com.floatclip.probe;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import com.floatclip.share.ShareSession;
import com.floatclip.share.TransportCrypto;
import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

/** Trusted-only short-lived RFCOMM listener. Authentication is app-layer TransportCrypto. */
public final class BluetoothSyncHost implements AutoCloseable {
    public static final UUID SERVICE_UUID=UUID.fromString("e06f95e4-fc4d-4e92-b67a-1eb7f9f0a56f");
    public interface Events {
        byte[] token(String relation);
        ShareSession.Events messages(String relation);
        void connected(String relation,String bluetoothAddress,ShareSession session);
        void stopped(String message);
    }
    private final File cache; private final Events events;
    private final ExecutorService acceptor=Executors.newSingleThreadExecutor();
    private final ThreadPoolExecutor handshakes=new ThreadPoolExecutor(0,4,30,TimeUnit.SECONDS,new SynchronousQueue<>());
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor();
    private final Set<BluetoothSocket> sockets=ConcurrentHashMap.newKeySet();
    private volatile boolean closed; private BluetoothServerSocket server;
    public BluetoothSyncHost(File cache,Events events){this.cache=cache;this.events=events;}

    @SuppressLint("MissingPermission")
    public synchronized boolean start(){
        if(closed)return false;if(server!=null)return true;
        BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();
        if(adapter==null||!adapter.isEnabled())return false;
        try{
            server=adapter.listenUsingRfcommWithServiceRecord("FloatClip Clipboard",SERVICE_UUID);
            acceptor.execute(this::acceptLoop);return true;
        }catch(Exception e){server=null;events.stopped("蓝牙短连接暂不可用");return false;}
    }
    @SuppressLint("MissingPermission")
    private void acceptLoop(){
        try{
            while(!closed){
                BluetoothServerSocket listener=server;if(listener==null)return;
                BluetoothSocket socket=listener.accept();
                if(closed){closeSocket(socket);return;}
                if(sockets.size()>=8){closeSocket(socket);continue;}
                sockets.add(socket);
                try{handshakes.execute(()->handshake(socket));}
                catch(RejectedExecutionException busy){sockets.remove(socket);closeSocket(socket);}
            }
        }catch(Exception e){if(!closed){synchronized(this){closeServer();}events.stopped("蓝牙监听已停止，等待蓝牙恢复");}}
    }
    @SuppressLint("MissingPermission")
    private void handshake(BluetoothSocket socket){
        boolean handedOff=false;
        ScheduledFuture<?> timeout=deadlines.schedule(()->closeSocket(socket),15,TimeUnit.SECONDS);
        try{
            TransportCrypto.SecureStreams secure=TransportCrypto.accept(socket.getInputStream(),socket.getOutputStream(),events::token);
            timeout.cancel(false);
            String relation=secure.relationHex;ShareSession.Events delegate=events.messages(relation);
            ShareSession session=new ShareSession(secure.input,secure.output,socket,cache,new ShareSession.Events(){
                public void text(UUID id,String text,boolean outgoing){delegate.text(id,text,outgoing);}
                public void offered(com.floatclip.share.ShareWire.Offer offer){delegate.offered(offer);}
                public void progress(UUID id,String name,long done,long total,boolean outgoing){delegate.progress(id,name,done,total,outgoing);}
                public void file(com.floatclip.share.ShareWire.Offer offer,File local,boolean outgoing){delegate.file(offer,local,outgoing);}
                public void notice(String message){delegate.notice(message);}
                public void failure(Exception e){delegate.failure(e);}
                public void sync(com.floatclip.share.ShareWire.Frame frame)throws Exception{delegate.sync(frame);}
                public void catalog(byte[] data)throws Exception{delegate.catalog(data);}
                public void rejected(com.floatclip.share.ShareWire.Offer offer){delegate.rejected(offer);}
                public void fileFailed(String message){delegate.fileFailed(message);}
                public void closed(){sockets.remove(socket);delegate.closed();}
            },true,true);
            delegate.attached(session);events.connected(relation,socket.getRemoteDevice().getAddress(),session);session.start();
            session.closeWhenIdle(deadlines,180);handedOff=true;
        }catch(Exception ignored){}finally{timeout.cancel(false);if(!handedOff){sockets.remove(socket);closeSocket(socket);}}
    }
    private static void closeSocket(BluetoothSocket socket){try{socket.close();}catch(IOException ignored){}}
    private void closeServer(){BluetoothServerSocket s=server;server=null;if(s!=null)try{s.close();}catch(IOException ignored){}}
    @Override public synchronized void close(){if(closed)return;closed=true;closeServer();for(BluetoothSocket s:sockets)closeSocket(s);sockets.clear();acceptor.shutdownNow();handshakes.shutdownNow();deadlines.shutdownNow();}
}
