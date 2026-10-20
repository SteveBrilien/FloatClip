using System;
using System.Buffers.Binary;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;

namespace FloatClip.Share;

/** Authenticated encryption for short-lived trusted-device transports such as Bluetooth RFCOMM. */
public static class TransportCrypto
{
    public const int Magic=0x46434231,NonceBytes=32,TokenBytes=32,ProofBytes=32,MaxRecord=70000;
    const int C2S=0x43325331,S2C=0x53324331;
    static readonly byte[] Context=Encoding.ASCII.GetBytes("FloatClip-BT-v1");

    public sealed record ClientHello(byte[] Relation,byte[] Nonce);
    public sealed record Material(byte[] ClientToServer,byte[] ServerToClient,byte[] ClientProof,byte[] ServerProof);

    public static byte[] Relation(byte[] token){Require(token,TokenBytes,"token");return SHA256.HashData(token);}
    public static byte[] EncodeClientHello(byte[] token,byte[] nonce)
    {
        Require(token,TokenBytes,"token");Require(nonce,NonceBytes,"nonce");
        using var m=new MemoryStream(68);WriteInt(m,Magic,4);m.Write(Relation(token));m.Write(nonce);return m.ToArray();
    }
    public static ClientHello ReadClientHello(Stream stream)
    {
        if(ReadInt(stream,4)!=Magic)throw new IOException("不支持的蓝牙传输");
        return new(ReadBytes(stream,32),ReadBytes(stream,32));
    }
    public static Material Derive(byte[] token,byte[] clientNonce,byte[] serverNonce)
    {
        Require(token,TokenBytes,"token");Require(clientNonce,NonceBytes,"client nonce");Require(serverNonce,NonceBytes,"server nonce");
        byte[] salt=SHA256.HashData(Join(Context,clientNonce,serverNonce));
        byte[] prk=Hmac(salt,token);
        return new(Expand(prk,"client-to-server"),Expand(prk,"server-to-client"),
            Hmac(prk,Join(Encoding.ASCII.GetBytes("client-proof"),clientNonce,serverNonce)),
            Hmac(prk,Join(Encoding.ASCII.GetBytes("server-proof"),clientNonce,serverNonce)));
    }
    static byte[] Expand(byte[] prk,string label)=>Hmac(prk,Join(Context,Encoding.ASCII.GetBytes(label),new byte[]{1}));
    static byte[] Hmac(byte[] key,byte[] data)=>HMACSHA256.HashData(key,data);
    public static bool Equal(byte[]? a,byte[]? b)=>a!=null&&b!=null&&CryptographicOperations.FixedTimeEquals(a,b);
    static byte[] Join(params byte[][] parts){var result=new byte[parts.Sum(p=>p.Length)];int at=0;foreach(var p in parts){p.CopyTo(result,at);at+=p.Length;}return result;}
    static void Require(byte[]? value,int length,string label){if(value==null||value.Length!=length)throw new ArgumentException("Invalid "+label);}

    public sealed class Records
    {
        readonly byte[] sendKey,receiveKey;readonly int sendPrefix,receivePrefix;
        long sendCounter,receiveCounter;
        Records(byte[] sendKey,byte[] receiveKey,int sendPrefix,int receivePrefix){this.sendKey=(byte[])sendKey.Clone();this.receiveKey=(byte[])receiveKey.Clone();this.sendPrefix=sendPrefix;this.receivePrefix=receivePrefix;}
        public static Records Client(Material m)=>new(m.ClientToServer,m.ServerToClient,C2S,S2C);
        public static Records Server(Material m)=>new(m.ServerToClient,m.ClientToServer,S2C,C2S);

        public byte[] Seal(byte[] plain)
        {
            if(plain.Length>MaxRecord)throw new IOException("安全记录过大");if(sendCounter==long.MaxValue)throw new IOException("安全记录计数耗尽");
            long counter=sendCounter;byte[] header=Header(counter,plain.Length),nonce=Nonce(sendPrefix,counter),cipher=new byte[plain.Length],tag=new byte[16];
            using(var aes=new AesGcm(sendKey,16))aes.Encrypt(nonce,plain,cipher,tag,Aad(sendPrefix,header));
            sendCounter++;return Join(header,cipher,tag);
        }
        public byte[] Open(byte[] record)
        {
            if(record.Length<28)throw new IOException("安全记录不完整");
            long counter=BinaryPrimitives.ReadInt64BigEndian(record.AsSpan(0,8));int length=BinaryPrimitives.ReadInt32BigEndian(record.AsSpan(8,4));
            if(counter!=receiveCounter||length<0||length>MaxRecord||record.Length!=12+length+16)throw new IOException("安全记录顺序无效");
            byte[] plain=new byte[length];
            try{using var aes=new AesGcm(receiveKey,16);aes.Decrypt(Nonce(receivePrefix,counter),record.AsSpan(12,length),record.AsSpan(12+length,16),plain,Aad(receivePrefix,record[..12]));}
            catch(CryptographicException e){throw new IOException("安全记录认证失败",e);}
            receiveCounter++;return plain;
        }
        public void Write(Stream stream,byte[] plain){byte[] record=Seal(plain);stream.Write(record);stream.Flush();}
        public byte[]? Read(Stream stream)
        {
            int first=stream.ReadByte();if(first<0)return null;byte[] header=new byte[12];header[0]=(byte)first;ReadExact(stream,header,1,11);
            long counter=BinaryPrimitives.ReadInt64BigEndian(header.AsSpan(0,8));int length=BinaryPrimitives.ReadInt32BigEndian(header.AsSpan(8,4));
            if(counter!=receiveCounter||length<0||length>MaxRecord)throw new IOException("安全记录顺序无效");
            byte[] record=new byte[12+length+16];header.CopyTo(record,0);ReadExact(stream,record,12,length+16);return Open(record);
        }
        static byte[] Header(long counter,int length){byte[] b=new byte[12];BinaryPrimitives.WriteInt64BigEndian(b.AsSpan(0,8),counter);BinaryPrimitives.WriteInt32BigEndian(b.AsSpan(8,4),length);return b;}
        static byte[] Nonce(int prefix,long counter){byte[] b=new byte[12];BinaryPrimitives.WriteInt32BigEndian(b.AsSpan(0,4),prefix);BinaryPrimitives.WriteInt64BigEndian(b.AsSpan(4,8),counter);return b;}
        static byte[] Aad(int prefix,byte[] header){byte[] b=new byte[20];BinaryPrimitives.WriteInt32BigEndian(b.AsSpan(0,4),Magic);BinaryPrimitives.WriteInt32BigEndian(b.AsSpan(4,4),prefix);header.CopyTo(b,8);return b;}
    }

