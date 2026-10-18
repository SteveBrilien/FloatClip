using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace FloatClip.Share;

public sealed record Frame(int Type, Guid Id, byte[] Data);
public sealed record Offer(Guid Id, string Name, long Size, byte[] Hash);
public static class Wire
{
    public const int Text=1,Ack=2,FileOffer=3,Accept=4,Reject=5,Chunk=6,End=7,MaxText=65536,ChunkSize=32768;
    public const long MaxFile=64L*1024*1024;
    static readonly UTF8Encoding Utf8=new(false,true);
    public static byte[] Digest(byte[] data)=>SHA256.HashData(data);
    public static string Decode(byte[] data)=>Utf8.GetString(data);
    public static string SafeName(string name)
    {
        if(Utf8.GetByteCount(name) is <1 or >240 || name is "." or ".." || name.EndsWith('.') || name.EndsWith(' ')) throw new IOException("文件名不受支持");
        foreach(char c in name) if(c<32 || "\\/:*?\"<>|".Contains(c)) throw new IOException("文件名不安全");
        string stem=name.Split('.')[0].ToUpperInvariant();
        if(System.Text.RegularExpressions.Regex.IsMatch(stem,@"^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$")) throw new IOException("保留文件名");
        return name;
    }
    public static void WriteInt(Stream s,long value,int count) { for(int i=count-1;i>=0;i--) s.WriteByte((byte)((ulong)value>>(i*8))); }
    public static long ReadInt(Stream s,int count) { ulong value=0; for(int i=0;i<count;i++){ int b=s.ReadByte(); if(b<0) throw new EndOfStreamException(); value=(value<<8)|(uint)b; } return unchecked((long)value); }
    public static byte[] ReadBytes(Stream s,int count) { byte[] data=new byte[count]; s.ReadExactly(data); return data; }
    static void Validate(int type,int size)
    {
        if(type<1 || type>7 || size<0 || size>MaxText || ((type==Ack || type==End) && size!=32) ||
           ((type==Accept || type==Reject) && size!=0) || (type==Chunk && (size<4 || size>ChunkSize+4)) ||
           (type==FileOffer && (size<43 || size>282))) throw new IOException("无效传输帧");
    }
    public static void Write(Stream s,Frame f)
    {
        Validate(f.Type,f.Data.Length); WriteInt(s,0x46435331,4); s.WriteByte(1); s.WriteByte((byte)f.Type);
        s.Write(Convert.FromHexString(f.Id.ToString("N"))); WriteInt(s,f.Data.Length,4); s.Write(Digest(f.Data)); s.Write(f.Data); s.Flush();
    }
    public static Frame? Read(Stream s)
    {
        int first=s.ReadByte(); if(first<0) return null;
        if(first!=0x46 || ReadInt(s,3)!=0x435331 || s.ReadByte()!=1) throw new IOException("协议不兼容");
        int type=s.ReadByte(); var id=Guid.ParseExact(Convert.ToHexString(ReadBytes(s,16)),"N");
        int size=checked((int)ReadInt(s,4)); Validate(type,size); byte[] hash=ReadBytes(s,32),data=ReadBytes(s,size);
        if(!CryptographicOperations.FixedTimeEquals(hash,Digest(data))) throw new IOException("数据校验失败");
        return new(type,id,data);
    }
    public static byte[] EncodeOffer(Offer offer)
    {
        SafeName(offer.Name); if(offer.Size<0 || offer.Size>MaxFile || offer.Hash.Length!=32) throw new IOException("文件最大 64 MiB");
        using var m=new MemoryStream(); byte[] name=Utf8.GetBytes(offer.Name);
        WriteInt(m,offer.Size,8); WriteInt(m,name.Length,2); m.Write(name); m.Write(offer.Hash); return m.ToArray();
    }
    public static Offer DecodeOffer(Frame frame)
    {
        using var m=new MemoryStream(frame.Data); long size=ReadInt(m,8); int n=(int)ReadInt(m,2);
        if(n<1 || n>240 || frame.Data.Length!=42+n || size<0 || size>MaxFile) throw new IOException("文件清单无效");
        return new(frame.Id,SafeName(Decode(ReadBytes(m,n))),size,ReadBytes(m,32));
    }
}

