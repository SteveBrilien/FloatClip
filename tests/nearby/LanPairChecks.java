package com.floatclip.probe;
import java.net.InetAddress;
public class LanPairChecks {
    static int checks;
    static void yes(boolean value) { if(!value) throw new AssertionError("check "+checks); checks++; }
    public static void main(String[] args) throws Exception {
        byte[] id=new byte[16],client=new byte[32],server=new byte[32];
        for(int i=0;i<32;i++) { client[i]=(byte)i; server[i]=(byte)(i+32); if(i<16) id[i]=(byte)i; }
        yes(LanPair.fingerprint("a".repeat(64),id,client,server).equals("8067 FAAD F0E2 7FA0"));
        InetAddress local=InetAddress.getByName("192.168.246.159");
        yes(LanPair.sameSubnet(local,InetAddress.getByName("192.168.246.1"),24));
        yes(LanPair.sameSubnet(local,InetAddress.getByName("192.168.246.250"),24));
        yes(!LanPair.sameSubnet(local,InetAddress.getByName("192.168.245.1"),24));
        yes(!LanPair.sameSubnet(local,InetAddress.getByName("8.8.8.8"),24));
        yes(!LanPair.sameSubnet(local,InetAddress.getByName("::1"),24));
        client[0]^=1; yes(!LanPair.fingerprint("a".repeat(64),id,client,server).equals("8067 FAAD F0E2 7FA0"));
        int[] writes={0};
        com.floatclip.share.ShareWire.write(new java.io.OutputStream() {
            @Override public void write(int b) { writes[0]++; }
            @Override public void write(byte[] b,int offset,int count) { writes[0]++; }
        },new com.floatclip.share.ShareWire.Frame(com.floatclip.share.ShareWire.TEXT,new java.util.UUID(0,0),new byte[]{1}));
        yes(writes[0]==1);
        System.out.println("PASS LAN pairing: "+checks+" checks; 64-bit verification fingerprint matches C#; subnet boundaries");
    }
}