    public sealed record SecureStream(RecordStream Stream,string RelationKey);

    public static SecureStream Accept(Stream raw,Func<string,byte[]?> tokenLookup)
    {
        var hello=ReadClientHello(raw);string relation=Convert.ToHexString(hello.Relation).ToLowerInvariant();
        byte[]? token=tokenLookup(relation);
        if(token==null||token.Length!=TokenBytes||!Equal(Relation(token),hello.Relation))
        {
            raw.WriteByte(0);raw.Flush();throw new IOException("未知的受信设备关系");
        }
        byte[] serverNonce=RandomNumberGenerator.GetBytes(NonceBytes);var material=Derive(token,hello.Nonce,serverNonce);
        raw.WriteByte(1);raw.Write(serverNonce);raw.Write(material.ServerProof);raw.Flush();
        byte[] proof=ReadBytes(raw,ProofBytes);if(!Equal(proof,material.ClientProof))throw new IOException("受信设备客户端证明失败");
        return new(new RecordStream(raw,Records.Server(material)),relation);
    }

    public static SecureStream Connect(Stream raw,byte[] token)
    {
        Require(token,TokenBytes,"token");byte[] clientNonce=RandomNumberGenerator.GetBytes(NonceBytes);
        raw.Write(EncodeClientHello(token,clientNonce));raw.Flush();
        int status=raw.ReadByte();if(status!=1)throw new IOException(status<0?"受信设备连接已关闭":"受信设备关系被拒绝");
        byte[] serverNonce=ReadBytes(raw,NonceBytes),serverProof=ReadBytes(raw,ProofBytes);var material=Derive(token,clientNonce,serverNonce);
        if(!Equal(serverProof,material.ServerProof))throw new IOException("受信设备服务端证明失败");
        raw.Write(material.ClientProof);raw.Flush();
        return new(new RecordStream(raw,Records.Client(material)),Convert.ToHexString(Relation(token)).ToLowerInvariant());
    }

    static void WriteInt(Stream s,long value,int count){for(int i=count-1;i>=0;i--)s.WriteByte((byte)((ulong)value>>(i*8)));}
    static long ReadInt(Stream s,int count){ulong value=0;for(int i=0;i<count;i++){int b=s.ReadByte();if(b<0)throw new EndOfStreamException();value=(value<<8)|(uint)b;}return unchecked((long)value);}
    static byte[] ReadBytes(Stream s,int count){byte[] b=new byte[count];ReadExact(s,b,0,count);return b;}
    static void ReadExact(Stream s,byte[] b,int offset,int count){while(count>0){int n=s.Read(b,offset,count);if(n<=0)throw new EndOfStreamException();offset+=n;count-=n;}}

    public sealed class RecordStream:Stream
    {
        readonly Stream raw;readonly Records records;readonly object writeGate=new();
        MemoryStream current=new(Array.Empty<byte>()),pending=new();bool closed;
        public RecordStream(Stream raw,Records records){this.raw=raw;this.records=records;}
        public override bool CanRead=>!closed&&raw.CanRead;public override bool CanSeek=>false;public override bool CanWrite=>!closed&&raw.CanWrite;
        public override long Length=>throw new NotSupportedException();public override long Position{get=>throw new NotSupportedException();set=>throw new NotSupportedException();}
        public override int Read(byte[] buffer,int offset,int count)
        {
            if(closed)throw new ObjectDisposedException(nameof(RecordStream));if(count==0)return 0;
            while(current.Position>=current.Length){byte[]? plain=records.Read(raw);if(plain==null)return 0;current.Dispose();current=new MemoryStream(plain,false);}
            return current.Read(buffer,offset,count);
        }
        public override void Write(byte[] buffer,int offset,int count)
        {
            lock(writeGate){if(closed)throw new ObjectDisposedException(nameof(RecordStream));if(pending.Length+count>MaxRecord)throw new IOException("安全记录过大");pending.Write(buffer,offset,count);}
        }
        public override void Flush()
        {
            lock(writeGate)
            {
                if(closed)throw new ObjectDisposedException(nameof(RecordStream));
                if(pending.Length==0){raw.Flush();return;}records.Write(raw,pending.ToArray());pending.SetLength(0);
            }
        }
        protected override void Dispose(bool disposing)
        {
            if(!disposing||closed){base.Dispose(disposing);return;}
            lock(writeGate){if(closed)return;try{Flush();}finally{closed=true;current.Dispose();pending.Dispose();raw.Dispose();}}
            base.Dispose(disposing);
        }
        public override long Seek(long offset,SeekOrigin origin)=>throw new NotSupportedException();
        public override void SetLength(long value)=>throw new NotSupportedException();
    }

}
