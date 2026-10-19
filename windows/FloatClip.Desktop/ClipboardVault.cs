using System;
using System.IO;
using System.Text;
using System.Text.Json.Nodes;
using System.Security.Cryptography;
using System.Threading;
using System.Linq;
using System.Collections.Generic;

namespace FloatClip.Desktop;

/** Local clipboard storage. Injected protection is used only by cross-platform model checks. */
public sealed class ClipboardVault
{
    readonly string directory,path,mutexName;
    readonly Func<byte[],byte[]> protect,unprotect;
    public const int MaximumBytes=16*1024*1024;
    public ClipboardVault(string? directory=null,Func<byte[],byte[]>? protect=null,Func<byte[],byte[]>? unprotect=null)
    {
        this.directory=directory??Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"FloatClip","Library");
        Directory.CreateDirectory(this.directory); path=Path.Combine(this.directory,"clipboard.v1.bin");
        mutexName=(OperatingSystem.IsWindows()?"Local\\":"")+"FloatClipVault-"+Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(Path.GetFullPath(this.directory))))[..24];
        this.protect=protect??(b=>ProtectedData.Protect(b,null,DataProtectionScope.CurrentUser));
        this.unprotect=unprotect??(b=>ProtectedData.Unprotect(b,null,DataProtectionScope.CurrentUser));
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
        byte[] cipher=File.ReadAllBytes(path);if(cipher.Length>MaximumBytes+65536)throw new IOException("本机剪贴板库过大，请保留数据");
        byte[] plain;
        try{plain=unprotect(cipher);}catch{throw new IOException("本机剪贴板无法解密，请保留数据并从备份恢复");}
        var entries=JsonNode.Parse(plain) as JsonArray??throw new IOException("本机剪贴板格式无效");Validate(entries);
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
            var duplicate=entries.OfType<JsonObject>().FirstOrDefault(c=>c["text"]?.GetValue<string>()==text&&c["source"]?["id"]?.GetValue<string>()==source["id"]!.GetValue<string>());
            if(duplicate!=null){duplicate["updatedAt"]=now;duplicate["source"]=source.DeepClone();}
            else{
                if(entries.Count>=500){var remove=entries.OfType<JsonObject>().Where(c=>!c["pinned"]!.GetValue<bool>()).OrderBy(c=>c["updatedAt"]!.GetValue<long>()).FirstOrDefault();if(remove==null)throw new IOException("剪贴板已满，请先整理置顶记录");entries.Remove(remove);}
                entries.Add(new JsonObject{["id"]=Guid.NewGuid().ToString(),["text"]=text,["pinned"]=false,["category"]="未分类",["createdAt"]=now,["updatedAt"]=now,["source"]=source.DeepClone()});
            }
            try{return Commit(snapshot["revision"]!.GetValue<string>(),entries);}catch(IOException e)when(e.Message=="VAULT_CONFLICT"&&retry<3){}
        }
    }
    public static void Validate(JsonArray entries)
    {
        if(entries.Count>500)throw new IOException("剪贴板最多保存 500 条");var keys=new HashSet<string>();
        foreach(var node in entries){var c=node as JsonObject??throw new IOException("记录格式无效");string id=c["id"]!.GetValue<string>(),text=c["text"]!.GetValue<string>(),category=c["category"]!.GetValue<string>();
            var s=c["source"] as JsonObject??throw new IOException("设备来源无效");string sid=s["id"]!.GetValue<string>(),name=s["name"]!.GetValue<string>(),platform=s["platform"]!.GetValue<string>();
            if(!System.Text.RegularExpressions.Regex.IsMatch(id,@"^[-a-zA-Z0-9_:]{1,128}$")||!keys.Add(id)||string.IsNullOrWhiteSpace(text)||text.Length>1000000||category.Length>80||!System.Text.RegularExpressions.Regex.IsMatch(sid,@"^[-a-zA-Z0-9_:]{1,100}$")||string.IsNullOrWhiteSpace(name)||name.Length>80||platform is not("android" or "windows" or "ipad" or "web")||c["createdAt"]!.GetValue<long>()<0||c["updatedAt"]!.GetValue<long>()<0)throw new IOException("剪贴板记录格式无效");
            _=c["pinned"]!.GetValue<bool>();
        }
    }
}
