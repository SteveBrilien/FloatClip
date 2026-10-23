package com.floatclip.share;

import java.io.IOException;
import java.net.*;

/** Receive only on the selected local and directed-broadcast addresses; reply from local, never wildcard. */
public final class DiscoverySockets implements AutoCloseable {
    public final DatagramSocket local,broadcast;
    public DiscoverySockets(InetAddress localAddress,InetAddress broadcastAddress,int port)throws SocketException {
        local=bind(localAddress,port);
        DatagramSocket receiver=null;
        // Some vendor stacks may reject binding a directed broadcast address. Keep scoped unicast
        // discovery (e.g. hotspot gateway requests) available rather than killing the TLS listener.
        if(broadcastAddress!=null&&!broadcastAddress.equals(localAddress))try{receiver=bind(broadcastAddress,local.getLocalPort());}catch(SocketException unsupported){}
        broadcast=receiver;
    }
    private static DatagramSocket bind(InetAddress address,int port)throws SocketException {
        DatagramSocket socket=new DatagramSocket(null);
        try{socket.setReuseAddress(true);socket.bind(new InetSocketAddress(address,port));return socket;}
        catch(SocketException e){socket.close();throw e;}
    }
    public void reply(DatagramPacket packet)throws IOException{local.send(packet);}
    public void close(){local.close();if(broadcast!=null)broadcast.close();}
}
