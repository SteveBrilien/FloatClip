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
public sealed partial class ClipboardVault
{
    readonly string directory,path,mutexName;
    string imageDirectory;
    JsonObject? memorySnapshot;
    long snapshotLength=-1,snapshotWriteTicks=-1;
    readonly Func<byte[],byte[]> protect,unprotect;
    public const int MaximumBytes=16*1024*1024;
    public const int MaximumImageBytes=8*1024*1024;
    public const long MaximumImageStorageBytes=64L*1024*1024;
    public const long MaximumBackupBytes=128L*1024*1024;
    static readonly uint[] PngCrcTable=CreatePngCrcTable();
    static uint[] CreatePngCrcTable(){var table=new uint[256];for(uint n=0;n<256;n++){uint crc=n;for(int bit=0;bit<8;bit++)crc=(crc>>1)^((crc&1)!=0?0xedb88320u:0);table[n]=crc;}return table;}
    public ClipboardVault(string? directory=null,Func<byte[],byte[]>? protect=null,Func<byte[],byte[]>? unprotect=null,string? imageDirectoryOverride=null,string? recoveryDirectory=null)
    {
        this.directory=directory??Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"FloatClip","Library");
        Directory.CreateDirectory(this.directory); imageDirectory=DesktopStoragePreferences.NormalizeDirectory(imageDirectoryOverride,Path.Combine(this.directory,"Images"));path=Path.Combine(this.directory,"clipboard.v1.bin");
        mutexName=(OperatingSystem.IsWindows()?"Local\\":"")+"FloatClipVault-"+Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(Path.GetFullPath(this.directory))))[..24];
        this.protect=protect??new Func<byte[],byte[]>(b=>ProtectedData.Protect(b,null,DataProtectionScope.CurrentUser));
        this.unprotect=unprotect??new Func<byte[],byte[]>(b=>ProtectedData.Unprotect(b,null,DataProtectionScope.CurrentUser));
        this.recoveryDirectory=recoveryDirectory==null?null:Path.GetFullPath(recoveryDirectory);
        CheckRecoverySeparation(imageDirectory);
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
        if(!File.Exists(file)&&!File.Exists(path))RestoreMissingIndex();
        if(File.Exists(file)){id=File.ReadAllText(file).Trim();if(!Guid.TryParse(id,out _))throw new IOException("设备标识损坏，请保留本机数据");}
        else{id=Guid.NewGuid().ToString();File.WriteAllText(file,id);}
        return new JsonObject{["id"]=id,["name"]=Environment.MachineName,["platform"]="windows"};
    });
    JsonObject LoadCore()
    {
        if(!File.Exists(path)&&!RestoreMissingIndex())
        {
            if(snapshotLength>0)throw new IOException("本机剪贴板索引已消失，请检查恢复副本");
            var empty=new JsonObject{["revision"]="0",["entries"]=new JsonArray()};
            memorySnapshot=empty;snapshotLength=0;snapshotWriteTicks=0;return empty;
        }
        var info=new FileInfo(path);
        if(info.Length>MaximumBytes+65536)throw new IOException("本机剪贴板库过大，请保留数据");
        long writeTicks=info.LastWriteTimeUtc.Ticks;
        if(memorySnapshot!=null&&snapshotLength==info.Length&&snapshotWriteTicks==writeTicks)
            return memorySnapshot;
        byte[] cipher=File.ReadAllBytes(path);
        var entries=DecodeIndex(cipher);
        var loaded=new JsonObject{["revision"]=Convert.ToHexString(SHA256.HashData(cipher)),["entries"]=entries};
        memorySnapshot=loaded;snapshotLength=info.Length;snapshotWriteTicks=writeTicks;
        return loaded;
    }
    JsonArray DecodeIndex(byte[] cipher)
    {
        if(cipher.Length>MaximumBytes+65536)throw new IOException("本机剪贴板库过大，请保留数据");
        byte[] plain;
        try{plain=unprotect(cipher);}catch{throw new IOException("本机剪贴板无法解密，请保留数据并从备份恢复");}
        if(plain.Length>MaximumBytes)throw new IOException("本机剪贴板库过大，请保留数据");
        var entries=JsonNode.Parse(plain) as JsonArray??throw new IOException("本机剪贴板格式无效");
        Validate(entries);
        return entries;
    }
    public JsonObject Load()=>Locked(()=>(JsonObject)LoadCore().DeepClone());
    public JsonObject? LoadIfChanged(string? revision)=>Locked(()=>{
        var snapshot=LoadCore();
        return snapshot["revision"]!.GetValue<string>()==revision?null:(JsonObject)snapshot.DeepClone();
    });
    public JsonObject Commit(string revision,JsonArray entries)=>Locked(()=>{
        var current=LoadCore();if(current["revision"]!.GetValue<string>()!=revision)throw new IOException("VAULT_CONFLICT");
        Validate(entries);byte[] plain=Encoding.UTF8.GetBytes(entries.ToJsonString());if(plain.Length>MaximumBytes)throw new IOException("剪贴板库超过 16 MiB");
        byte[] cipher=protect(plain);string temporary=path+"."+Guid.NewGuid().ToString("N")+".tmp";
        try{using(var stream=new FileStream(temporary,FileMode.CreateNew,FileAccess.Write,FileShare.None)){stream.Write(cipher);stream.Flush(true);}File.Move(temporary,path,true);}
        finally{if(File.Exists(temporary))File.Delete(temporary);}
        var result=new JsonObject{["revision"]=Convert.ToHexString(SHA256.HashData(cipher)),["entries"]=entries.DeepClone()};
        var info=new FileInfo(path);memorySnapshot=(JsonObject)result.DeepClone();snapshotLength=info.Length;snapshotWriteTicks=info.LastWriteTimeUtc.Ticks;
        RequestRecoveryCheckpoint();
        return result;
    });
    public JsonObject AddText(string text,JsonObject? source=null)
    {
        if(string.IsNullOrWhiteSpace(text)||text.Length>1000000)throw new IOException("文字内容为空或过长");source??=Device();
        for(int retry=0;;retry++){
            var snapshot=Load();var entries=(JsonArray)snapshot["entries"]!;long now=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            var duplicate=entries.OfType<JsonObject>().FirstOrDefault(c=>Kind(c)=="text"&&c["text"]?.GetValue<string>()==text&&c["source"]?["id"]?.GetValue<string>()==source["id"]!.GetValue<string>());
            JsonObject? evicted=null;
            if(duplicate!=null){duplicate["updatedAt"]=now;duplicate["source"]=source.DeepClone();}
            else{
                if(entries.Count>=500){evicted=entries.OfType<JsonObject>().Where(c=>!IsPinned(c)&&!IsLocked(c)).OrderBy(c=>c["updatedAt"]!.GetValue<long>()).FirstOrDefault();if(evicted==null)throw new IOException("剪贴板已满，请先整理固定或置顶记录");entries.Remove(evicted);}
                entries.Add(new JsonObject{["id"]=Guid.NewGuid().ToString(),["kind"]="text",["text"]=text,["pinned"]=false,["locked"]=false,["category"]="未分类",["createdAt"]=now,["updatedAt"]=now,["source"]=source.DeepClone()});
            }
            try{
                var committed=Commit(snapshot["revision"]!.GetValue<string>(),entries);
                if(evicted!=null)RemoveUnreferencedImages(ImageReferences(new[]{evicted}));
                return committed;
            }catch(IOException e)when(e.Message=="VAULT_CONFLICT"&&retry<3){}
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
    public string ImageDirectory=>imageDirectory;
    string ThumbnailDirectory=>Path.Combine(imageDirectory,"Thumbnails");
    string ImagePath(string imageRef)=>Path.Combine(imageDirectory,NormalizeImageRef(imageRef)+".bin");

    public void SetImageDirectory(string target)=>Locked(()=>{
        target=DesktopStoragePreferences.NormalizeDirectory(target,imageDirectory);
        CheckRecoverySeparation(target);
        string prior=Path.GetFullPath(imageDirectory);
        if(string.Equals(prior,target,StringComparison.OrdinalIgnoreCase))return true;
        Directory.CreateDirectory(target);
        foreach(var entry in LoadCore()["entries"]!.AsArray().OfType<JsonObject>().Where(c=>Kind(c)=="image"))
        {
            string reference=NormalizeImageRef(entry["imageRef"]!.GetValue<string>());
            string source=Path.Combine(prior,reference+".bin"),destination=Path.Combine(target,reference+".bin");
            if(!File.Exists(source))throw new IOException("旧缓存目录中的图片不完整，未切换路径");
            string temporary=destination+"."+Guid.NewGuid().ToString("N")+".tmp";
            try{File.Copy(source,temporary,true);File.Move(temporary,destination,true);}
            finally{if(File.Exists(temporary))File.Delete(temporary);}
        }
        string oldThumbs=Path.Combine(prior,"Thumbnails"),newThumbs=Path.Combine(target,"Thumbnails");
        if(Directory.Exists(oldThumbs))
        {
            Directory.CreateDirectory(newThumbs);
            foreach(string source in Directory.EnumerateFiles(oldThumbs,"*.bin"))
                try{File.Copy(source,Path.Combine(newThumbs,Path.GetFileName(source)),true);}catch(IOException){}
        }
        imageDirectory=target;
        try{foreach(var entry in LoadCore()["entries"]!.AsArray().OfType<JsonObject>().Where(c=>Kind(c)=="image"))_=ReadImage(entry);}
        catch{imageDirectory=prior;throw;}
        return true;
    });

    public byte[]? ReadThumbnail(string key)
    {
        if(!System.Text.RegularExpressions.Regex.IsMatch(key,"^[0-9a-f]{64}$"))return null;
        return Locked(()=>{
            string file=Path.Combine(ThumbnailDirectory,key+".bin");
            try
            {
                if(!File.Exists(file)||new FileInfo(file).Length>2*1024*1024){if(File.Exists(file))File.Delete(file);return null;}
                byte[] plain=unprotect(File.ReadAllBytes(file));if(plain.Length is <45 or >1500000)throw new IOException();
                File.SetLastWriteTimeUtc(file,DateTime.UtcNow);return plain;
            }
            catch{try{File.Delete(file);}catch{}return null;}
        });
    }

    public void WriteThumbnail(string key,byte[] png)
    {
        if(!System.Text.RegularExpressions.Regex.IsMatch(key,"^[0-9a-f]{64}$")||png.Length is <45 or >1500000)return;
        Locked(()=>{
            Directory.CreateDirectory(ThumbnailDirectory);
            string file=Path.Combine(ThumbnailDirectory,key+".bin"),temporary=file+"."+Guid.NewGuid().ToString("N")+".tmp";
            try{File.WriteAllBytes(temporary,protect(png));File.Move(temporary,file,true);}
            finally{if(File.Exists(temporary))File.Delete(temporary);}
            long bytes=0;int count=0;
            foreach(var item in Directory.EnumerateFiles(ThumbnailDirectory,"*.bin").Select(f=>new FileInfo(f)).OrderByDescending(f=>f.LastWriteTimeUtc))
            {bytes+=item.Length;count++;if(count>128||bytes>48L*1024*1024)try{item.Delete();}catch{}}
            return true;
        });
    }
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
            var duplicate=entries.OfType<JsonObject>().FirstOrDefault(c=>Kind(c)=="image"&&c["imageSha256"]?.GetValue<string>()==hash);
            if(duplicate!=null)
            {
                // A new copy is also a recovery opportunity for an interrupted or damaged blob write.
                bool repair=false;try{ReadImage(duplicate);}catch(IOException){repair=true;}
                if(!repair)return snapshot;
                string? repairedRef=null;
                string previousRef=duplicate["imageRef"]!.GetValue<string>();
                if(repair){repairedRef=Guid.NewGuid().ToString("N");WriteImageBlob(repairedRef,pngBytes);duplicate["imageRef"]=repairedRef;}
                // Repair changes only the encrypted blob reference, never original chronology.
                try{
                    var committed=Commit(snapshot["revision"]!.GetValue<string>(),entries);
                    if(repairedRef!=null&&!ImageReferences(entries.OfType<JsonObject>()).Contains(previousRef,StringComparer.OrdinalIgnoreCase))
                        DeleteImageBlob(previousRef);
                    return committed;
                }
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
            var previous=entries.OfType<JsonObject>().FirstOrDefault(c=>Kind(c)=="image"&&c["imageSha256"]?.GetValue<string>()==hash);
            // Same bytes copied again are a new user event, not a network retry.
            // Exact event IDs above remain idempotent. Reuse the existing image blob and
            // protected metadata, but refresh the event ID/timestamp and recent ordering.
            JsonObject row;string imageRef;bool fresh=previous==null;
            JsonObject? evicted=null;
            if(previous!=null){row=(JsonObject)previous.DeepClone();imageRef=row["imageRef"]!.GetValue<string>();entries.Remove(previous);}
            else
            {
                if(entries.Count>=500)
                {
                    evicted=entries.OfType<JsonObject>().Where(c=>!IsPinned(c)&&!IsLocked(c)).OrderBy(c=>c["updatedAt"]!.GetValue<long>()).FirstOrDefault()??throw new IOException("固定和置顶记录已满");
                    entries.Remove(evicted);
                }
                imageRef=Guid.NewGuid().ToString("N");row=new JsonObject{["kind"]="image",["text"]="图片",["pinned"]=false,["locked"]=false,["category"]="未分类"};
            }
            WriteImageBlob(imageRef,pngBytes);long now=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            row["id"]=key;row["imageRef"]=imageRef;row["imageSha256"]=hash;row["imageBytes"]=(long)pngBytes.Length;
            row["imageWidth"]=(long)width;row["imageHeight"]=(long)height;row["mimeType"]="image/png";
            row["createdAt"]=previous?["createdAt"]?.GetValue<long>()??createdAt;row["updatedAt"]=now;row["source"]=sourceJson.DeepClone();entries.Add(row);
            JsonObject committed;
            try{committed=Commit(snapshot["revision"]!.GetValue<string>(),entries);}
            catch(IOException e)when(e.Message=="VAULT_CONFLICT"&&retry<3){if(fresh)DeleteImageBlob(imageRef);continue;}
            catch{if(fresh)DeleteImageBlob(imageRef);throw;}
            if(evicted!=null)RemoveUnreferencedImages(ImageReferences(new[]{evicted}));
            return committed;
        }
    }

    public byte[] ReadImage(JsonObject entry)
    {
        if(Kind(entry)!="image")throw new IOException("这条记录不是图片");
        string imageRef=entry["imageRef"]?.GetValue<string>()??throw new IOException("图片引用缺失");
        string expected=entry["imageSha256"]?.GetValue<string>()??throw new IOException("图片校验信息缺失");
        if(expected.Length!=64||!System.Text.RegularExpressions.Regex.IsMatch(expected,"^[0-9a-f]{64}$"))throw new IOException("图片校验信息无效");
        string file=ImagePath(imageRef);
        if(!File.Exists(file)&&recoveryDirectory!=null)
            return Locked(()=>File.Exists(file)?DecodeImageBlob(file,entry,expected):RestoreMissingImage(file,entry,expected));
        return DecodeImageBlob(file,entry,expected);
    }

    byte[] DecodeImageBlob(string file,JsonObject entry,string expected)
    {
        if(new FileInfo(file).Length>MaximumImageBytes+65536)throw new IOException("图片存储大小无效");
        byte[] cipher=File.ReadAllBytes(file);
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
            try{
                Commit(snapshot["revision"]!.GetValue<string>(),entries);
                RemoveUnreferencedImages(ImageReferences(removed));
                return removed.Count;
            }
            catch(IOException e)when(e.Message=="VAULT_CONFLICT"&&retry<3){}
        }
    }

    /** Only remove candidates from a successful, explicit edit or eviction. */
    public void RemoveUnreferencedImages(IEnumerable<string> candidates)
    {
        var references=candidates.Distinct(StringComparer.OrdinalIgnoreCase).ToArray();
        if(references.Length==0)return;
        Locked(()=>{
            if(!File.Exists(path))return false;
            var referenced=ImageReferences(LoadCore()["entries"]!.AsArray().OfType<JsonObject>()).ToHashSet(StringComparer.OrdinalIgnoreCase);
            foreach(string reference in references)
                if(!referenced.Contains(reference))DeleteImageBlob(reference);
            return true;
        });
    }

    internal static IEnumerable<string> ImageReferences(IEnumerable<JsonObject> rows)=>
        rows.Where(row=>Kind(row)=="image").Select(row=>NormalizeImageRef(row["imageRef"]!.GetValue<string>()));

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
            JsonObject? evicted=null;
            if(entries.Count>=500)
            {
                evicted=entries.OfType<JsonObject>().Where(c=>!IsPinned(c)&&!IsLocked(c)).OrderBy(c=>c["updatedAt"]!.GetValue<long>()).FirstOrDefault()??throw new IOException("固定和置顶记录已满");
                entries.Remove(evicted);
            }
            row["id"]=key;row["text"]=entry.Text;row["updatedAt"]=now;
            row["source"]=new JsonObject{["id"]=entry.Source.Id.ToString(),["name"]=entry.Source.Name,["platform"]=entry.Source.Platform};entries.Add(row);
            try{
                Commit(snapshot["revision"]!.GetValue<string>(),entries);
                if(evicted!=null)RemoveUnreferencedImages(ImageReferences(new[]{evicted}));
                return;
            }catch(IOException e)when(e.Message=="VAULT_CONFLICT"&&retry<3){}
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
