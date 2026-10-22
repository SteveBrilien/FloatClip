using System;
using System.Collections.Concurrent;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace FloatClip.Desktop;

/** Read the registered extension icon, without opening the user's file or running a handler. */
public static class ShellFileIcons
{
    static readonly ConcurrentDictionary<string,ImageSource> cache=new(StringComparer.OrdinalIgnoreCase);
    [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)]
    struct Info {public IntPtr Icon;public int Index;public uint Attributes;[MarshalAs(UnmanagedType.ByValTStr,SizeConst=260)]public string Name;[MarshalAs(UnmanagedType.ByValTStr,SizeConst=80)]public string Type;}
    [DllImport("shell32.dll",CharSet=CharSet.Unicode)]static extern IntPtr SHGetFileInfo(string path,uint attributes,out Info info,uint size,uint flags);
    [DllImport("user32.dll")]static extern bool DestroyIcon(IntPtr icon);
    public static ImageSource For(string name)
    {
        string extension=System.IO.Path.GetExtension(name);if(extension.Length>32)extension="";
        return cache.GetOrAdd(extension,ext=>{
            SHGetFileInfo("file"+ext,0x80,out var info,(uint)Marshal.SizeOf<Info>(),0x100|0x1|0x10);
            if(info.Icon==IntPtr.Zero)return new DrawingImage();
            try{var image=Imaging.CreateBitmapSourceFromHIcon(info.Icon,Int32Rect.Empty,BitmapSizeOptions.FromWidthAndHeight(20,20));image.Freeze();return image;}finally{DestroyIcon(info.Icon);}
        });
    }
}
