using System;
using System.IO;
using System.Linq;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;

namespace FloatClip.Desktop;

public sealed partial class ClipboardVault
{
    readonly string? recoveryDirectory;
    int recoveryPending, recoveryWorker;
    volatile string? recoveryWarning;
    public string? RecoveryWarning => recoveryWarning;

    string RecoveryIndex => Path.Combine(recoveryDirectory!,"clipboard.v1.bin");
    string RecoveryImage(string reference) => Path.Combine(recoveryDirectory!,"Images",NormalizeImageRef(reference)+".bin");

    internal void RequestRecoveryCheckpoint()
    {
        if(recoveryDirectory==null)return;
        Volatile.Write(ref recoveryPending,1);
        StartRecoveryWorker();
    }

    void StartRecoveryWorker()
    {
        if(Interlocked.CompareExchange(ref recoveryWorker,1,0)!=0)return;
        _=Task.Run(async ()=>{
            try
            {
                do
                {
                    await Task.Delay(350).ConfigureAwait(false);
                    if(Interlocked.Exchange(ref recoveryPending,0)!=0)WriteRecoveryCheckpoint();
                }while(Volatile.Read(ref recoveryPending)!=0);
            }
            finally
            {
                Volatile.Write(ref recoveryWorker,0);
                if(Volatile.Read(ref recoveryPending)!=0)StartRecoveryWorker();
            }
        });
    }

    internal void FlushRecoveryCheckpoint()
    {
        if(recoveryDirectory==null)return;
        Interlocked.Exchange(ref recoveryPending,0);
        WriteRecoveryCheckpoint();
    }

    void WriteRecoveryCheckpoint()
    {
        try
        {
            Locked(()=>{
                if(File.Exists(path))SaveRecoveryCheckpoint();
                return true;
            });
            recoveryWarning=null;
        }
        catch(Exception)
        {
            recoveryWarning="恢复副本暂未更新，请检查本机存储";
        }
    }

    void SaveRecoveryCheckpoint()
    {
        var entries=LoadCore()["entries"]!.AsArray().OfType<JsonObject>();
        var originals=entries.Where(row=>Kind(row)=="image").DistinctBy(row=>row["imageRef"]!.GetValue<string>(),StringComparer.OrdinalIgnoreCase).ToArray();
        Directory.CreateDirectory(recoveryDirectory!);
        var imageBackup=Path.Combine(recoveryDirectory!,"Images");
        Directory.CreateDirectory(imageBackup);
        foreach(var row in originals)
        {
            string reference=row["imageRef"]!.GetValue<string>(), backup=RecoveryImage(reference);
            if(File.Exists(backup))continue;
            string original=ImagePath(reference);
            _=DecodeImageBlob(original,row,row["imageSha256"]!.GetValue<string>());
            AtomicCopy(original,backup);
        }
        _=Device();
        AtomicCopy(Path.Combine(directory,"device-id.txt"),Path.Combine(recoveryDirectory!,"device-id.txt"));
        // Publish the index only after all encrypted original attachments are in place.
        AtomicCopy(path,RecoveryIndex);
        var live=ImageReferences(originals).ToHashSet(StringComparer.OrdinalIgnoreCase);
        foreach(string file in Directory.EnumerateFiles(imageBackup,"*.bin"))
        {
            string name=Path.GetFileNameWithoutExtension(file);
            if(name.Length==32&&name.All(Uri.IsHexDigit)&&!live.Contains(name))
                try{File.Delete(file);}catch(IOException){}catch(UnauthorizedAccessException){}
        }
    }

    bool RestoreMissingIndex()
    {
        if(recoveryDirectory==null||!File.Exists(RecoveryIndex))return false;
        if(new FileInfo(RecoveryIndex).Length>MaximumBytes+65536)throw new IOException("剪贴板恢复副本过大");
        var entries=DecodeIndex(File.ReadAllBytes(RecoveryIndex));
        var originals=entries.OfType<JsonObject>().Where(row=>Kind(row)=="image")
            .DistinctBy(row=>row["imageRef"]!.GetValue<string>(),StringComparer.OrdinalIgnoreCase).ToArray();
        // Check the complete snapshot before restoring the active index or changing identity.
        foreach(var row in originals)
        {
            string reference=row["imageRef"]!.GetValue<string>();
            string expected=row["imageSha256"]!.GetValue<string>();
            string active=ImagePath(reference);
            _=DecodeImageBlob(File.Exists(active)?active:RecoveryImage(reference),row,expected);
        }
        string savedDevice=Path.Combine(recoveryDirectory,"device-id.txt");
        if(!Guid.TryParse(File.ReadAllText(savedDevice).Trim(),out _))
            throw new IOException("剪贴板恢复副本的设备标识无效");
        Directory.CreateDirectory(directory);
        Directory.CreateDirectory(imageDirectory);
        foreach(var row in originals)
        {
            string reference=row["imageRef"]!.GetValue<string>(), active=ImagePath(reference);
            if(!File.Exists(active))AtomicCopy(RecoveryImage(reference),active,overwrite:false);
        }
        string device=Path.Combine(directory,"device-id.txt");
        if(!File.Exists(device))AtomicCopy(savedDevice,device,overwrite:false);
        AtomicCopy(RecoveryIndex,path,overwrite:false);
        snapshotLength=-1;
        return true;
    }

    byte[] RestoreMissingImage(string active,JsonObject entry,string expected)
    {
        string reference=NormalizeImageRef(entry["imageRef"]!.GetValue<string>());
        if(!ImageReferences(LoadCore()["entries"]!.AsArray().OfType<JsonObject>()).Contains(reference,StringComparer.OrdinalIgnoreCase))
            throw new IOException("图片记录已移除");
        if(File.Exists(active))return DecodeImageBlob(active,entry,expected);
        string backup=RecoveryImage(reference);
        byte[] decoded=DecodeImageBlob(backup,entry,expected);
        Directory.CreateDirectory(imageDirectory);
        AtomicCopy(backup,active,overwrite:false);
        return decoded;
    }

    static void AtomicCopy(string source,string target,bool overwrite=true)
    {
        string temporary=target+"."+Guid.NewGuid().ToString("N")+".tmp";
        try
        {
            using(var input=File.OpenRead(source))
            using(var output=new FileStream(temporary,FileMode.CreateNew,FileAccess.Write,FileShare.None))
            {
                input.CopyTo(output);
                output.Flush(true);
            }
            File.Move(temporary,target,overwrite);
        }
        finally{if(File.Exists(temporary))File.Delete(temporary);}
    }

    void CheckRecoverySeparation(string images)
    {
        if(recoveryDirectory==null)return;
        static bool ContainsPath(string root,string child)
        {
            root=Path.TrimEndingDirectorySeparator(Path.GetFullPath(root));
            child=Path.TrimEndingDirectorySeparator(Path.GetFullPath(child));
            return string.Equals(root,child,StringComparison.OrdinalIgnoreCase)
                || child.StartsWith(root+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase);
        }
        if(ContainsPath(directory,recoveryDirectory)||ContainsPath(recoveryDirectory,directory)
            ||ContainsPath(images,recoveryDirectory)||ContainsPath(recoveryDirectory,images))
            throw new IOException("剪贴板恢复副本需要独立的目录");
    }
}