public sealed class Session : IDisposable
{
    readonly Stream stream; readonly Action closeSocket; readonly string cache;
    readonly object writing=new(),state=new(); readonly Timer expiry;
    readonly Dictionary<Guid,(byte[] Hash,string Text)> pending=new();
    readonly Dictionary<Guid,byte[]> received=new(); readonly Queue<Guid> receivedOrder=new();
    Job? outgoing; Offer? incoming; FileStream? sink; IncrementalHash? fileHash; string? part;
    long count,lastProgress,nextByte; int sequence,closed;
    sealed class Job { public required string Path,Name; public Offer? Offer; public bool Accepted,EndSent; }
    public Action<string,bool>? Text;
    public Action<Offer>? Offered;
    public Action<string,long,long,bool>? Progress;
    public Action<Offer,string,bool>? File;
    public Action<string>? Notice;
    public Action? Closed;
    public Session(Stream stream,Action closeSocket,string cache)
    {
        this.stream=stream; this.closeSocket=closeSocket; this.cache=cache; Directory.CreateDirectory(cache);
        expiry=new Timer(_=>Dispose(),null,Timeout.Infinite,Timeout.Infinite);
    }
    public void Start() { expiry.Change(TimeSpan.FromMinutes(10),Timeout.InfiniteTimeSpan); Task.Run(ReadLoop); }
    void Write(int type,Guid id,byte[] data) { lock(writing) { if(closed!=0) throw new IOException("连接已结束"); Wire.Write(stream,new(type,id,data)); } }
    public void SendText(string text)
    {
        byte[] data=Encoding.UTF8.GetBytes(text); if(data.Length is <1 or >Wire.MaxText) throw new IOException("文字最多 64 KiB");
        Guid id=Guid.NewGuid(); lock(state) { if(pending.Count>=16) throw new IOException("请等待前面的消息确认"); pending.Add(id,(Wire.Digest(data),text)); }
        try { Write(Wire.Text,id,data); } catch { Dispose(); throw; }
    }
    public void SendFile(string path)
    {
        string name=Wire.SafeName(Path.GetFileName(path)); var info=new FileInfo(path);
        if(!info.Exists || info.Length>Wire.MaxFile || (info.Attributes&FileAttributes.ReparsePoint)!=0) throw new IOException("请选择 64 MiB 内的普通文件");
        var job=new Job{Path=path,Name=name}; lock(state) { if(outgoing!=null || incoming!=null) throw new IOException("请等待当前文件完成"); outgoing=job; }
        Task.Run(()=>{
            try {
                using var source=new FileStream(path,FileMode.Open,FileAccess.Read,FileShare.Read);
                if(source.Length>Wire.MaxFile) throw new IOException();
                var offer=new Offer(Guid.NewGuid(),name,source.Length,SHA256.HashData(source));
                lock(state) { if(outgoing!=job || closed!=0) return; job.Offer=offer; }
                Progress?.Invoke(name,0,offer.Size,true); Notice?.Invoke("等待对方接收文件"); Write(Wire.FileOffer,offer.Id,Wire.EncodeOffer(offer));
            } catch { lock(state) if(outgoing==job) outgoing=null; Notice?.Invoke("文件准备失败，请重新选择"); }
        });
    }
    public void Accept(Guid id)
    {
        lock(state) {
            if(incoming?.Id!=id || sink!=null) throw new IOException("邀请已失效");
            long cached=0; foreach(var f in Directory.GetFiles(cache)) cached+=new FileInfo(f).Length;
            if(cached+incoming.Size>128L*1024*1024) throw new IOException("接收缓存已满，请断开后清理");
            if(new DriveInfo(Path.GetPathRoot(cache)!).AvailableFreeSpace<incoming.Size+1048576) throw new IOException("磁盘空间不足");
            part=Path.Combine(cache,id+".part"); sink=new(part,FileMode.CreateNew,FileAccess.Write,FileShare.None);
            fileHash=IncrementalHash.CreateHash(HashAlgorithmName.SHA256); count=0; sequence=0;
        }
        Write(Wire.Accept,id,Array.Empty<byte>());
    }
    public void Reject(Guid id)
    {
        lock(state) { if(incoming?.Id!=id || sink!=null) throw new IOException("请求已失效"); incoming=null; }
        Write(Wire.Reject,id,Array.Empty<byte>());
    }
    void Pace(int bytes)
    {
        long ticks; lock(state) { long now=System.Diagnostics.Stopwatch.GetTimestamp(); nextByte=Math.Max(now,nextByte)+(long)bytes*System.Diagnostics.Stopwatch.Frequency/(2*1024*1024); ticks=nextByte-now; }
        if(ticks>0) Thread.Sleep(TimeSpan.FromSeconds((double)ticks/System.Diagnostics.Stopwatch.Frequency));
        if(closed!=0) throw new IOException("连接已结束");
    }
    void SendBody(Job job)
    {
        try {
            Offer offer=job.Offer!; using var source=new FileStream(job.Path,FileMode.Open,FileAccess.Read,FileShare.Read);
            using var hash=IncrementalHash.CreateHash(HashAlgorithmName.SHA256); byte[] buffer=new byte[Wire.ChunkSize]; long done=0,last=0; int index=0,n;
            while((n=source.Read(buffer))!=0) {
                lock(state) if(outgoing!=job || closed!=0) return;
                done+=n; if(done>offer.Size) throw new IOException("文件已变化"); hash.AppendData(buffer,0,n); Pace(n);
                using var m=new MemoryStream(); Wire.WriteInt(m,index++,4); m.Write(buffer,0,n); Write(Wire.Chunk,offer.Id,m.ToArray());
                long now=Environment.TickCount64; if(now-last>100 || done==offer.Size) { last=now; Progress?.Invoke(offer.Name,done,offer.Size,true); }
            }
            if(done!=offer.Size || !CryptographicOperations.FixedTimeEquals(hash.GetHashAndReset(),offer.Hash)) throw new IOException("文件已变化");
            lock(state) { if(outgoing!=job) return; job.EndSent=true; }
            Write(Wire.End,offer.Id,offer.Hash);
        } catch { Notice?.Invoke("文件传输中断，请重新发送"); Dispose(); }
    }
    void ReadLoop()
    {
        try { Frame? frame; while(closed==0 && (frame=Wire.Read(stream))!=null) Handle(frame); }
        catch { if(closed==0) Notice?.Invoke("连接已结束，请重新连接"); }
        finally { Dispose(); }
    }
    void Handle(Frame f)
    {
        switch(f.Type) {
            case Wire.Text: {
                string value=Wire.Decode(f.Data); byte[] hash=Wire.Digest(f.Data); bool fresh;
                lock(state) {
                    fresh=!received.TryGetValue(f.Id,out var previous);
                    if(!fresh && !CryptographicOperations.FixedTimeEquals(previous!,hash)) throw new IOException("重复事件 ID");
                    if(fresh) { received.Add(f.Id,hash); receivedOrder.Enqueue(f.Id); if(received.Count>128) received.Remove(receivedOrder.Dequeue()); }
                }
                Write(Wire.Ack,f.Id,hash); if(fresh) Text?.Invoke(value,false); break;
            }
            case Wire.Ack: {
                string? text=null; Job? done=null;
                lock(state) {
                    if(pending.Remove(f.Id,out var expected)) { if(!CryptographicOperations.FixedTimeEquals(expected.Hash,f.Data)) throw new IOException("确认不匹配"); text=expected.Text; }
                    else if(outgoing?.Offer?.Id==f.Id) { if(!outgoing.EndSent || !CryptographicOperations.FixedTimeEquals(outgoing.Offer.Hash,f.Data)) throw new IOException("文件确认不匹配"); done=outgoing; outgoing=null; }
                    else throw new IOException("意外确认");
                }
                if(text!=null) Text?.Invoke(text,true); if(done!=null) File?.Invoke(done.Offer!,done.Path,true); break;
            }
            case Wire.FileOffer: {
                Offer offer=Wire.DecodeOffer(f); bool busy; lock(state) { busy=incoming!=null || outgoing!=null; if(!busy) incoming=offer; }
                if(busy) Write(Wire.Reject,f.Id,Array.Empty<byte>()); else Offered?.Invoke(offer); break;
            }
            case Wire.Accept: case Wire.Reject: {
                Job job; lock(state) {
                    job=outgoing ?? throw new IOException("意外文件决策");
                    if(job.Offer?.Id!=f.Id || job.Accepted) throw new IOException("意外文件决策");
                    if(f.Type==Wire.Reject) outgoing=null; else job.Accepted=true;
                }
                if(f.Type==Wire.Accept) Task.Run(()=>SendBody(job)); else Notice?.Invoke("对方未接收文件"); break;
            }
            case Wire.Chunk: {
                Pace(f.Data.Length-4);
                lock(state) {
                    if(incoming?.Id!=f.Id || sink==null || count+f.Data.Length-4>incoming.Size) throw new IOException("未接收的文件正文");
                    using var m=new MemoryStream(f.Data); if(Wire.ReadInt(m,4)!=sequence++) throw new IOException("文件分块顺序错误");
                    sink.Write(f.Data,4,f.Data.Length-4); fileHash!.AppendData(f.Data,4,f.Data.Length-4); count+=f.Data.Length-4;
                    long now=Environment.TickCount64; if(now-lastProgress>100 || count==incoming.Size) { lastProgress=now; Progress?.Invoke(incoming.Name,count,incoming.Size,false); }
                }
                break;
            }
            case Wire.End: {
                Offer done; string target;
                lock(state) {
                    if(incoming?.Id!=f.Id || sink==null || count!=incoming.Size || !CryptographicOperations.FixedTimeEquals(f.Data,incoming.Hash) ||
                       !CryptographicOperations.FixedTimeEquals(fileHash!.GetHashAndReset(),incoming.Hash)) throw new IOException("文件不完整");
                    sink.Flush(true); sink.Dispose(); sink=null; fileHash.Dispose(); fileHash=null;
                    done=incoming; target=Path.Combine(cache,done.Id+".ready"); System.IO.File.Move(part!,target); part=null; incoming=null;
                }
                Write(Wire.Ack,f.Id,done.Hash); File?.Invoke(done,target,false); break;
            }
        }
    }
    public void Dispose()
    {
        if(Interlocked.Exchange(ref closed,1)!=0) return;
        closeSocket(); expiry.Dispose();
        lock(state) { sink?.Dispose(); sink=null; fileHash?.Dispose(); fileHash=null; if(part!=null) { try { System.IO.File.Delete(part); } catch { } part=null; } incoming=null; outgoing=null; pending.Clear(); }
        Closed?.Invoke();
    }
}

