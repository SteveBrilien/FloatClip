package com.floatclip.share;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** All packets stay inside 127/8. No discovery traffic reaches a physical adapter. */
public final class DiscoverySocketChecks {
    static int checks;
    static void yes(boolean value){if(!value)throw new AssertionError("Discovery socket check "+checks);checks++;}
    static DatagramPacket receive(DatagramSocket socket)throws Exception {byte[] data=new byte[128];DatagramPacket p=new DatagramPacket(data,data.length);socket.receive(p);return p;}
    public static void main(String[] args)throws Exception{
        InetAddress local=InetAddress.getByName("127.0.0.1"),broadcast=InetAddress.getByName("127.255.255.255");
        byte[] data="FLOATCLIP3|loopback-only".getBytes(StandardCharsets.US_ASCII);
        try(DiscoverySockets sockets=new DiscoverySockets(local,broadcast,0);DatagramSocket sender=new DatagramSocket(new InetSocketAddress(local,0))){
            yes(sockets.broadcast!=null);sockets.local.setSoTimeout(250);sockets.broadcast.setSoTimeout(1000);sender.setSoTimeout(1000);sender.setBroadcast(true);
            int port=sockets.local.getLocalPort();sender.send(new DatagramPacket(data,data.length,broadcast,port));DatagramPacket query=receive(sockets.broadcast);
            yes(Arrays.equals(data,Arrays.copyOf(query.getData(),query.getLength())));
            try{receive(sockets.local);throw new AssertionError("Unicast socket unexpectedly received directed broadcast");}catch(SocketTimeoutException expected){checks++;}
            sockets.reply(new DatagramPacket(data,data.length,query.getAddress(),query.getPort()));DatagramPacket reply=receive(sender);
            yes(reply.getAddress().equals(local)&&reply.getPort()==port);
            sender.send(new DatagramPacket(data,data.length,local,port));query=receive(sockets.local);yes(query.getAddress().equals(local));
        }
        System.out.println("PASS scoped discovery: "+checks+" loopback checks; directed broadcast, unicast and fixed source address/port");
    }
}
