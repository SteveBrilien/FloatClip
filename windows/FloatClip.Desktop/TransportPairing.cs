using System;
using System.IO;
using System.Security.Cryptography;
using System.Text;

namespace FloatClip.Share;

/** One-time app pairing over an OS-authenticated/encrypted RFCOMM link. */
public static class TransportPairing
{
    public const int Magic=0x46434250,NonceBytes=32,TokenBytes=32;
    static readonly byte[] Context=Encoding.ASCII.GetBytes("FloatClip-Bluetooth-Pair-v1");

    public static byte[] EncodeClientHello(byte[] nonce)
    {
        if(nonce.Length!=NonceBytes)throw new ArgumentException("Invalid pairing nonce");
        using var m=new MemoryStream();Wire.WriteInt(m,Magic,4);m.Write(nonce);return m.ToArray();
    }
    public static byte[] ReadClientHello(Stream stream)
    {
        if(Wire.ReadInt(stream,4)!=Magic)throw new IOException("不支持的蓝牙配对协议");
        return Wire.ReadBytes(stream,NonceBytes);
    }
    public static string Fingerprint(byte[] clientNonce,byte[] serverNonce)
    {
        if(clientNonce.Length!=NonceBytes||serverNonce.Length!=NonceBytes)throw new ArgumentException("Invalid pairing nonce");
        byte[] all=new byte[Context.Length+64];Context.CopyTo(all,0);clientNonce.CopyTo(all,Context.Length);serverNonce.CopyTo(all,Context.Length+32);
        byte[] hash=SHA256.HashData(all);
        string raw=Convert.ToHexString(hash.AsSpan(0,8));return raw[..4]+" "+raw[4..8]+" "+raw[8..12]+" "+raw[12..16];
    }

    public static byte[] Accept(Stream stream,Func<string,bool> verify,Action<byte[]> persist)
    {
        byte[] clientNonce=ReadClientHello(stream),serverNonce=RandomNumberGenerator.GetBytes(NonceBytes);
        stream.Write(serverNonce);stream.Flush();
        bool local=verify(Fingerprint(clientNonce,serverNonce));int remote=stream.ReadByte();
        if(!local||remote!=1){stream.WriteByte(0);stream.Flush();throw new IOException("蓝牙配对已拒绝");}
        byte[] token=RandomNumberGenerator.GetBytes(TokenBytes);stream.WriteByte(1);stream.Write(token);stream.Flush();
        if(stream.ReadByte()!=1)throw new IOException("对端未保存蓝牙授权");
        try{persist(token);}catch{stream.WriteByte(0);stream.Flush();throw;}
        stream.WriteByte(1);stream.Flush();return token;
    }

    public static byte[] Connect(Stream stream,Func<string,bool> verify,Action<byte[]> persist,Action remove)
    {
        byte[] clientNonce=RandomNumberGenerator.GetBytes(NonceBytes);stream.Write(EncodeClientHello(clientNonce));stream.Flush();
        byte[] serverNonce=Wire.ReadBytes(stream,NonceBytes);
        bool local=verify(Fingerprint(clientNonce,serverNonce));stream.WriteByte(local?(byte)1:(byte)0);stream.Flush();
        if(!local)throw new IOException("蓝牙配对已拒绝");
        if(stream.ReadByte()!=1)throw new IOException("对端拒绝蓝牙配对");
        byte[] token=Wire.ReadBytes(stream,TokenBytes);
        try{persist(token);stream.WriteByte(1);stream.Flush();}catch{stream.WriteByte(0);stream.Flush();throw;}
        if(stream.ReadByte()!=1){try{remove();}catch{}throw new IOException("对端未能保存蓝牙授权");}
        return token;
    }
}
