using System;
using Microsoft.Win32;

namespace FloatClip.Desktop;

public static class WindowsStartup
{
    const string Key=@"Software\Microsoft\Windows\CurrentVersion\Run";
    static string Command=>"\""+Environment.ProcessPath+"\" --background";
    public static bool Enabled
    {
        get{using var key=Registry.CurrentUser.OpenSubKey(Key);return string.Equals(key?.GetValue("FloatClip") as string,Command,StringComparison.OrdinalIgnoreCase);}
    }
    public static void SetEnabled(bool enabled)
    {
        using var key=Registry.CurrentUser.CreateSubKey(Key,true);
        if(enabled)key.SetValue("FloatClip",Command,RegistryValueKind.String);
        else key.DeleteValue("FloatClip",false);
    }
}
