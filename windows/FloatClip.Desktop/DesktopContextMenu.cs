using System;
using System.Diagnostics;
using Microsoft.Win32;

namespace FloatClip.Desktop;

public static class DesktopContextMenu
{
    const string DesktopKey=@"Software\Classes\DesktopBackground\Shell\FloatClip";
    const string FolderKey=@"Software\Classes\Directory\Background\shell\FloatClip";

    static string Command=>$"\"{Environment.ProcessPath??Process.GetCurrentProcess().MainModule?.FileName}\" --clipboard";

    public static bool Enabled
    {
        get
        {
            using var key=Registry.CurrentUser.OpenSubKey(DesktopKey+@"\command");
            return string.Equals(key?.GetValue(null) as string,Command,StringComparison.OrdinalIgnoreCase);
        }
    }

    public static void SetEnabled(bool enabled)
    {
        foreach(string root in new[]{DesktopKey,FolderKey})
        {
            if(!enabled){Registry.CurrentUser.DeleteSubKeyTree(root,false);continue;}
            using(var key=Registry.CurrentUser.CreateSubKey(root,true))
            {
                key.SetValue(null,"FloatClip 剪贴板");
                key.SetValue("Icon",Environment.ProcessPath??Process.GetCurrentProcess().MainModule?.FileName??"");
                key.SetValue("Position","Top");
            }
            using var command=Registry.CurrentUser.CreateSubKey(root+@"\command",true);
            command.SetValue(null,Command);
        }
    }
}
