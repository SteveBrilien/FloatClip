using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using FloatClip.Share;

namespace FloatClip.Desktop;

/** Persists only files explicitly added to the Windows shelf; file contents are never read here. */
public sealed record LocalShelfEntry(string Path,string Name,long Size,long LastWriteUtcTicks,long AddedUtcTicks,long CreationUtcTicks=0,bool Kept=false)
{
    public bool IsAvailable
    {
        get
        {
            try
            {
                var info=new FileInfo(Path);
                return info.Exists&&(info.Attributes&FileAttributes.ReparsePoint)==0&&info.Length<=Wire.MaxFile;
            }
            catch{return false;}
        }
    }
}

public sealed class FileShelfStore
{
    public const int MaximumEntries=200;
    const long MaximumMetadataBytes=256*1024;
    readonly string path;
    readonly object gate=new();
    public FileShelfStore(string path){this.path=Path.GetFullPath(path);Directory.CreateDirectory(System.IO.Path.GetDirectoryName(this.path)!);}
    public LocalShelfEntry[] Load()
    {
        lock(gate)
        {
            if(!File.Exists(path))return Array.Empty<LocalShelfEntry>();
            if(new FileInfo(path).Length>MaximumMetadataBytes)throw new IOException("文件清单元数据过大，未修改现有清单");
            List<LocalShelfEntry> rows;
            try{rows=JsonSerializer.Deserialize<List<LocalShelfEntry>>(File.ReadAllText(path))??new();}
            catch{throw new IOException("文件清单损坏，已保留原文件");}
            return rows.Where(r=>r is not null&&ValidMetadata(r)).GroupBy(r=>System.IO.Path.GetFullPath(r.Path),StringComparer.OrdinalIgnoreCase).Select(g=>RefreshMetadata(g.Last())).Take(MaximumEntries).ToArray();
        }
    }
    static bool ValidMetadata(LocalShelfEntry row)
    {
        if(row is null||string.IsNullOrWhiteSpace(row.Path)||!System.IO.Path.IsPathFullyQualified(row.Path)||string.IsNullOrWhiteSpace(row.Name)||row.Name.Length is <1 or >240||row.Size<0||row.Size>Wire.MaxFile||row.LastWriteUtcTicks<0||row.AddedUtcTicks<0||row.CreationUtcTicks<0)return false;
        try{_ = System.IO.Path.GetFullPath(row.Path);return Wire.SafeName(row.Name)==row.Name;}catch{return false;}
    }
    static LocalShelfEntry RefreshMetadata(LocalShelfEntry row)
    {
        try
        {
            var info=new FileInfo(row.Path);
            if(info.Exists&&(info.Attributes&FileAttributes.ReparsePoint)==0&&info.Length<=Wire.MaxFile)
                return row with{Name=Wire.SafeName(info.Name),Size=info.Length,LastWriteUtcTicks=info.LastWriteTimeUtc.Ticks,CreationUtcTicks=info.CreationTimeUtc.Ticks};
        }
        catch{}
        return row;
    }
    public LocalShelfEntry Add(string filePath)
    {
        string full=System.IO.Path.GetFullPath(filePath);var info=new FileInfo(full);
        if(!info.Exists||(info.Attributes&FileAttributes.ReparsePoint)!=0)throw new IOException("请选择本机存在的普通文件");
        if(info.Length>Wire.MaxFile)throw new IOException("单个文件最大 64 MiB");
        Wire.SafeName(info.Name);
        lock(gate)
        {
            var existing=Load();bool kept=existing.Any(r=>r.Kept&&string.Equals(r.Path,full,StringComparison.OrdinalIgnoreCase));
            var rows=existing.Where(r=>!string.Equals(r.Path,full,StringComparison.OrdinalIgnoreCase)).ToList();
            if(rows.Count>=MaximumEntries)throw new IOException("文件清单最多保留 200 项，请先移除不需要的条目");
            var row=new LocalShelfEntry(full,info.Name,info.Length,info.LastWriteTimeUtc.Ticks,DateTime.UtcNow.Ticks,info.CreationTimeUtc.Ticks,kept);
            rows.Add(row);Save(rows);return row;
        }
    }
    public bool Remove(string filePath)
    {
        string full=System.IO.Path.GetFullPath(filePath);
        lock(gate)
        {
            var rows=Load().ToList();int count=rows.RemoveAll(r=>!r.Kept&&string.Equals(r.Path,full,StringComparison.OrdinalIgnoreCase));
            if(count>0)Save(rows);return count>0;
        }
    }
    public void Keep(string filePath,bool kept){lock(gate){var rows=Load().Select(r=>string.Equals(r.Path,filePath,StringComparison.OrdinalIgnoreCase)?r with{Kept=kept}:r).ToList();Save(rows);}}
    void Save(List<LocalShelfEntry> rows)
    {
        if(rows.Count>MaximumEntries)throw new IOException("文件清单最多保留 200 项");
        byte[] bytes=JsonSerializer.SerializeToUtf8Bytes(rows);
        if(bytes.Length>MaximumMetadataBytes)throw new IOException("文件清单元数据过大");
        string temporary=path+"."+Guid.NewGuid().ToString("N")+".tmp";
        try
        {
            using(var stream=new FileStream(temporary,FileMode.CreateNew,FileAccess.Write,FileShare.None)){stream.Write(bytes);stream.Flush(true);}
            File.Move(temporary,path,true);
        }
        finally{if(File.Exists(temporary))File.Delete(temporary);}
    }
}
