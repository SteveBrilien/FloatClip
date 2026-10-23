package com.floatclip.probe;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import com.floatclip.share.TransportPairing;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One-time FloatClip authorization over an OS-paired/authenticated RFCOMM link. */
public final class BluetoothPairHost implements AutoCloseable {
    public static final UUID SERVICE_UUID=UUID.fromString("e06f95e4-fc4d-4e92-b67a-1eb7f9f0a570");
    public interface Events {
        boolean pairingAllowed();
        void verify(String code,Consumer<Boolean> answer);
        void paired(byte[] token,String bluetoothAddress) throws Exception;
        void stopped(String message);
    }
    private final Events events;
    private final ExecutorService acceptor=Executors.newSingleThreadExecutor();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(0,2,30,TimeUnit.SECONDS,new SynchronousQueue<>());
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor();
    private volatile boolean closed;private BluetoothServerSocket server;

    public BluetoothPairHost(Events events){this.events=events;}

    @SuppressLint("MissingPermission")
    public synchronized boolean start(){
        if(closed)return false;if(server!=null)return true;
        BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();if(adapter==null||!adapter.isEnabled())return false;
        try{
            // Secure RFCOMM is mandatory because the first app token is created on this channel.
            server=adapter.listenUsingRfcommWithServiceRecord("FloatClip Pairing",SERVICE_UUID);
            acceptor.execute(this::loop);return true;
        }catch(Exception e){server=null;events.stopped("蓝牙配对暂不可用");return false;}
    }
    @SuppressLint("MissingPermission")
    private void loop(){
        try{
            while(!closed){
                BluetoothServerSocket listener=server;if(listener==null)return;
                BluetoothSocket socket=listener.accept();
                if(closed){close(socket);return;}
                try{workers.execute(()->pair(socket));}catch(RejectedExecutionException busy){close(socket);}
            }
        }catch(Exception e){if(!closed){synchronized(this){closeServer();}events.stopped("蓝牙配对监听已停止");}}
    }
    @SuppressLint("MissingPermission")
    private void pair(BluetoothSocket socket){
        ScheduledFuture<?> timeout=deadlines.schedule(()->close(socket),75,TimeUnit.SECONDS);
        try{
            if(!events.pairingAllowed()||socket.getRemoteDevice().getBondState()!=BluetoothDevice.BOND_BONDED)throw new IOException("Bluetooth device is not bonded");
            TransportPairing.accept(socket.getInputStream(),socket.getOutputStream(),code->{
                if(!events.pairingAllowed())return false;
                CompletableFuture<Boolean> answer=new CompletableFuture<>();events.verify(code,answer::complete);
                return answer.get(60,TimeUnit.SECONDS)&&events.pairingAllowed();
            },token->{
                if(!events.pairingAllowed())throw new IOException("Pairing window closed");
                events.paired(token,socket.getRemoteDevice().getAddress());
            });
        }catch(Exception ignored){}finally{timeout.cancel(false);close(socket);}
    }
    private static void close(BluetoothSocket socket){try{socket.close();}catch(IOException ignored){}}
    private void closeServer(){BluetoothServerSocket s=server;server=null;if(s!=null)try{s.close();}catch(IOException ignored){}}
    @Override public synchronized void close(){if(closed)return;closed=true;closeServer();acceptor.shutdownNow();workers.shutdownNow();deadlines.shutdownNow();}
}
