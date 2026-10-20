package com.floatclip.share;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Transport-independent authenticated encryption for short-lived trusted-device
 * links such as Bluetooth RFCOMM. The long-lived 32-byte token never travels on
 * this transport; sha256(token) is only a lookup key.
 */
public final class TransportCrypto {
    private TransportCrypto(){}
    public static final int MAGIC=0x46434231; // FCB1
    public static final int NONCE_BYTES=32, TOKEN_BYTES=32, PROOF_BYTES=32;
    public static final int MAX_RECORD=70000;
    private static final byte[] CONTEXT="FloatClip-BT-v1".getBytes(StandardCharsets.US_ASCII);
    private static final int C2S=0x43325331, S2C=0x53324331; // C2S1 / S2C1

    public static final class ClientHello {
        public final byte[] relation,nonce;
        ClientHello(byte[] relation,byte[] nonce){this.relation=relation.clone();this.nonce=nonce.clone();}
    }
    public static final class Material {
        public final byte[] clientToServer,serverToClient,clientProof,serverProof;
        Material(byte[] c2s,byte[] s2c,byte[] clientProof,byte[] serverProof){
            this.clientToServer=c2s;this.serverToClient=s2c;this.clientProof=clientProof;this.serverProof=serverProof;
        }
    }

    public static byte[] relation(byte[] token) {
        require(token,TOKEN_BYTES,"token");
        return ShareWire.digest(token);
    }
    public static byte[] clientHello(byte[] token,byte[] nonce)throws IOException{
        require(token,TOKEN_BYTES,"token");require(nonce,NONCE_BYTES,"nonce");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(68);DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(MAGIC);out.write(relation(token));out.write(nonce);return bytes.toByteArray();
    }
    public static ClientHello readClientHello(InputStream raw)throws IOException{
        DataInputStream in=new DataInputStream(raw);if(in.readInt()!=MAGIC)throw new IOException("Unsupported Bluetooth transport");
        byte[] relation=new byte[32],nonce=new byte[32];in.readFully(relation);in.readFully(nonce);return new ClientHello(relation,nonce);
    }
    public static Material derive(byte[] token,byte[] clientNonce,byte[] serverNonce)throws IOException{
        require(token,TOKEN_BYTES,"token");require(clientNonce,NONCE_BYTES,"client nonce");require(serverNonce,NONCE_BYTES,"server nonce");
        byte[] salt=ShareWire.digest(join(CONTEXT,clientNonce,serverNonce));
        byte[] prk=hmac(salt,token);
        byte[] c2s=expand(prk,"client-to-server"),s2c=expand(prk,"server-to-client");
        byte[] clientProof=hmac(prk,join("client-proof".getBytes(StandardCharsets.US_ASCII),clientNonce,serverNonce));
        byte[] serverProof=hmac(prk,join("server-proof".getBytes(StandardCharsets.US_ASCII),clientNonce,serverNonce));
        return new Material(c2s,s2c,clientProof,serverProof);
    }
    private static byte[] expand(byte[] prk,String label)throws IOException{
        return hmac(prk,join(CONTEXT,label.getBytes(StandardCharsets.US_ASCII),new byte[]{1}));
    }
    private static byte[] hmac(byte[] key,byte[] data)throws IOException{
        try{
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(data);
        }catch(GeneralSecurityException e){throw new IOException("HMAC unavailable",e);}
    }
    public static boolean equal(byte[] a,byte[] b){return a!=null&&b!=null&&MessageDigest.isEqual(a,b);}
    private static byte[] join(byte[]...parts){
        int n=0;for(byte[] p:parts)n+=p.length;byte[] out=new byte[n];int at=0;for(byte[] p:parts){System.arraycopy(p,0,out,at,p.length);at+=p.length;}return out;
    }
    private static void require(byte[] value,int length,String label){if(value==null||value.length!=length)throw new IllegalArgumentException("Invalid "+label);}

