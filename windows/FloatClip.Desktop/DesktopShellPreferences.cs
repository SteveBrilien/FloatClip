using System;
using System.IO;
using System.Text.Json;

namespace FloatClip.Desktop;

public sealed class DesktopShellPreferences
{
    public uint HotkeyModifiers{get;set;}=3;
    public uint HotkeyKey{get;set;}=0x56;
    public bool Pinned{get;set;}
    public int? Left{get;set;}
    public int? Top{get;set;}
    public static bool ValidHotkey(uint modifiers,uint key)=>
        (modifiers&~15U)==0&&(modifiers&11U)!=0&&key>=0x20&&key<=0xFE&&
        key is not (0x5B or 0x5C or 0x10 or 0x11 or 0x12)&&!(modifiers==8&&key==0x56);
    public static DesktopShellPreferences Load(string path)
    {
        if(!File.Exists(path))return new();
        if(new FileInfo(path).Length>16384)throw new IOException("桌面设置文件过大");
        var value=JsonSerializer.Deserialize<DesktopShellPreferences>(File.ReadAllText(path))??throw new IOException("桌面设置损坏");
        if(!ValidHotkey(value.HotkeyModifiers,value.HotkeyKey))throw new IOException("快捷键设置无效");
        return value;
    }
    public void Save(string path)
    {
        if(!ValidHotkey(HotkeyModifiers,HotkeyKey))throw new IOException("请选择包含 Ctrl、Alt 或 Win 的组合键；Win + V 由系统使用");
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        string temporary=path+".tmp";
        try{File.WriteAllText(temporary,JsonSerializer.Serialize(this));File.Move(temporary,path,true);}
        finally{if(File.Exists(temporary))File.Delete(temporary);}
    }
}