public static class LanClient
{
    public static Session Connect(string invitation,string cache)
    {
        if(invitation.Length>400 || !Uri.TryCreate(invitation.Trim(),UriKind.Absolute,out var uri) || uri.Scheme!="floatclip" || uri.Host!="v1") throw new IOException("邀请格式不正确");
        string[] fields=uri.AbsolutePath.Trim('/').Split('/');
        if(fields.Length!=4 || !IPAddress.TryParse(fields[0],out var ip) || ip.AddressFamily!=AddressFamily.InterNetwork || !int.TryParse(fields[1],out int port) || port<1024 || port>65535) throw new IOException("邀请地址无效");
        byte[] address=ip.GetAddressBytes(); if(!(address[0]==10 || (address[0]==172 && address[1]>=16 && address[1]<=31) || (address[0]==192 && address[1]==168))) throw new IOException("仅支持局域网地址");
        if(fields[2].Length!=64 || fields[3].Length!=64) throw new IOException("邀请已损坏");
        byte[] pin,secret;
        try { pin=Convert.FromHexString(fields[2]); secret=Convert.FromHexString(fields[3]); } catch { throw new IOException("邀请已损坏"); }
        var client=new TcpClient(AddressFamily.InterNetwork){NoDelay=true};
        var deadline=new Timer(_=>client.Dispose(),null,15000,Timeout.Infinite);
        try {
            client.Connect(ip,port);
            var tls=new SslStream(client.GetStream(),false,(_,cert,_,_)=>cert!=null && CryptographicOperations.FixedTimeEquals(SHA256.HashData(cert.GetRawCertData()),pin));
            tls.ReadTimeout=10000; tls.WriteTimeout=10000; tls.AuthenticateAsClient("FloatClip LAN P0",null,SslProtocols.Tls12,false);
            Wire.WriteInt(tls,0x46435331,4); tls.Write(secret); tls.Flush(); Array.Clear(secret);
            if(tls.ReadByte()!=1) throw new IOException("邀请未通过验证");
            tls.ReadTimeout=180000; deadline.Dispose();
            return new Session(tls,()=>{ try { client.Dispose(); } catch { } },cache);
        } catch { client.Dispose(); throw new IOException("连接失败：请检查网络或重新生成邀请"); }
        finally { deadline.Dispose(); Array.Clear(secret); }
    }
}
