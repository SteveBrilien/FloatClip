package com.floatclip.share;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.util.*;

/** Bounded application framing inside an authenticated TLS connection. */
public final class ShareWire {
    private ShareWire() { }
    public static final int TEXT=1, ACK=2, OFFER=3, ACCEPT=4, REJECT=5, CHUNK=6, END=7;
    public static final int MAX_TEXT=65536, CHUNK_SIZE=32768;
    public static final long MAX_FILE=64L*1024*1024;
    public static final class Frame {
        public final int type; public final UUID id; public final byte[] data;
        public Frame(int type, UUID id, byte[] data) { this.type=type; this.id=id; this.data=data.clone(); }
    }
    public static byte[] digest(byte[] data) { return sha().digest(data); }
    public static MessageDigest sha() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public static String text(byte[] data) throws IOException {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString(); }
        catch (CharacterCodingException e) { throw new IOException("Invalid UTF-8", e); }
    }
    public static void write(OutputStream raw, Frame f) throws IOException {
        validate(f.type, f.data.length);
        ByteArrayOutputStream packet=new ByteArrayOutputStream(58+f.data.length);
        DataOutputStream out=new DataOutputStream(packet);
        out.writeInt(0x46435331); out.writeByte(1); out.writeByte(f.type);
        out.writeLong(f.id.getMostSignificantBits()); out.writeLong(f.id.getLeastSignificantBits());
        out.writeInt(f.data.length); out.write(digest(f.data)); out.write(f.data); out.flush();
        raw.write(packet.toByteArray()); raw.flush();
    }
    public static Frame read(InputStream raw) throws IOException {
        DataInputStream in=new DataInputStream(raw);
        int first=in.read(); if(first<0) return null;
        byte[] rest=new byte[3]; in.readFully(rest);
        if(first!=0x46 || rest[0]!=0x43 || rest[1]!=0x53 || rest[2]!=0x31 || in.readUnsignedByte()!=1)
            throw new IOException("Unsupported sharing protocol");
        int type=in.readUnsignedByte(); UUID id=new UUID(in.readLong(),in.readLong());
        int length=in.readInt(); validate(type,length);
        byte[] hash=new byte[32]; in.readFully(hash); byte[] data=new byte[length]; in.readFully(data);
        if(!MessageDigest.isEqual(hash,digest(data))) throw new IOException("Frame integrity failed");
        return new Frame(type,id,data);
    }
    private static void validate(int type,int length) throws IOException {
        if(type<TEXT || type>END || length<0 || length>MAX_TEXT) throw new IOException("Frame limit exceeded");
        if((type==ACK || type==END) && length!=32) throw new IOException("Invalid digest frame");
        if((type==ACCEPT || type==REJECT) && length!=0) throw new IOException("Invalid decision frame");
        if(type==CHUNK && (length<4 || length>CHUNK_SIZE+4)) throw new IOException("Invalid chunk");
        if(type==OFFER && (length<43 || length>282)) throw new IOException("Invalid file offer");
    }
    public static String safeName(String name) throws IOException {
        byte[] bytes=name.getBytes(StandardCharsets.UTF_8);
        if(bytes.length==0 || bytes.length>240 || name.equals(".") || name.equals("..") ||
                name.endsWith(".") || name.endsWith(" ")) throw new IOException("Unsupported filename");
        for(char c:name.toCharArray()) if(c<32 || "\\/:*?\"<>|".indexOf(c)>=0) throw new IOException("Unsafe filename");
        String stem=name.split("\\.",2)[0].toUpperCase(Locale.ROOT);
        if(stem.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) throw new IOException("Reserved filename");
        return name;
    }
    public static final class Offer {
        public final UUID id; public final String name; public final long size; public final byte[] hash;
        public Offer(UUID id,String name,long size,byte[] hash) throws IOException {
            this.id=id; this.name=safeName(name); this.size=size; this.hash=hash.clone();
            if(size<0 || size>MAX_FILE || hash.length!=32) throw new IOException("File limit is 64 MiB");
        }
        public byte[] encode() throws IOException {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(bytes);
            byte[] nameBytes=name.getBytes(StandardCharsets.UTF_8);
            out.writeLong(size); out.writeShort(nameBytes.length); out.write(nameBytes); out.write(hash); return bytes.toByteArray();
        }
        public static Offer decode(Frame f) throws IOException {
            DataInputStream in=new DataInputStream(new ByteArrayInputStream(f.data));
            long size=in.readLong(); int length=in.readUnsignedShort();
            if(length<1 || length>240 || f.data.length!=42+length) throw new IOException("Invalid filename length");
            byte[] name=new byte[length]; in.readFully(name); byte[] hash=new byte[32]; in.readFully(hash);
            return new Offer(f.id,text(name),size,hash);
        }
    }
}
