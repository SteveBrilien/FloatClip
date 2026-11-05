using System;
using System.IO;
using System.Text.Json;

namespace FloatClip.Desktop;

public sealed class DesktopStoragePreferences
{
    public string ImageCachePath{get;set;}="";
    public string DefaultSavePath{get;set;}="";

    public static string DefaultDownloads()
    {
        string downloads=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),"Downloads");
        return Directory.Exists(downloads)?downloads:Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);
    }

    public static DesktopStoragePreferences Load(string preferencesRoot,string libraryRoot,bool isolated=false)
    {
        string path=Path.Combine(preferencesRoot,"storage-preferences.json");
        DesktopStoragePreferences value;
        try
        {
            if(File.Exists(path)&&new FileInfo(path).Length<=32768)
                value=JsonSerializer.Deserialize<DesktopStoragePreferences>(File.ReadAllText(path))??new();
            else value=new();
        }
        catch{value=new();}
        string defaultImages=Path.Combine(libraryRoot,"Images");
        string defaultSave=isolated?Path.Combine(preferencesRoot,"Saved"):DefaultDownloads();
        value.ImageCachePath=NormalizeDirectory(value.ImageCachePath,defaultImages);
        value.DefaultSavePath=NormalizeDirectory(value.DefaultSavePath,defaultSave);
        return value;
    }

    public void Save(string preferencesRoot)
    {
        ImageCachePath=NormalizeDirectory(ImageCachePath,ImageCachePath);
        DefaultSavePath=NormalizeDirectory(DefaultSavePath,DefaultSavePath);
        Directory.CreateDirectory(preferencesRoot);
        string path=Path.Combine(preferencesRoot,"storage-preferences.json"),temporary=path+".tmp";
        try{File.WriteAllText(temporary,JsonSerializer.Serialize(this));File.Move(temporary,path,true);}
        finally{if(File.Exists(temporary))File.Delete(temporary);}
    }

    public static string NormalizeDirectory(string? value,string fallback)
    {
        string chosen=string.IsNullOrWhiteSpace(value)?fallback:value.Trim();
        if(!Path.IsPathFullyQualified(chosen))throw new IOException("请选择完整的文件夹路径");
        string full=Path.GetFullPath(chosen).TrimEnd(Path.DirectorySeparatorChar,Path.AltDirectorySeparatorChar);
        if(full.Length<3)throw new IOException("不能把磁盘根目录直接作为 FloatClip 缓存目录");
        Directory.CreateDirectory(full);
        var info=new DirectoryInfo(full);
        if((info.Attributes&FileAttributes.ReparsePoint)!=0)throw new IOException("暂不支持把重解析点用作 FloatClip 缓存目录");
        return full;
    }
}