    public static final class Records {
        private final byte[] sendKey,receiveKey;
        private final int sendPrefix,receivePrefix;
        private long sendCounter,receiveCounter;
        private Records(byte[] sendKey,byte[] receiveKey,int sendPrefix,int receivePrefix){
            this.sendKey=sendKey.clone();this.receiveKey=receiveKey.clone();this.sendPrefix=sendPrefix;this.receivePrefix=receivePrefix;
        }
        public static Records client(Material material){return new Records(material.clientToServer,material.serverToClient,C2S,S2C);}
        public static Records server(Material material){return new Records(material.serverToClient,material.clientToServer,S2C,C2S);}

        public synchronized byte[] seal(byte[] plain)throws IOException{
            if(plain==null||plain.length>MAX_RECORD)throw new IOException("Secure record too large");
            long counter=sendCounter;if(counter==Long.MAX_VALUE)throw new IOException("Secure record counter exhausted");
            byte[] header=header(counter,plain.length),nonce=nonce(sendPrefix,counter);
            byte[] encrypted=crypt(Cipher.ENCRYPT_MODE,sendKey,nonce,aad(sendPrefix,header),plain);
            sendCounter++;
            return join(header,encrypted);
        }
        public synchronized byte[] open(byte[] record)throws IOException{
            if(record==null||record.length<12+16)throw new IOException("Secure record truncated");
            DataInputStream in=new DataInputStream(new ByteArrayInputStream(record));long counter=in.readLong();int length=in.readInt();
            if(counter!=receiveCounter||length<0||length>MAX_RECORD||record.length!=12+length+16)throw new IOException("Secure record sequence");
            byte[] encrypted=new byte[length+16];in.readFully(encrypted);
            byte[] plain=crypt(Cipher.DECRYPT_MODE,receiveKey,nonce(receivePrefix,counter),aad(receivePrefix,Arrays.copyOf(record,12)),encrypted);
            receiveCounter++;return plain;
        }
        public synchronized void write(OutputStream out,byte[] plain)throws IOException{byte[] record=seal(plain);out.write(record);out.flush();}
        public synchronized byte[] read(InputStream raw)throws IOException{
            DataInputStream in=new DataInputStream(raw);byte[] header=new byte[12];int first=in.read();if(first<0)return null;header[0]=(byte)first;in.readFully(header,1,11);
            DataInputStream h=new DataInputStream(new ByteArrayInputStream(header));long counter=h.readLong();int length=h.readInt();
            if(counter!=receiveCounter||length<0||length>MAX_RECORD)throw new IOException("Secure record sequence");
            byte[] record=new byte[12+length+16];System.arraycopy(header,0,record,0,12);in.readFully(record,12,length+16);return open(record);
        }
        private static byte[] crypt(int mode,byte[] key,byte[] nonce,byte[] aad,byte[] data)throws IOException{
            try{
                Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(aad);return cipher.doFinal(data);
            }catch(AEADBadTagException e){throw new IOException("Secure record authentication failed",e);}
            catch(GeneralSecurityException e){throw new IOException("AES-GCM unavailable",e);}
        }
        private static byte[] header(long counter,int length)throws IOException{
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(12);DataOutputStream out=new DataOutputStream(bytes);out.writeLong(counter);out.writeInt(length);return bytes.toByteArray();
        }
        private static byte[] nonce(int prefix,long counter)throws IOException{
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(12);DataOutputStream out=new DataOutputStream(bytes);out.writeInt(prefix);out.writeLong(counter);return bytes.toByteArray();
        }
        private static byte[] aad(int prefix,byte[] header)throws IOException{
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(20);DataOutputStream out=new DataOutputStream(bytes);out.writeInt(MAGIC);out.writeInt(prefix);out.write(header);return bytes.toByteArray();
        }
    }


