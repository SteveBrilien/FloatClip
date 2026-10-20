package com.floatclip.share;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * One-time application pairing over an already OS-authenticated RFCOMM link.
 * The RFCOMM channel MUST require Bluetooth authentication/encryption.
 */
public final class TransportPairing {
    private TransportPairing(){}
    public static final int MAGIC=0x46434250; // FCBP
    public static final int NONCE_BYTES=32,TOKEN_BYTES=32;
    private static final byte[] CONTEXT="FloatClip-Bluetooth-Pair-v1".getBytes(StandardCharsets.US_ASCII);

    public interface Verify { boolean confirm(String code) throws Exception; }
    public interface Persist { void save(byte[] token) throws Exception; }

    public static final class ClientHello {
        public final byte[] nonce;
        ClientHello(byte[] nonce){this.nonce=nonce.clone();}
    }

    public static byte[] clientHello(byte[] nonce)throws IOException{
        if(nonce==null||nonce.length!=NONCE_BYTES)throw new IllegalArgumentException("Invalid pairing nonce");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(36);DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(MAGIC);out.write(nonce);return bytes.toByteArray();
    }
    public static ClientHello readClientHello(InputStream raw)throws IOException{
        DataInputStream in=new DataInputStream(raw);if(in.readInt()!=MAGIC)throw new IOException("Unsupported Bluetooth pairing protocol");
        byte[] nonce=new byte[NONCE_BYTES];in.readFully(nonce);return new ClientHello(nonce);
    }
    public static String fingerprint(byte[] clientNonce,byte[] serverNonce){
        if(clientNonce==null||clientNonce.length!=NONCE_BYTES||serverNonce==null||serverNonce.length!=NONCE_BYTES)throw new IllegalArgumentException("Invalid pairing nonce");
        MessageDigest sha=ShareWire.sha();sha.update(CONTEXT);sha.update(clientNonce);sha.update(serverNonce);
        byte[] digest=sha.digest();StringBuilder text=new StringBuilder(19);
        for(int i=0;i<8;i++){if(i>0&&i%2==0)text.append(' ');text.append(String.format(Locale.ROOT,"%02X",digest[i]&255));}
        return text.toString();
    }
    public static byte[] random32(){byte[] out=new byte[32];new SecureRandom().nextBytes(out);return out;}

    /**
     * Server side. Persistence happens only after the client confirms it stored
     * the token; final success is sent only after local persistence succeeds.
     */
    public static byte[] accept(InputStream rawIn,OutputStream rawOut,Verify verify,Persist persist)throws Exception{
        ClientHello hello=readClientHello(rawIn);byte[] serverNonce=random32();
        rawOut.write(serverNonce);rawOut.flush();
        boolean local=verify.confirm(fingerprint(hello.nonce,serverNonce));
        int remote=rawIn.read();if(!local||remote!=1){rawOut.write(0);rawOut.flush();throw new IOException("Bluetooth pairing rejected");}
        byte[] token=random32();rawOut.write(1);rawOut.write(token);rawOut.flush();
        if(rawIn.read()!=1)throw new IOException("Peer did not persist Bluetooth pairing");
        try{persist.save(token);}catch(Exception e){rawOut.write(0);rawOut.flush();throw e;}
        rawOut.write(1);rawOut.flush();return token;
    }

    /**
     * Client side. If final server persistence fails, remove() must undo the
     * provisional token so a one-sided authorization is not silently retained.
     */
    public static byte[] connect(InputStream rawIn,OutputStream rawOut,Verify verify,Persist persist,Runnable remove)throws Exception{
        byte[] clientNonce=random32();rawOut.write(clientHello(clientNonce));rawOut.flush();
        byte[] serverNonce=new byte[NONCE_BYTES];new DataInputStream(rawIn).readFully(serverNonce);
        boolean accepted=verify.confirm(fingerprint(clientNonce,serverNonce));rawOut.write(accepted?1:0);rawOut.flush();
        if(!accepted)throw new IOException("Bluetooth pairing rejected");
        int status=rawIn.read();if(status!=1)throw new IOException("Peer rejected Bluetooth pairing");
        byte[] token=new byte[TOKEN_BYTES];new DataInputStream(rawIn).readFully(token);
        try{persist.save(token);rawOut.write(1);rawOut.flush();}
        catch(Exception e){rawOut.write(0);rawOut.flush();throw e;}
        if(rawIn.read()!=1){try{remove.run();}catch(Exception ignored){}throw new IOException("Peer could not persist Bluetooth pairing");}
        return token;
    }
}
