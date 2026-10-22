using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using FloatClip.Share;

namespace FloatClip.Desktop;

public sealed record SharedFileItem(string Peer,string Id,string Version,string Name,long Size,long Modified,long Created,long Shared)
{
    public string LocalPath{get;set;}="";
    public bool Kept{get;set;}
    public bool Online{get;set;}
    public long Done{get;set;}
    public string Error{get;set;}="";
    public string Key=>Peer+":"+Id;
}

/** FCP4-only metadata and pull adapter. The established clipboard envelope is untouched. */
public sealed class FileCatalog
{
    public const int MaximumShared=64;
    sealed class Pending(SharedFileItem item){public readonly Guid Id=Guid.NewGuid();public readonly SharedFileItem Item=item;public readonly TaskCompletionSource<string> Completion=new(TaskCreationOptions.RunContinuationsAsynchronously);}
    sealed class Link(Session session){public readonly Session Session=session;public Pending? Pending;public Guid? Serving;}
    readonly object gate=new();readonly Dictionary<string,Link> links=new();readonly Dictionary<string,SharedFileItem> rows=new();
    readonly Func<string,bool> allowed;readonly Func<string,string> describe;readonly string path,cache;
    public FileShelfStore Store{get;}
    public string? Warning{get;private set;}
    public event Action? Changed;
    public FileCatalog(string root,string cache,Func<string,bool> allowed,Func<string,string> describe)
    {
        this.allowed=allowed;this.describe=describe;this.cache=Path.GetFullPath(cache);path=Path.Combine(root,"remote-file-catalog.json");Store=new FileShelfStore(Path.Combine(root,"file-shelf.json"));
        if(System.IO.File.Exists(path))try{if(new FileInfo(path).Length>1048576)throw new IOException("文件目录过大");foreach(var row in JsonSerializer.Deserialize<SharedFileItem[]>(System.IO.File.ReadAllText(path))??Array.Empty<SharedFileItem>()){Validate(row);row.Online=false;row.Done=0;if(!Cached(row.LocalPath))row.LocalPath="";rows[row.Key]=row;}}catch(Exception e)when(e is IOException or JsonException or ArgumentException){rows.Clear();try{System.IO.File.Copy(path,path+".recovery-"+Guid.NewGuid().ToString("N"));}catch(IOException){}catch(UnauthorizedAccessException){}}
    }
    public string PeerName(string key)=>describe(key);
    public SharedFileItem[] Snapshot(){lock(gate)return rows.Values.OrderByDescending(r=>r.Shared).ToArray();}
    public bool IsLocal(SharedFileItem row)=>Cached(row.LocalPath);
    bool Cached(string value){try{string p=Path.GetFullPath(value);return p.StartsWith(cache+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase)&&System.IO.File.Exists(p)&&(System.IO.File.GetAttributes(p)&FileAttributes.ReparsePoint)==0;}catch{return false;}}
    public static string LocalId(LocalShelfEntry f)=>Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(Path.GetFullPath(f.Path).ToLowerInvariant()))).ToLowerInvariant();
    public static string LocalVersion(LocalShelfEntry f)=>Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(f.Size+":"+f.LastWriteUtcTicks))).ToLowerInvariant();
    static long Millis(long ticks)=>ticks>621355968000000000?(ticks-621355968000000000)/10000:0;
    static void Validate(SharedFileItem f){if(!Regex.IsMatch(f.Id,"^(?:[a-f0-9]{32}|[a-f0-9]{64})$")||!Regex.IsMatch(f.Version,"^[a-f0-9]{64}$")||f.Size<0||f.Size>Wire.MaxFile||f.Modified<0||f.Created<0||f.Shared<0)throw new IOException("文件目录无效");Wire.SafeName(f.Name);}
    void Notify(){try{Changed?.Invoke();}catch{}}
    void Save(){byte[] data=JsonSerializer.SerializeToUtf8Bytes(rows.Values);if(data.Length>1048576)throw new IOException("文件目录已满");string temp=path+".tmp";System.IO.File.WriteAllBytes(temp,data);System.IO.File.Move(temp,path,true);}
    // Names are already validated as plain file names; UTF-8 avoids bloating a CJK
    // shelf beyond the frame limit through JSON's optional ASCII escaping.
    static byte[] Encode(object value)=>JsonSerializer.SerializeToUtf8Bytes(value,new JsonSerializerOptions{Encoder=System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping});
    public void Attach(string peer,Session session)
    {
        if(!session.CatalogEnabled)return;var link=new Link(session);lock(gate)links[peer]=link;
        session.Catalog=data=>{if(allowed(peer))Handle(peer,link,data);};
        var legacy=session.Offered;
        session.Offered=offer=>{
            Pending? pending;lock(gate)pending=link.Pending;
            if(pending?.Id!=offer.Id){legacy?.Invoke(offer);return;}
            if(!allowed(peer)||offer.Name!=pending.Item.Name||offer.Size!=pending.Item.Size){session.Reject(offer.Id);Fail(link,"文件与所选条目不一致");return;}
            try{session.Accept(offer.Id);}catch(Exception e){Fail(link,e.Message);session.Dispose();}
        };
        session.Progress+=(name,done,total,outgoing)=>{if(outgoing)return;lock(gate){if(link.Pending is not Pending p)return;p.Item.Done=done;}Notify();};
        session.File+=(offer,local,outgoing)=>{lock(gate){if(outgoing){if(link.Serving==offer.Id)link.Serving=null;return;}if(link.Pending is not Pending p||p.Id!=offer.Id)return;if(!Cached(local)){Fail(link,"接收文件不可用");return;}var target=rows.TryGetValue(p.Item.Key,out var current)&&current.Version==p.Item.Version?current:p.Item;target.LocalPath=local;target.Done=offer.Size;target.Error="";rows[target.Key]=target;Save();link.Pending=null;p.Completion.TrySetResult(local);}Notify();};
        session.FileFailed+=message=>{Guid? serving;lock(gate){serving=link.Serving;link.Serving=null;}if(serving.HasValue)SendError(link,serving.Value,message);Fail(link,message);};
        session.Closed+=()=>{lock(gate){if(links.TryGetValue(peer,out var current)&&ReferenceEquals(current,link)){links.Remove(peer);foreach(var row in rows.Values.Where(r=>r.Peer==peer))row.Online=false;}}Fail(link,"设备已离线，可重连后重试");Notify();};
        Publish(peer);Notify();
    }
    void Fail(Link link,string error){lock(gate){if(link.Pending is not Pending p)return;p.Item.Error=error;link.Pending=null;p.Completion.TrySetException(new IOException(error));}Notify();}
    void SendError(Link link,Guid request,string message){try{link.Session.SendCatalog(Encode(new{v=1,op="error",request=request.ToString("N"),message=message.Length>160?message[..160]:message}));}catch{}}
    public void Publish(string? only=null)
    {
        LocalShelfEntry[] local;
        try{local=Store.Load();Warning=null;}catch(Exception e)when(e is IOException or UnauthorizedAccessException){Warning="共享文件清单无法读取，已保留原文件；文字同步仍可继续。";Notify();return;}
        var entries=local.Where(f=>f.IsAvailable).Take(MaximumShared).Select(f=>new{id=LocalId(f),version=LocalVersion(f),name=f.Name,size=f.Size,modified=Millis(f.LastWriteUtcTicks),created=Millis(f.CreationUtcTicks),shared=Millis(f.AddedUtcTicks)}).ToArray();
        byte[] data=Encode(new{v=1,op="list",files=entries});if(data.Length>65536)throw new IOException("文件清单过大");
        KeyValuePair<string,Link>[] targets;lock(gate)targets=links.ToArray();foreach(var pair in targets)if((only==null||pair.Key==only)&&allowed(pair.Key))try{pair.Value.Session.SendCatalog(data);}catch{pair.Value.Session.Dispose();}Notify();
    }
    void Handle(string peer,Link link,byte[] data)
    {
        using var doc=JsonDocument.Parse(data);var root=doc.RootElement;if(root.GetProperty("v").GetInt32()!=1)throw new IOException("文件协议版本无效");string op=root.GetProperty("op").GetString()??"";
        if(op=="list"){
            var array=root.GetProperty("files");if(array.ValueKind!=JsonValueKind.Array||array.GetArrayLength()>MaximumShared)throw new IOException("文件目录过大");var incoming=new List<SharedFileItem>();
            foreach(var f in array.EnumerateArray()){var item=new SharedFileItem(peer,f.GetProperty("id").GetString()!,f.GetProperty("version").GetString()!,f.GetProperty("name").GetString()!,f.GetProperty("size").GetInt64(),f.GetProperty("modified").GetInt64(),f.GetProperty("created").GetInt64(),f.GetProperty("shared").GetInt64()){Online=true};Validate(item);if(incoming.Any(r=>r.Id==item.Id))throw new IOException("重复文件标识");incoming.Add(item);}
            lock(gate){foreach(var f in rows.Values.Where(f=>f.Peer==peer))f.Online=false;foreach(var f in incoming){if(rows.TryGetValue(f.Key,out var previous)){f.Kept=previous.Kept;if(previous.Version==f.Version&&Cached(previous.LocalPath))f.LocalPath=previous.LocalPath;}else if(rows.Count>=512){Warning="文件记录已满，请清理后重新连接以加载新文件。";continue;}rows[f.Key]=f;}Save();}Notify();return;
        }
        if(op=="get"){
            Guid request=Guid.ParseExact(root.GetProperty("request").GetString()!,"N");string id=root.GetProperty("id").GetString()!,version=root.GetProperty("version").GetString()!;
            _=Task.Run(()=>{try{if(!allowed(peer))throw new IOException("设备共享已暂停");var item=Store.Load().FirstOrDefault(f=>LocalId(f)==id&&LocalVersion(f)==version&&f.IsAvailable)??throw new IOException("源文件已变化或被移除，请刷新清单");lock(gate){if(link.Serving!=null)throw new IOException("设备正在传输另一个文件");link.Serving=request;}link.Session.SendFile(item.Path,request);}catch(Exception e){lock(gate)if(link.Serving==request)link.Serving=null;SendError(link,request,e is IOException?e.Message:"文件无法读取");}});return;
        }
        if(op=="error"){Guid id=Guid.ParseExact(root.GetProperty("request").GetString()!,"N");lock(gate)if(link.Pending?.Id!=id)return;Fail(link,"来源设备未能提供文件，请刷新清单后重试");return;}
        throw new IOException("文件目录操作无效");
    }
    public async Task<string> Download(SharedFileItem item,CancellationToken cancellation=default)
    {
        if(IsLocal(item))return item.LocalPath;Link link;Pending pending;
        lock(gate){if(!item.Online||!allowed(item.Peer)||!links.TryGetValue(item.Peer,out link!))throw new IOException("来源设备离线或共享已暂停");if(link.Pending!=null)throw new IOException("请等待该设备当前下载完成");pending=new Pending(item);link.Pending=pending;item.Error="";item.Done=0;}
        try{link.Session.SendCatalog(Encode(new{v=1,op="get",id=item.Id,version=item.Version,request=pending.Id.ToString("N")}));using var timeout=CancellationTokenSource.CreateLinkedTokenSource(cancellation);timeout.CancelAfter(TimeSpan.FromMinutes(3));using var registration=timeout.Token.Register(()=>{Fail(link,"文件下载已取消或超时");link.Session.Dispose();});return await pending.Completion.Task;}
        catch{lock(gate)if(link.Pending==pending)link.Pending=null;throw;}
    }
    public void Keep(SharedFileItem item,bool value){lock(gate){item.Kept=value;if(rows.TryGetValue(item.Key,out var current))current.Kept=value;Save();}Notify();}
    public void Clear(IEnumerable<SharedFileItem> items,bool records,bool contents)
    {
        lock(gate){foreach(var selected in items.ToArray()){if(!rows.TryGetValue(selected.Key,out var item)||item.Kept||item.Version!=selected.Version)continue;if(contents&&Cached(item.LocalPath))System.IO.File.Delete(item.LocalPath);if(contents){item.LocalPath="";selected.LocalPath="";}if(records)rows.Remove(item.Key);}Save();}Notify();
    }
}