    public interface TokenLookup { byte[] find(String relationHex) throws Exception; }
    public static final class SecureStreams {
        public final InputStream input; public final OutputStream output; public final String relationHex;
        SecureStreams(InputStream input,OutputStream output,String relationHex){this.input=input;this.output=output;this.relationHex=relationHex;}
    }
    private static String hex(byte[] bytes){
        StringBuilder out=new StringBuilder(bytes.length*2);
        for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));
        return out.toString();
    }
    private static byte[] randomNonce(){byte[] nonce=new byte[NONCE_BYTES];new SecureRandom().nextBytes(nonce);return nonce;}

    /** Trusted-device server handshake. The token is resolved locally from sha256(token) and never crosses the transport. */
    public static SecureStreams accept(InputStream rawIn,OutputStream rawOut,TokenLookup lookup)throws Exception{
        ClientHello hello=readClientHello(rawIn);String relationHex=hex(hello.relation);
        byte[] token=lookup.find(relationHex);
        if(token==null||token.length!=TOKEN_BYTES||!equal(relation(token),hello.relation)){rawOut.write(0);rawOut.flush();throw new IOException("Unknown trusted relation");}
        byte[] serverNonce=randomNonce();Material material=derive(token,hello.nonce,serverNonce);
        rawOut.write(1);rawOut.write(serverNonce);rawOut.write(material.serverProof);rawOut.flush();
        byte[] clientProof=new byte[PROOF_BYTES];new DataInputStream(rawIn).readFully(clientProof);
        if(!equal(clientProof,material.clientProof))throw new IOException("Trusted transport client proof failed");
        Records records=Records.server(material);
        return new SecureStreams(input(rawIn,records),output(rawOut,records),relationHex);
    }

    /** Trusted-device client handshake. Returns secure record streams only after the peer proves knowledge of the same token. */
    public static SecureStreams connect(InputStream rawIn,OutputStream rawOut,byte[] token)throws Exception{
        require(token,TOKEN_BYTES,"token");byte[] clientNonce=randomNonce();
        rawOut.write(clientHello(token,clientNonce));rawOut.flush();
        int status=rawIn.read();if(status!=1)throw new IOException(status<0?"Trusted transport closed":"Trusted relation rejected");
        byte[] serverNonce=new byte[NONCE_BYTES],serverProof=new byte[PROOF_BYTES];DataInputStream in=new DataInputStream(rawIn);in.readFully(serverNonce);in.readFully(serverProof);
        Material material=derive(token,clientNonce,serverNonce);
        if(!equal(serverProof,material.serverProof))throw new IOException("Trusted transport server proof failed");
        rawOut.write(material.clientProof);rawOut.flush();
        Records records=Records.client(material);
        return new SecureStreams(input(rawIn,records),output(rawOut,records),hex(relation(token)));
    }

    public static InputStream input(InputStream raw,Records records){
        return new InputStream(){
            private ByteArrayInputStream current=new ByteArrayInputStream(new byte[0]);
            private boolean eof;
            private boolean fill()throws IOException{
                while(current.available()==0&&!eof){
                    byte[] plain=records.read(raw);if(plain==null){eof=true;return false;}current=new ByteArrayInputStream(plain);
                }
                return current.available()>0;
            }
            @Override public int read()throws IOException{return fill()?current.read():-1;}
            @Override public int read(byte[] b,int off,int len)throws IOException{
                Objects.checkFromIndexSize(off,len,b.length);if(len==0)return 0;if(!fill())return -1;return current.read(b,off,len);
            }
            @Override public void close()throws IOException{raw.close();}
        };
    }
    public static OutputStream output(OutputStream raw,Records records){
        return new OutputStream(){
            private final ByteArrayOutputStream pending=new ByteArrayOutputStream();
            private boolean closed;
            @Override public synchronized void write(int b)throws IOException{ensure(1);pending.write(b);}
            @Override public synchronized void write(byte[] b,int off,int len)throws IOException{Objects.checkFromIndexSize(off,len,b.length);ensure(len);pending.write(b,off,len);}
            private void ensure(int add)throws IOException{if(closed)throw new IOException("Secure stream closed");if(pending.size()+add>MAX_RECORD)throw new IOException("Secure record too large");}
            @Override public synchronized void flush()throws IOException{
                if(closed)throw new IOException("Secure stream closed");if(pending.size()==0){raw.flush();return;}
                records.write(raw,pending.toByteArray());pending.reset();
            }
            @Override public synchronized void close()throws IOException{
                if(closed)return;try{flush();}finally{closed=true;raw.close();}
            }
        };
    }
}
