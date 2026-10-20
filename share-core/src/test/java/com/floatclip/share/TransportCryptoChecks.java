package com.floatclip.share;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;

public final class TransportCryptoChecks {
    private static int checks;
    private static void yes(boolean value){if(!value)throw new AssertionError("transport crypto check "+checks);checks++;}
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(java.util.Locale.ROOT,"%02x",x&255));return s.toString();}
    public static void main(String[] args)throws Exception{
        Path vectors=Paths.get(args[0]);Files.createDirectories(vectors);
        byte[] token=new byte[32],clientNonce=new byte[32],serverNonce=new byte[32];
        for(int i=0;i<32;i++){token[i]=(byte)(i+1);clientNonce[i]=(byte)(i+33);serverNonce[i]=(byte)(i+65);}
        byte[] hello=TransportCrypto.clientHello(token,clientNonce);
        var material=TransportCrypto.derive(token,clientNonce,serverNonce);
        byte[] payload="FloatClip Bluetooth secure frame 中文 📋".getBytes(StandardCharsets.UTF_8);
        var client=TransportCrypto.Records.client(material);var server=TransportCrypto.Records.server(material);
        byte[] c0=client.seal(payload);yes(Arrays.equals(payload,server.open(c0)));
        byte[] reply="ACK over Bluetooth".getBytes(StandardCharsets.UTF_8);byte[] s0=server.seal(reply);yes(Arrays.equals(reply,client.open(s0)));
        try{server.open(c0);throw new AssertionError("replay accepted");}catch(java.io.IOException expected){checks++;}
        byte[] changed=c0.clone();changed[changed.length-1]^=1;
        var fresh=TransportCrypto.Records.server(material);try{fresh.open(changed);throw new AssertionError("tamper accepted");}catch(java.io.IOException expected){checks++;}
        yes(TransportCrypto.equal(TransportCrypto.relation(token),Arrays.copyOfRange(hello,4,36)));
        java.io.ByteArrayOutputStream encryptedStream=new java.io.ByteArrayOutputStream();
        var framedClient=TransportCrypto.Records.client(material);var secureOut=TransportCrypto.output(encryptedStream,framedClient);
        ShareWire.Frame frame=new ShareWire.Frame(ClipboardSync.PING,new java.util.UUID(0,0),new byte[0]);ShareWire.write(secureOut,frame);
        var framedServer=TransportCrypto.Records.server(material);var secureIn=TransportCrypto.input(new java.io.ByteArrayInputStream(encryptedStream.toByteArray()),framedServer);
        ShareWire.Frame decoded=ShareWire.read(secureIn);yes(decoded.type==ClipboardSync.PING&&decoded.id.equals(frame.id)&&decoded.data.length==0);
        Files.write(vectors.resolve("bt-client-hello.bin"),hello);
        Files.write(vectors.resolve("bt-client-nonce.bin"),clientNonce);
        Files.write(vectors.resolve("bt-server-nonce.bin"),serverNonce);
        Files.write(vectors.resolve("bt-server-proof.bin"),material.serverProof);
        Files.write(vectors.resolve("bt-client-proof.bin"),material.clientProof);
        Files.write(vectors.resolve("bt-c2s-record.bin"),c0);
        Files.write(vectors.resolve("bt-s2c-record.bin"),s0);
        Files.write(vectors.resolve("bt-payload.bin"),payload);
        Files.write(vectors.resolve("bt-reply.bin"),reply);

        // Full interactive handshake over in-memory pipes: no token is ever sent on the wire.
        java.io.PipedInputStream serverIn=new java.io.PipedInputStream(),clientIn=new java.io.PipedInputStream();
        java.io.PipedOutputStream clientOut=new java.io.PipedOutputStream(serverIn),serverOut=new java.io.PipedOutputStream(clientIn);
        java.util.concurrent.atomic.AtomicReference<ShareWire.Frame> serverFrame=new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> serverFailure=new java.util.concurrent.atomic.AtomicReference<>();
        Thread serverThread=new Thread(()->{try{
            var secure=TransportCrypto.accept(serverIn,serverOut,key->key.equals(hex(TransportCrypto.relation(token)))?token:null);
            serverFrame.set(ShareWire.read(secure.input));
        }catch(Throwable e){serverFailure.set(e);}});
        serverThread.start();var clientSecure=TransportCrypto.connect(clientIn,clientOut,token);
        ShareWire.write(clientSecure.output,new ShareWire.Frame(ClipboardSync.PING,new java.util.UUID(0,0),new byte[0]));
        serverThread.join(3000);yes(serverFailure.get()==null&&serverFrame.get()!=null);yes(serverFrame.get().type==ClipboardSync.PING);
        clientSecure.output.close();

        System.out.println("PASS transport crypto Java: "+checks+" checks; authenticated handshake, AES-GCM, replay and tamper rejection");
    }
}
