using System;
using System.Collections.Concurrent;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace FloatClip.Desktop;

/** Use the same association/image list as Explorer. Never execute the selected file. */
public static class ShellFileIcons
{
    static readonly ConcurrentDictionary<string,ImageSource> cache=new(StringComparer.OrdinalIgnoreCase);
    [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)]
    struct Info {public IntPtr Icon;public int Index;public uint Attributes;[MarshalAs(UnmanagedType.ByValTStr,SizeConst=260)]public string Name;[MarshalAs(UnmanagedType.ByValTStr,SizeConst=80)]public string Type;}
    [DllImport("shell32.dll",CharSet=CharSet.Unicode)]static extern IntPtr SHGetFileInfo(string path,uint attributes,out Info info,uint size,uint flags);
    [DllImport("shell32.dll")]static extern int SHGetImageList(int imageList,ref Guid id,out IntPtr list);
    [DllImport("comctl32.dll")]static extern IntPtr ImageList_GetIcon(IntPtr list,int index,uint flags);
    [DllImport("user32.dll")]static extern bool DestroyIcon(IntPtr icon);
    public static ImageSource For(string name)
    {
        string extension=Path.GetExtension(name);if(extension.Length>32)extension="";
        bool actual=Path.IsPathFullyQualified(name)&&File.Exists(name);
        string key=actual?name+"|"+File.GetLastWriteTimeUtc(name).Ticks:extension;
        return cache.GetOrAdd(key,_=>{
            string requested=actual?name:"file"+extension;uint attributes=actual?0:0x10u;
            IntPtr icon=IntPtr.Zero,list=IntPtr.Zero;
            try
            {
                // SHIL_EXTRALARGE preserves the native (at least 48px) frame. A 24 DIP
                // list icon therefore has enough pixels at 200%, without enlarging 16px.
                if(SHGetFileInfo(requested,0x80,out var info,(uint)Marshal.SizeOf<Info>(),0x4000|attributes)!=IntPtr.Zero)
                {
                    var id=new Guid("46EB5926-582E-4017-9FDF-E8998DAA0950");
                    if(SHGetImageList(2,ref id,out list)==0)icon=ImageList_GetIcon(list,info.Index,1);
                }
                if(icon==IntPtr.Zero){SHGetFileInfo(requested,0x80,out var fallback,(uint)Marshal.SizeOf<Info>(),0x100|attributes);icon=fallback.Icon;}
                if(icon==IntPtr.Zero)return new DrawingImage();
                var image=Imaging.CreateBitmapSourceFromHIcon(icon,Int32Rect.Empty,BitmapSizeOptions.FromEmptyOptions());image.Freeze();return image;
            }
            finally{if(icon!=IntPtr.Zero)DestroyIcon(icon);if(list!=IntPtr.Zero)Marshal.Release(list);}
        });
    }
}
