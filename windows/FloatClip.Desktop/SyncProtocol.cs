using System;
using System.IO;
using System.Text;

namespace FloatClip.Share;

public sealed record SyncDevice(Guid Id,string Name,string Platform);
public sealed record SyncEntry(Guid Id,SyncDevice Source,long Clock,long CreatedAt,string Text);
public sealed record SyncHello(SyncDevice Device,Guid Epoch,bool Send,bool Receive);

public sealed record ClipboardImageMeta(Guid Id,long CreatedAt,int Width,int Height);
public static class ClipboardImageTransfer
{
    public const long MaximumBytes=8L*1024*1024;
    public const int MaximumEdge=16384;
    public const long MaximumPixels=64000000L;
    static readonly System.Text.RegularExpressions.Regex NamePattern=new(
        @"^floatclip-clip-v1_([0-9a-f]{32})_([0-9]{1,16})_([0-9]{1,5})x([0-9]{1,5})\.png$",
        System.Text.RegularExpressions.RegexOptions.CultureInvariant);

    static void Validate(Guid id,long createdAt,int width,int height)
    {
        if(id==Guid.Empty||createdAt<0||width<1||height<1||width>MaximumEdge||height>MaximumEdge||(long)width*height>MaximumPixels)
            throw new IOException("剪贴板图片元数据无效");
    }
    public static string Name(Guid id,long createdAt,int width,int height)
    {
        Validate(id,createdAt,width,height);
        return $"floatclip-clip-v1_{id:N}_{createdAt}_{width}x{height}.png";
    }
    public static ClipboardImageMeta Parse(string name)
    {
        var match=NamePattern.Match(name??"");if(!match.Success)throw new IOException("不是剪贴板图片传输");
        if(!Guid.TryParseExact(match.Groups[1].Value,"N",out var id)||
           !long.TryParse(match.Groups[2].Value,out long createdAt)||
           !int.TryParse(match.Groups[3].Value,out int width)||
           !int.TryParse(match.Groups[4].Value,out int height))throw new IOException("剪贴板图片元数据无效");
        Validate(id,createdAt,width,height);return new(id,createdAt,width,height);
    }
    public static void ValidatePng(byte[] bytes,ClipboardImageMeta meta)
    {
        if(bytes==null||bytes.Length<33||bytes.LongLength>MaximumBytes)throw new IOException("剪贴板图片大小无效");
        ReadOnlySpan<byte> signature=stackalloc byte[]{137,80,78,71,13,10,26,10};
        if(!bytes.AsSpan(0,8).SequenceEqual(signature))throw new IOException("剪贴板图片不是 PNG");
        static int Big(byte[] b,int o)=>checked((int)(((uint)b[o]<<24)|((uint)b[o+1]<<16)|((uint)b[o+2]<<8)|b[o+3]));
        if(Big(bytes,8)!=13||bytes[12]!='I'||bytes[13]!='H'||bytes[14]!='D'||bytes[15]!='R')throw new IOException("PNG 头无效");
        int width=Big(bytes,16),height=Big(bytes,20);Validate(meta.Id,meta.CreatedAt,width,height);
        if(width!=meta.Width||height!=meta.Height)throw new IOException("剪贴板图片尺寸已变化");
    }
    public static bool TryParse(string name,out ClipboardImageMeta? meta)
    {
        try{meta=Parse(name);return true;}catch{meta=null;return false;}
    }
}

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
