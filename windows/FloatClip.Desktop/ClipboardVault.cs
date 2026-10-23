using System;
using System.IO;
using System.Text;
using System.Text.Json.Nodes;
using System.Security.Cryptography;
using System.Threading;
using System.Linq;
using System.Collections.Generic;
using System.Buffers.Binary;

namespace FloatClip.Desktop;

/** Local clipboard storage. Injected protection is used only by cross-platform model checks. */
public sealed class ClipboardVault
{
    readonly string directory,path,mutexName,imageDirectory;
    readonly Func<byte[],byte[]> protect,unprotect;
    public const int MaximumBytes=16*1024*1024;
    public const int MaximumImageBytes=8*1024*1024;
    public const long MaximumImageStorageBytes=64L*1024*1024;
    public const long MaximumBackupBytes=128L*1024*1024;
    static readonly uint[] PngCrcTable=CreatePngCrcTable();
    static uint[] CreatePngCrcTable(){var table=new uint[256];for(uint n=0;n<256;n++){uint crc=n;for(int bit=0;bit<8;bit++)crc=(crc>>1)^((crc&1)!=0?0xedb88320u:0);table[n]=crc;}return table;}
    public ClipboardVault(string? directory=null,Func<byte[],byte[]>? protect=null,Func<byte[],byte[]>? unprotect=null)
    {
        this.directory=directory??Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"FloatClip","Library");
        Directory.CreateDirectory(this.directory); imageDirectory=Path.Combine(this.directory,"Images");Directory.CreateDirectory(imageDirectory);path=Path.Combine(this.directory,"clipboard.v1.bin");
        mutexName=(OperatingSystem.IsWindows()?"Local\\":"")+"FloatClipVault-"+Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(Path.GetFullPath(this.directory))))[..24];
        this.protect=protect??new Func<byte[],byte[]>(b=>ProtectedData.Protect(b,null,DataProtectionScope.CurrentUser));
        this.unprotect=unprotect??new Func<byte[],byte[]>(b=>ProtectedData.Unprotect(b,null,DataProtectionScope.CurrentUser));
    }
    T Locked<T>(Func<T> action)
    {
        using var mutex=new Mutex(false,mutexName);bool acquired=false;
        try{try{acquired=mutex.WaitOne(TimeSpan.FromSeconds(8));}catch(AbandonedMutexException){acquired=true;}
            if(!acquired)throw new IOException("剪贴板正由其他窗口保存，请重试");return action();}
        finally{if(acquired)mutex.ReleaseMutex();}
    }
    public JsonObject Device()=>Locked(()=>{
        string file=Path.Combine(directory,"device-id.txt");string id;
        if(File.Exists(file)){id=File.ReadAllText(file).Trim();if(!Guid.TryParse(id,out _))throw new IOException("设备标识损坏，请保留本机数据");}
        else{id=Guid.NewGuid().ToString();File.WriteAllText(file,id);}
        return new JsonObject{["id"]=id,["name"]=Environment.MachineName,["platform"]="windows"};
    });
    public JsonObject Load()=>Locked(()=>{
        if(!File.Exists(path))return new JsonObject{["revision"]="0",["entries"]=new JsonArray()};
        if(new FileInfo(path).Length>MaximumBytes+65536)throw new IOException("本机剪贴板库过大，请保留数据");byte[] cipher=File.ReadAllBytes(path);
        byte[] plain;
        try{plain=unprotect(cipher);}catch{throw new IOException("本机剪贴板无法解密，请保留数据并从备份恢复");}
        if(plain.Length>MaximumBytes)throw new IOException("本机剪贴板库过大，请保留数据");var entries=JsonNode.Parse(plain) as JsonArray??throw new IOException("本机剪贴板格式无效");Validate(entries);
        return new JsonObject{["revision"]=Convert.ToHexString(SHA256.HashData(cipher)),["entries"]=entries};
    });
    public JsonObject Commit(string revision,JsonArray entries)=>Locked(()=>{
        var current=Load();if(current["revision"]!.GetValue<string>()!=revision)throw new IOException("VAULT_CONFLICT");
        Validate(entries);byte[] plain=Encoding.UTF8.GetBytes(entries.ToJsonString());if(plain.Length>MaximumBytes)throw new IOException("剪贴板库超过 16 MiB");
        byte[] cipher=protect(plain);string temporary=path+"."+Guid.NewGuid().ToString("N")+".tmp";
        try{using(var stream=new FileStream(temporary,FileMode.CreateNew,FileAccess.Write,FileShare.None)){stream.Write(cipher);stream.Flush(true);}File.Move(temporary,path,true);}
        finally{if(File.Exists(temporary))File.Delete(temporary);}
        return new JsonObject{["revision"]=Convert.ToHexString(SHA256.HashData(cipher)),["entries"]=entries.DeepClone()};
    });
    public JsonObject AddText(string text,JsonObject? source=null)
    {
        if(string.IsNullOrWhiteSpace(text)||text.Length>1000000)throw new IOException("文字内容为空或过长");source??=Device();
        for(int retry=0;;retry++){
            var snapshot=Load();var entries=(JsonArray)snapshot["entries"]!;long now=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            var duplicate=entries.OfType<JsonObject>().FirstOrDefault(c=>Kind(c)=="text"&&c["text"]?.GetValue<string>()==text&&c["source"]?["id"]?.GetValue<string>()==source["id"]!.GetValue<string>());
            if(duplicate!=null){duplicate["updatedAt"]=now;duplicate["source"]=source.DeepClone();}
            else{
                if(entries.Count>=500){var remove=entries.OfType<JsonObject>().Where(c=>!IsPinned(c)&&!IsLocked(c)).OrderBy(c=>c["updatedAt"]!.GetValue<long>()).FirstOrDefault();if(remove==null)throw new IOException("剪贴板已满，请先整理固定或置顶记录");entries.Remove(remove);}
                entries.Add(new JsonObject{["id"]=Guid.NewGuid().ToString(),["kind"]="text",["text"]=text,["pinned"]=false,["locked"]=false,["category"]="未分类",["createdAt"]=now,["updatedAt"]=now,["source"]=source.DeepClone()});
            }
            try{return Commit(snapshot["revision"]!.GetValue<string>(),entries);}catch(IOException e)when(e.Message=="VAULT_CONFLICT"&&retry<3){}
        }
    }
    public static string Kind(JsonObject entry)=>entry["kind"]?.GetValue<string>()??"text";
    public static bool IsPinned(JsonObject entry)=>entry["pinned"]?.GetValue<bool>()??false;
    public static bool IsLocked(JsonObject entry)=>entry["locked"]?.GetValue<bool>()??false;

    static void ValidatePng(byte[] bytes,int width,int height)
    {
        if(bytes==null||bytes.Length<45||bytes.Length>MaximumImageBytes||!bytes.AsSpan(0,8).SequenceEqual(new byte[]{137,80,78,71,13,10,26,10}))throw new IOException("图片必须是完整 PNG，且不超过 8 MiB");
        if(width<1||height<1||width>16384||height>16384||(long)width*height>64000000)throw new IOException("图片尺寸超出安全范围");
        if(BinaryPrimitives.ReadUInt32BigEndian(bytes.AsSpan(8,4))!=13||Encoding.ASCII.GetString(bytes,12,4)!="IHDR"||BinaryPrimitives.ReadUInt32BigEndian(bytes.AsSpan(16,4))!=(uint)width||BinaryPrimitives.ReadUInt32BigEndian(bytes.AsSpan(20,4))!=(uint)height)throw new IOException("图片尺寸信息与 PNG 不一致");
        bool pixels=false,ended=false;int offset=8;
        while(offset<bytes.Length)
        {
            if(bytes.Length-offset<12)throw new IOException("PNG 数据不完整");
            uint length=BinaryPrimitives.ReadUInt32BigEndian(bytes.AsSpan(offset,4));
            if(length>(uint)(bytes.Length-offset-12))throw new IOException("PNG 数据不完整");
            int count=(int)length;string type=Encoding.ASCII.GetString(bytes,offset+4,4);
            if(type=="IHDR"&&offset!=8)throw new IOException("PNG 图片头重复");
            uint crc=0xffffffff;
            for(int i=offset+4;i<offset+8+count;i++)crc=(crc>>8)^PngCrcTable[(crc^bytes[i])&255];
            if(~crc!=BinaryPrimitives.ReadUInt32BigEndian(bytes.AsSpan(offset+8+count,4)))throw new IOException("PNG 数据校验失败");
            if(type=="IDAT")pixels=true;
            offset+=count+12;
            if(type=="IEND"){ended=true;if(count!=0||offset!=bytes.Length)throw new IOException("PNG 结束标记无效");break;}
        }
        if(!pixels||!ended)throw new IOException("PNG 数据不完整");
    }
    static string NormalizeImageRef(string value)
    {
        if(value.Length!=32||!System.Text.RegularExpressions.Regex.IsMatch(value,"^[0-9a-fA-F]{32}$"))throw new IOException("图片引用无效");
        return value.ToLowerInvariant();
    }
    string ImagePath(string imageRef)=>Path.Combine(imageDirectory,NormalizeImageRef(imageRef)+".bin");
    void WriteImageBlob(string imageRef,byte[] bytes)
    {
        string destination=ImagePath(imageRef),temporary=destination+"."+Guid.NewGuid().ToString("N")+".tmp";
        byte[] cipher=protect(bytes);
        try{using(var stream=new FileStream(temporary,FileMode.CreateNew,FileAccess.Write,FileShare.None)){stream.Write(cipher);stream.Flush(true);}File.Move(temporary,destination,true);}
        finally{if(File.Exists(temporary))File.Delete(temporary);}
    }
    void DeleteImageBlob(string imageRef){try{string path=ImagePath(imageRef);if(File.Exists(path))File.Delete(path);}catch{} }

    public JsonObject AddImage(byte[] pngBytes,int width,int height,JsonObject? source=null)=>Locked(()=>AddImageLocked(pngBytes,width,height,source));
    JsonObject AddImageLocked(byte[] pngBytes,int width,int height,JsonObject? source)
    {
        ValidatePng(pngBytes,width,height);
        source??=Device();string sourceId=source["id"]!.GetValue<string>();string hash=Convert.ToHexString(SHA256.HashData(pngBytes)).ToLowerInvariant();
        for(int retry=0;;retry++)
        {
            var snapshot=Load();var entries=snapshot["entries"]!.AsArray();long now=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            var duplicate=entries.OfType<JsonObject>().FirstOrDefault(c=>Kind(c)=="image"&&c["imageSha256"]?.GetValue<string>()==hash&&c["source"]?["id"]?.GetValue<string>()==sourceId);
            if(duplicate!=null)
            {
                // A new copy is also a recovery opportunity for an interrupted or damaged blob write.
                bool repair=false;try{ReadImage(duplicate);}catch(IOException){repair=true;}
                string? repairedRef=null;
                if(repair){repairedRef=Guid.NewGuid().ToString("N");WriteImageBlob(repairedRef,pngBytes);duplicate["imageRef"]=repairedRef;}
                duplicate["updatedAt"]=now;duplicate["imageBytes"]=(long)pngBytes.Length;duplicate["imageWidth"]=(long)width;duplicate["imageHeight"]=(long)height;
                try{return Commit(snapshot["revision"]!.GetValue<string>(),entries);}
                catch(IOException e)when(e.Message=="VAULT_CONFLICT"&&retry<3){if(repairedRef!=null)DeleteImageBlob(repairedRef);}
                catch{if(repairedRef!=null)DeleteImageBlob(repairedRef);throw;}
                continue;
            }
            JsonObject? evicted=null;
            if(entries.Count>=500)
            {
                evicted=entries.OfType<JsonObject>().Where(c=>!IsPinned(c)&&!IsLocked(c)).OrderBy(c=>c["updatedAt"]!.GetValue<long>()).FirstOrDefault();
                if(evicted==null)throw new IOException("固定和置顶记录已满，请先整理受保护的记录");
                entries.Remove(evicted);
            }
            string imageRef=Guid.NewGuid().ToString("N");WriteImageBlob(imageRef,pngBytes);
            entries.Add(new JsonObject{["id"]=Guid.NewGuid().ToString(),["kind"]="image",["text"]="图片",["imageRef"]=imageRef,["imageSha256"]=hash,["imageBytes"]=(long)pngBytes.Length,["imageWidth"]=(long)width,["imageHeight"]=(long)height,["mimeType"]="image/png",["pinned"]=false,["locked"]=false,["category"]="未分类",["createdAt"]=now,["updatedAt"]=now,["source"]=source.DeepClone()});
            try
            {
                var committed=Commit(snapshot["revision"]!.GetValue<string>(),entries);
                if(evicted!=null&&Kind(evicted)=="image")
                {
                    string oldRef=evicted["imageRef"]?.GetValue<string>()??"";
                    bool stillUsed=committed["entries"]!.AsArray().OfType<JsonObject>().Any(c=>c["imageRef"]?.GetValue<string>()==oldRef);
                    if(!stillUsed)DeleteImageBlob(oldRef);
                }
                return committed;
            }
            catch(IOException e)when(e.Message=="VAULT_CONFLICT"&&retry<3){DeleteImageBlob(imageRef);}
            catch{DeleteImageBlob(imageRef);throw;}
        }
    }

    public JsonObject? FindImage(string imageRef)=>Locked(()=>{
        string normalized=NormalizeImageRef(imageRef);
        return Load()["entries"]!.AsArray().OfType<JsonObject>()
            .FirstOrDefault(c=>Kind(c)=="image"&&string.Equals(c["imageRef"]?.GetValue<string>(),normalized,StringComparison.OrdinalIgnoreCase))?.DeepClone() as JsonObject;
    });

    public JsonObject ReceiveSharedImage(Guid eventId,FloatClip.Share.SyncDevice source,byte[] pngBytes,int width,int height,long createdAt)
    {
        if(eventId==Guid.Empty)throw new IOException("图片事件无效");ValidatePng(pngBytes,width,height);
        string hash=Convert.ToHexString(SHA256.HashData(pngBytes)).ToLowerInvariant(),key=eventId.ToString();
        var sourceJson=new JsonObject{["id"]=source.Id.ToString(),["name"]=source.Name,["platform"]=source.Platform};
        for(int retry=0;;retry++)
        {
            var snapshot=Load();var entries=snapshot["entries"]!.AsArray();
            var exact=entries.OfType<JsonObject>().FirstOrDefault(c=>c["id"]?.GetValue<string>()==key);
            if(exact!=null)
            {
                if(Kind(exact)!="image"||exact["imageSha256"]?.GetValue<string>()!=hash)throw new IOException("图片事件编号冲突");
                return snapshot;
            }
            var previous=entries.OfType<JsonObject>().FirstOrDefault(c=>Kind(c)=="image"&&c["imageSha256"]?.GetValue<string>()==hash&&c["source"]?["id"]?.GetValue<string>()==source.Id.ToString());
            JsonObject row;string imageRef;bool fresh=previous==null;
            if(previous!=null){row=(JsonObject)previous.DeepClone();imageRef=row["imageRef"]!.GetValue<string>();entries.Remove(previous);}
            else
            {
                if(entries.Count>=500)
                {
                    var removable=entries.OfType<JsonObject>().Where(c=>!IsPinned(c)&&!IsLocked(c)).OrderBy(c=>c["updatedAt"]!.GetValue<long>()).FirstOrDefault()??throw new IOException("固定和置顶记录已满");
                    entries.Remove(removable);
                }
                imageRef=Guid.NewGuid().ToString("N");row=new JsonObject{["kind"]="image",["text"]="图片",["pinned"]=false,["locked"]=false,["category"]="未分类"};
            }
            WriteImageBlob(imageRef,pngBytes);long now=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            row["id"]=key;row["imageRef"]=imageRef;row["imageSha256"]=hash;row["imageBytes"]=(long)pngBytes.Length;
            row["imageWidth"]=(long)width;row["imageHeight"]=(long)height;row["mimeType"]="image/png";
            row["createdAt"]=previous?["createdAt"]?.GetValue<long>()??createdAt;row["updatedAt"]=now;row["source"]=sourceJson.DeepClone();entries.Add(row);
            try{return Commit(snapshot["revision"]!.GetValue<string>(),entries);}
            catch(IOException e)when(e.Message=="VAULT_CONFLICT"&&retry<3){if(fresh)DeleteImageBlob(imageRef);}
            catch{if(fresh)DeleteImageBlob(imageRef);throw;}
        }
    }

    public byte[] ReadImage(JsonObject entry)
    {
        if(Kind(entry)!="image")throw new IOException("这条记录不是图片");
        string imageRef=entry["imageRef"]?.GetValue<string>()??throw new IOException("图片引用缺失");
        string expected=entry["imageSha256"]?.GetValue<string>()??throw new IOException("图片校验信息缺失");
        if(expected.Length!=64||!System.Text.RegularExpressions.Regex.IsMatch(expected,"^[0-9a-f]{64}$"))throw new IOException("图片校验信息无效");
        string file=ImagePath(imageRef);if(new FileInfo(file).Length>MaximumImageBytes+65536)throw new IOException("图片存储大小无效");byte[] cipher=File.ReadAllBytes(file);
        byte[] bytes;try{bytes=unprotect(cipher);}catch{throw new IOException("图片无法解密，请保留本机数据");}
        if(bytes.Length!=entry["imageBytes"]!.GetValue<long>()||!CryptographicOperations.FixedTimeEquals(SHA256.HashData(bytes),Convert.FromHexString(expected)))throw new IOException("图片校验失败，请保留本机数据");
        ValidatePng(bytes,checked((int)entry["imageWidth"]!.GetValue<long>()),checked((int)entry["imageHeight"]!.GetValue<long>()));
        return bytes;
    }

    /** Portable backups contain image bytes, never machine-local encrypted blob references alone. */
    public JsonObject ExportBackup()=>Locked(()=>{
        var entries=Load()["entries"]!.AsArray();var images=new JsonObject();
        foreach(var entry in entries.OfType<JsonObject>().Where(c=>Kind(c)=="image"))
        {
            string hash=entry["imageSha256"]!.GetValue<string>();byte[] bytes=ReadImage(entry);
            if(images[hash]==null)images[hash]=Convert.ToBase64String(bytes);
        }
        return new JsonObject{["format"]="FloatClip.clipboard",["schema"]=2,["entries"]=entries.DeepClone(),["images"]=images};
    });

    public int ImportBackup(JsonObject document)=>Locked(()=>{
        int schema=document["schema"]?.GetValue<int>()??0;
        if(document["format"]?.GetValue<string>()!="FloatClip.clipboard"||schema is not(1 or 2))throw new IOException("请导入 FloatClip 剪贴板备份");
        var incoming=document["entries"] as JsonArray??throw new IOException("备份记录缺失");Validate(incoming);
        var imageEntries=incoming.OfType<JsonObject>().Where(c=>Kind(c)=="image").ToList();
        if(schema==1&&imageEntries.Count>0)throw new IOException("旧备份仅含图片引用，缺少图片数据；请从原电脑重新导出完整备份");
        var encoded=document["images"] as JsonObject??new JsonObject();
        var hashes=imageEntries.Select(c=>c["imageSha256"]!.GetValue<string>()).ToHashSet(StringComparer.Ordinal);
        if(encoded.Count!=hashes.Count||encoded.Any(p=>!hashes.Contains(p.Key)))throw new IOException("备份图片数据与记录不匹配");
        var blobs=new Dictionary<string,byte[]>(StringComparer.Ordinal);long total=0;
        foreach(string hash in hashes)
        {
            string value=encoded[hash]?.GetValue<string>()??throw new IOException("备份图片数据缺失");
            if(value.Length>(MaximumImageBytes+2L)/3*4)throw new IOException("备份图片超过 8 MiB");
            byte[] bytes;try{bytes=Convert.FromBase64String(value);}catch(FormatException){throw new IOException("备份图片编码无效");}
            total+=bytes.Length;if(total>MaximumImageStorageBytes)throw new IOException("备份图片总量超过 64 MiB");
            if(!CryptographicOperations.FixedTimeEquals(SHA256.HashData(bytes),Convert.FromHexString(hash)))throw new IOException("备份图片校验失败");
            foreach(var entry in imageEntries.Where(c=>c["imageSha256"]!.GetValue<string>()==hash))
            {
                if(bytes.Length!=entry["imageBytes"]!.GetValue<long>())throw new IOException("备份图片大小不一致");
                ValidatePng(bytes,checked((int)entry["imageWidth"]!.GetValue<long>()),checked((int)entry["imageHeight"]!.GetValue<long>()));
            }
            blobs.Add(hash,bytes);
        }
        var snapshot=Load();var entries=snapshot["entries"]!.AsArray();var staged=new Dictionary<string,byte[]>();int merged=0;
        foreach(var entry in incoming.OfType<JsonObject>())
        {
            string id=entry["id"]!.GetValue<string>();var old=entries.OfType<JsonObject>().FirstOrDefault(c=>c["id"]!.GetValue<string>()==id);
            if(old!=null&&old["source"]!["id"]!.GetValue<string>()!=entry["source"]!["id"]!.GetValue<string>())throw new IOException("备份中存在来源冲突");
            if(old!=null&&entry["updatedAt"]!.GetValue<long>()<=old["updatedAt"]!.GetValue<long>())continue;
            var row=(JsonObject)entry.DeepClone();
            if(old!=null){row["pinned"]=IsPinned(row)||IsPinned(old);row["locked"]=IsLocked(row)||IsLocked(old);entries.Remove(old);}
            if(Kind(row)=="image"){string reference=Guid.NewGuid().ToString("N");row["imageRef"]=reference;staged.Add(reference,blobs[row["imageSha256"]!.GetValue<string>()]);}
            entries.Add(row);merged++;
        }
        // Validate the complete merge before writing either metadata or new blobs.
        Validate(entries);if(Encoding.UTF8.GetByteCount(entries.ToJsonString())>MaximumBytes)throw new IOException("合并后剪贴板库超过 16 MiB");
        var written=new List<string>();
        try
        {
            foreach(var pair in staged){written.Add(pair.Key);WriteImageBlob(pair.Key,pair.Value);}
            if(merged>0)Commit(snapshot["revision"]!.GetValue<string>(),entries);
            return merged;
        }
        catch{foreach(string reference in written)DeleteImageBlob(reference);throw;}
    });

    public int ClearUnprotected()
    {
        for(int retry=0;;retry++)
        {
            var snapshot=Load();var entries=snapshot["entries"]!.AsArray();
            var removed=entries.OfType<JsonObject>().Where(c=>!IsPinned(c)&&!IsLocked(c)).ToList();
            if(removed.Count==0)return 0;
            foreach(var entry in removed)entries.Remove(entry);
            try{Commit(snapshot["revision"]!.GetValue<string>(),entries);return removed.Count;}
            catch(IOException e)when(e.Message=="VAULT_CONFLICT"&&retry<3){}
        }
    }

    /** Remove stale image blobs only at process startup, when no in-memory undo can reference them. */
    public void PruneOrphanedImages(IEnumerable<string>? preserveReferences=null)
    {
        Locked(()=>{
            var entries=Load()["entries"]!.AsArray().OfType<JsonObject>().ToList();
            var referenced=new HashSet<string>(entries.Where(c=>Kind(c)=="image").Select(c=>c["imageRef"]?.GetValue<string>()??"").Where(x=>x.Length>0).Select(NormalizeImageRef));
            if(preserveReferences!=null)foreach(string value in preserveReferences)try{referenced.Add(NormalizeImageRef(value));}catch{}
            foreach(string file in Directory.EnumerateFiles(imageDirectory))
            {
                string extension=Path.GetExtension(file),name=Path.GetFileNameWithoutExtension(file);
                bool staleTemporary=extension==".tmp"&&File.GetLastWriteTimeUtc(file)<DateTime.UtcNow.AddDays(-1);
                if(staleTemporary||extension==".bin"&&!referenced.Contains(name))try{File.Delete(file);}catch{}
            }
            return true;
        });
    }

    public static void Validate(JsonArray entries)
    {
        ValidateEntries(entries);
    }
    public void ReceiveShared(FloatClip.Share.SyncEntry entry)
    {
        for(int retry=0;;retry++)
        {
            var snapshot=Load();var entries=snapshot["entries"]!.AsArray();string key=entry.Id.ToString();
            if(entries.OfType<JsonObject>().Any(c=>c["id"]!.GetValue<string>()==key))return;
            var previous=entries.OfType<JsonObject>().FirstOrDefault(c=>Kind(c)=="text"&&c["text"]!.GetValue<string>()==entry.Text&&c["source"]!["id"]!.GetValue<string>()==entry.Source.Id.ToString());
            long now=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            var row=previous==null?new JsonObject{["kind"]="text",["pinned"]=false,["locked"]=false,["category"]="未分类",["createdAt"]=now}:(JsonObject)previous.DeepClone();
            if(previous!=null)entries.Remove(previous);
            if(entries.Count>=500)
            {
                var removable=entries.OfType<JsonObject>().Where(c=>!IsPinned(c)&&!IsLocked(c)).OrderBy(c=>c["updatedAt"]!.GetValue<long>()).FirstOrDefault()??throw new IOException("固定和置顶记录已满");
                entries.Remove(removable);
            }
            row["id"]=key;row["text"]=entry.Text;row["updatedAt"]=now;
            row["source"]=new JsonObject{["id"]=entry.Source.Id.ToString(),["name"]=entry.Source.Name,["platform"]=entry.Source.Platform};entries.Add(row);
            try{Commit(snapshot["revision"]!.GetValue<string>(),entries);return;}catch(IOException e)when(e.Message=="VAULT_CONFLICT"&&retry<3){}
        }
    }
    static void ValidateEntries(JsonArray entries)
    {
        if(entries.Count>500)throw new IOException("剪贴板最多保存 500 条");var keys=new HashSet<string>();var imageSizes=new Dictionary<string,long>();var imageHashes=new Dictionary<string,string>();
        foreach(var node in entries){var c=node as JsonObject??throw new IOException("记录格式无效");string id=c["id"]!.GetValue<string>(),text=c["text"]?.GetValue<string>()??"",category=c["category"]!.GetValue<string>(),kind=Kind(c);
            var s=c["source"] as JsonObject??throw new IOException("设备来源无效");string sid=s["id"]!.GetValue<string>(),name=s["name"]!.GetValue<string>(),platform=s["platform"]!.GetValue<string>();
            if(!System.Text.RegularExpressions.Regex.IsMatch(id,@"^[-a-zA-Z0-9_:]{1,128}$")||!keys.Add(id)||category.Length>80||!System.Text.RegularExpressions.Regex.IsMatch(sid,@"^[-a-zA-Z0-9_:]{1,100}$")||string.IsNullOrWhiteSpace(name)||name.Length>80||platform is not("android" or "windows" or "ipad" or "web")||c["createdAt"]!.GetValue<long>()<0||c["updatedAt"]!.GetValue<long>()<0)throw new IOException("剪贴板记录格式无效");
            if(kind=="text"){if(string.IsNullOrWhiteSpace(text)||text.Length>1000000)throw new IOException("剪贴板文字格式无效");}
            else if(kind=="image"){
                string imageRef=c["imageRef"]?.GetValue<string>()??"",hash=c["imageSha256"]?.GetValue<string>()??"",mime=c["mimeType"]?.GetValue<string>()??"";long bytes=c["imageBytes"]?.GetValue<long>()??0,width=c["imageWidth"]?.GetValue<long>()??0,height=c["imageHeight"]?.GetValue<long>()??0;
                if(text.Length>80||imageRef.Length!=32||hash.Length!=64||!System.Text.RegularExpressions.Regex.IsMatch(imageRef,"^[0-9a-fA-F]{32}$")||!System.Text.RegularExpressions.Regex.IsMatch(hash,"^[0-9a-f]{64}$")||mime!="image/png"||bytes<45||bytes>MaximumImageBytes||width<1||height<1||width>16384||height>16384||width*height>64000000)throw new IOException("剪贴板图片记录格式无效");
                string normalized=NormalizeImageRef(imageRef);if(imageSizes.TryGetValue(normalized,out long size)&&(size!=bytes||imageHashes[normalized]!=hash))throw new IOException("图片引用信息不一致");imageSizes[normalized]=bytes;imageHashes[normalized]=hash;
            }else throw new IOException("剪贴板记录类型无效");
            _=c["pinned"]!.GetValue<bool>();if(c["locked"]!=null)_=c["locked"]!.GetValue<bool>();
        }
        if(imageSizes.Values.Sum()>MaximumImageStorageBytes)throw new IOException("图片存储已满（64 MiB），请先整理普通图片记录");
    }
}
