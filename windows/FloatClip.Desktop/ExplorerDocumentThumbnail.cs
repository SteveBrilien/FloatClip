using System;
using System.IO;
using System.IO.Compression;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media.Imaging;

namespace FloatClip.Desktop;

/// <summary>
/// Ask the installed Explorer thumbnail provider (Office if present) for a genuine
/// rendered document thumbnail, never reconstitute Office layout from extracted text.
/// PPTX document thumbnail is a bounded fallback when there is no registered handler.
/// </summary>
internal static class ExplorerDocumentThumbnail
{
    [StructLayout(LayoutKind.Sequential)]struct SIZE{public int Width;public int Height;public SIZE(int w,int h){Width=w;Height=h;}}
    [ComImport,Guid("bcc18b79-ba16-442f-80c4-8a59c30c463b"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IShellItemImageFactory{[PreserveSig]int GetImage(SIZE size,uint flags,out IntPtr bitmap);}
    [DllImport("shell32.dll",CharSet=CharSet.Unicode,PreserveSig=false)]
    static extern void SHCreateItemFromParsingName(string path,IntPtr bindContext,ref Guid iid,
        [MarshalAs(UnmanagedType.Interface)]out IShellItemImageFactory result);
    [DllImport("gdi32.dll")]static extern bool DeleteObject(IntPtr bitmap);

    public static Task<BitmapSource?> GetAsync(string path,CancellationToken token)=>
        Task.Run(()=>Get(path,token),token);

    static BitmapSource? Get(string path,CancellationToken token)
    {
        token.ThrowIfCancellationRequested();
        if(!File.Exists(path))return null;
        IShellItemImageFactory? factory=null;IntPtr handle=IntPtr.Zero;
        try
        {
            Guid iid=new("bcc18b79-ba16-442f-80c4-8a59c30c463b");
            SHCreateItemFromParsingName(path,IntPtr.Zero,ref iid,out factory);
            // SIIGBF_THUMBNAILONLY: no fallback to a stretched generic file icon.
            if(factory.GetImage(new SIZE(1600,1200),0x8|0x1,out handle)==0&&handle!=IntPtr.Zero)
            {
                var source=Imaging.CreateBitmapSourceFromHBitmap(handle,IntPtr.Zero,Int32Rect.Empty,
                    BitmapSizeOptions.FromEmptyOptions());source.Freeze();return source;
            }
        }
        catch(COMException){}catch(ExternalException){}catch(UnauthorizedAccessException){}
        finally
        {
            if(handle!=IntPtr.Zero)DeleteObject(handle);
            if(factory!=null)Marshal.ReleaseComObject(factory);
        }
        token.ThrowIfCancellationRequested();
        if(!Path.GetExtension(path).Equals(".pptx",StringComparison.OrdinalIgnoreCase))return null;
        try
        {
            using var archive=ZipFile.OpenRead(path);
            var preview=archive.GetEntry("docProps/thumbnail.jpeg")??archive.GetEntry("docProps/thumbnail.png");
            if(preview==null||preview.Length<16||preview.Length>5*1024*1024)return null;
            using var stream=preview.Open();using var memory=new MemoryStream();
            stream.CopyTo(memory);memory.Position=0;
            var bitmap=new BitmapImage();bitmap.BeginInit();bitmap.CacheOption=BitmapCacheOption.OnLoad;
            bitmap.StreamSource=memory;bitmap.EndInit();bitmap.Freeze();return bitmap;
        }
        catch(IOException){return null;}catch(InvalidDataException){return null;}
    }
}
