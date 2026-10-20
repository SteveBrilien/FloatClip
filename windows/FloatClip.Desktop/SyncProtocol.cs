using System;
using System.IO;
using System.Text;

namespace FloatClip.Share;

public sealed record SyncDevice(Guid Id,string Name,string Platform);
public sealed record SyncEntry(Guid Id,SyncDevice Source,long Clock,long CreatedAt,string Text);
public sealed record SyncHello(SyncDevice Device,Guid Epoch,bool Send,bool Receive);

/** Versioned binary payloads shared with Android, within the existing authenticated TLS framing. */
public static class SyncProtocol
{
    public const int Clip=8,Hello=9,Ping=10,Pong=11,Ack=12,MaximumBytes=64000;
    public const long MaximumClock=9007199254740990L,RetryTtl=300000;
    static readonly UTF8Encoding Utf8=new(false,true);

    public static void ValidateDevice(SyncDevice d)
    {
        if(d.Id==Guid.Empty||string.IsNullOrWhiteSpace(d.Name)||d.Name.Length>80||d.Platform is not("android" or "windows" or "ipad" or "web"))throw new IOException("设备身份无效");
    }
    public static void ValidateEntry(SyncEntry e)
    {
        ValidateDevice(e.Source);
        if(e.Id==Guid.Empty||e.Clock<1||e.Clock>MaximumClock||e.CreatedAt<0||string.IsNullOrWhiteSpace(e.Text)||Utf8.GetByteCount(e.Text)>MaximumBytes)throw new IOException("剪贴板事件无效");
    }
    static void Uuid(Stream stream,Guid id)=>stream.Write(Convert.FromHexString(id.ToString("N")));
    static Guid Uuid(Stream stream)=>Guid.ParseExact(Convert.ToHexString(Wire.ReadBytes(stream,16)),"N");
    static void Text(Stream stream,string text){byte[] raw=Utf8.GetBytes(text);Wire.WriteInt(stream,raw.Length,2);stream.Write(raw);}
    static string Text(Stream stream,int max){int n=(int)Wire.ReadInt(stream,2);if(n>max)throw new IOException("字符串过长");return Utf8.GetString(Wire.ReadBytes(stream,n));}
    static void Device(Stream stream,SyncDevice d){ValidateDevice(d);Uuid(stream,d.Id);Text(stream,d.Name);Text(stream,d.Platform);}
    static SyncDevice Device(Stream stream){var d=new SyncDevice(Uuid(stream),Text(stream,320),Text(stream,16));ValidateDevice(d);return d;}
    static void End(Stream s){if(s.Position!=s.Length)throw new IOException("存在多余数据");}
    public static byte[] EncodeHello(SyncHello hello)
    {
        if(hello.Epoch==Guid.Empty)throw new IOException("接收授权无效");
        using var s=new MemoryStream();s.WriteByte(1);s.WriteByte((byte)((hello.Send?1:0)|(hello.Receive?2:0)));Uuid(s,hello.Epoch);Device(s,hello.Device);return s.ToArray();
    }
    public static SyncHello DecodeHello(Frame f)
    {
        if(f.Id!=Guid.Empty||f.Data.Length>512)throw new IOException("同步握手无效");
        using var s=new MemoryStream(f.Data);if(s.ReadByte()!=1)throw new IOException("同步协议不支持");int flags=s.ReadByte();if(flags<0||(flags&~3)!=0)throw new IOException("同步开关无效");
        Guid epoch=Uuid(s);var device=Device(s);End(s);if(epoch==Guid.Empty)throw new IOException("接收授权无效");return new(device,epoch,(flags&1)!=0,(flags&2)!=0);
    }
    public static byte[] EncodeEntry(SyncEntry entry,Guid epoch)
    {
        ValidateEntry(entry);if(epoch==Guid.Empty)throw new IOException("接收授权无效");
        using var s=new MemoryStream();s.WriteByte(1);Uuid(s,entry.Source.Id);Wire.WriteInt(s,entry.Clock,8);Wire.WriteInt(s,entry.CreatedAt,8);Uuid(s,epoch);
        byte[] raw=Utf8.GetBytes(entry.Text);Wire.WriteInt(s,raw.Length,4);s.Write(raw);return s.ToArray();
    }
    public static (SyncEntry Entry,Guid Epoch) DecodeEntry(Frame frame,SyncDevice source)
    {
        using var s=new MemoryStream(frame.Data);if(s.ReadByte()!=1||Uuid(s)!=source.Id)throw new IOException("剪贴板来源不符");
        long clock=Wire.ReadInt(s,8),created=Wire.ReadInt(s,8);Guid epoch=Uuid(s);int n=checked((int)Wire.ReadInt(s,4));
        if(n<1||n>MaximumBytes||s.Length-s.Position!=n||epoch==Guid.Empty)throw new IOException("剪贴板长度或授权无效");
        var entry=new SyncEntry(frame.Id,source,clock,created,Utf8.GetString(Wire.ReadBytes(s,n)));ValidateEntry(entry);return(entry,epoch);
    }
    public static int Compare(long ac,Guid ai,long bc,Guid bi){int c=ac.CompareTo(bc);return c!=0?c:string.CompareOrdinal(ai.ToString(),bi.ToString());}
}
