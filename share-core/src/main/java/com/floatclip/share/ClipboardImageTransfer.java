package com.floatclip.share;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Names a trusted clipboard-image transfer without changing the v1 text clipboard envelope. */
public final class ClipboardImageTransfer {
    private ClipboardImageTransfer() { }
    public static final long MAX_BYTES=8L*1024*1024;
    public static final int MAX_EDGE=16384;
    public static final long MAX_PIXELS=64_000_000L;
    private static final Pattern NAME=Pattern.compile("^floatclip-clip-v1_([0-9a-f]{32})_([0-9]{1,16})_([0-9]{1,5})x([0-9]{1,5})\\.png$");

    public static final class Meta {
        public final UUID id;
        public final long createdAt;
        public final int width,height;
        public Meta(UUID id,long createdAt,int width,int height){this.id=id;this.createdAt=createdAt;this.width=width;this.height=height;}
    }

    private static void validate(UUID id,long createdAt,int width,int height) throws IOException {
        if(id==null||id.equals(new UUID(0,0))||createdAt<0||width<1||height<1||width>MAX_EDGE||height>MAX_EDGE||(long)width*height>MAX_PIXELS)
            throw new IOException("Invalid clipboard image metadata");
    }

    public static String name(UUID id,long createdAt,int width,int height) throws IOException {
        validate(id,createdAt,width,height);
        return "floatclip-clip-v1_"+id.toString().replace("-","")+"_"+createdAt+"_"+width+"x"+height+".png";
    }

    public static Meta parse(String name) throws IOException {
        Matcher match=NAME.matcher(name==null?"":name);
        if(!match.matches())throw new IOException("Not a clipboard image transfer");
        try {
            UUID id=UUID.fromString(match.group(1).replaceFirst(
                "([0-9a-f]{8})([0-9a-f]{4})([0-9a-f]{4})([0-9a-f]{4})([0-9a-f]{12})","$1-$2-$3-$4-$5"));
            long createdAt=Long.parseLong(match.group(2));
            int width=Integer.parseInt(match.group(3)),height=Integer.parseInt(match.group(4));
            validate(id,createdAt,width,height);
            return new Meta(id,createdAt,width,height);
        } catch(RuntimeException e) { throw new IOException("Invalid clipboard image metadata",e); }
    }

    public static void validatePng(byte[] bytes,Meta meta) throws IOException {
        if(bytes==null||bytes.length<33||bytes.length>MAX_BYTES)throw new IOException("Invalid clipboard image size");
        byte[] signature={(byte)137,80,78,71,13,10,26,10};
        for(int i=0;i<signature.length;i++)if(bytes[i]!=signature[i])throw new IOException("Clipboard image is not PNG");
        int chunk=((bytes[8]&255)<<24)|((bytes[9]&255)<<16)|((bytes[10]&255)<<8)|(bytes[11]&255);
        if(chunk!=13||bytes[12]!='I'||bytes[13]!='H'||bytes[14]!='D'||bytes[15]!='R')throw new IOException("PNG header missing");
        int width=((bytes[16]&255)<<24)|((bytes[17]&255)<<16)|((bytes[18]&255)<<8)|(bytes[19]&255);
        int height=((bytes[20]&255)<<24)|((bytes[21]&255)<<16)|((bytes[22]&255)<<8)|(bytes[23]&255);
        validate(meta.id,meta.createdAt,width,height);
        if(width!=meta.width||height!=meta.height)throw new IOException("Clipboard image dimensions changed");
    }

    public static Meta tryParse(String name) {
        try{return parse(name);}catch(IOException ignored){return null;}
    }
}
