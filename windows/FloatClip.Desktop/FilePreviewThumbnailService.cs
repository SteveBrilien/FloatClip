using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Windows.Data.Pdf;
using Windows.Storage;
using Windows.Storage.Streams;

namespace FloatClip.Desktop;

sealed class FilePreviewThumbnailService:IDisposable
{
    sealed class Entry(ImageSource image,long bytes,LinkedListNode<string> node)
    {
        public readonly ImageSource Image=image;
        public readonly long Bytes=bytes;
        public readonly LinkedListNode<string> Node=node;
    }
    readonly string directory;
    readonly SemaphoreSlim workers=new(2);
    readonly object gate=new();
    readonly Dictionary<string,Entry> memory=new();
    readonly LinkedList<string> lru=new();
    long memoryBytes;
    bool disposed;
    const long MemoryBudget=24L*1024*1024;

    public FilePreviewThumbnailService(string preferencesRoot)
    {
        directory=Path.Combine(preferencesRoot,"PreviewCache");
        Directory.CreateDirectory(directory);
    }

    public static bool Supports(string path)=>Path.GetExtension(path).ToLowerInvariant() is
        ".png" or ".jpg" or ".jpeg" or ".bmp" or ".gif" or ".webp" or ".pdf";

    static string Key(string path)
    {
        var info=new FileInfo(path);
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(
            Path.GetFullPath(path).ToLowerInvariant()+"|"+info.Length+"|"+info.LastWriteTimeUtc.Ticks))).ToLowerInvariant();
    }

    bool TryMemory(string key,out ImageSource? image)
    {
        lock(gate)
        {
            if(!memory.TryGetValue(key,out var entry)){image=null;return false;}
            lru.Remove(entry.Node);lru.AddLast(entry.Node);image=entry.Image;return true;
        }
    }

    ImageSource Store(string key,BitmapSource image)
    {
        image.Freeze();long bytes=Math.Max(4096L,(long)image.PixelWidth*image.PixelHeight*4);
        lock(gate)
        {
            if(memory.TryGetValue(key,out var old)){lru.Remove(old.Node);memoryBytes-=old.Bytes;memory.Remove(key);}
            var node=lru.AddLast(key);memory[key]=new Entry(image,bytes,node);memoryBytes+=bytes;
            while((memoryBytes>MemoryBudget||memory.Count>64)&&lru.First is { } first)
            {
                string victim=first.Value;lru.RemoveFirst();
                if(memory.Remove(victim,out var removed))memoryBytes-=removed.Bytes;
            }
        }
        return image;
    }

    static BitmapSource Decode(byte[] png)
    {
        using var input=new MemoryStream(png);
        var image=new BitmapImage();image.BeginInit();image.CacheOption=BitmapCacheOption.OnLoad;image.StreamSource=input;image.EndInit();image.Freeze();return image;
    }

    byte[]? ReadDisk(string key)
    {
        string path=Path.Combine(directory,key+".bin");
        try
        {
            if(!File.Exists(path)||new FileInfo(path).Length>2*1024*1024)return null;
            var bytes=ProtectedData.Unprotect(File.ReadAllBytes(path),null,DataProtectionScope.CurrentUser);
            File.SetLastWriteTimeUtc(path,DateTime.UtcNow);return bytes.Length<=1500000?bytes:null;
        }
        catch{try{File.Delete(path);}catch{}return null;}
    }

    void WriteDisk(string key,BitmapSource image)
    {
        try
        {
            var encoder=new PngBitmapEncoder();encoder.Frames.Add(BitmapFrame.Create(image));using var output=new MemoryStream();encoder.Save(output);
            if(output.Length>1500000)return;
            string path=Path.Combine(directory,key+".bin"),temp=path+".tmp";
            File.WriteAllBytes(temp,ProtectedData.Protect(output.ToArray(),null,DataProtectionScope.CurrentUser));File.Move(temp,path,true);
            long bytes=0;int count=0;
            foreach(var file in Directory.EnumerateFiles(directory,"*.bin").Select(x=>new FileInfo(x)).OrderByDescending(x=>x.LastWriteTimeUtc))
            {bytes+=file.Length;count++;if(count>160||bytes>64L*1024*1024)try{file.Delete();}catch{}}
        }
        catch{}
    }

    static BitmapSource DecodeImageFile(string path)
    {
        using var input=new FileStream(path,FileMode.Open,FileAccess.Read,FileShare.ReadWrite|FileShare.Delete);
        var image=new BitmapImage();image.BeginInit();image.CacheOption=BitmapCacheOption.OnLoad;image.DecodePixelWidth=256;image.StreamSource=input;image.EndInit();image.Freeze();return image;
    }

    static async Task<BitmapSource?> DecodePdf(string path,CancellationToken token)
    {
        var file=await StorageFile.GetFileFromPathAsync(path);var pdf=await PdfDocument.LoadFromFileAsync(file);
        if(pdf.PageCount==0)return null;token.ThrowIfCancellationRequested();
        using var page=pdf.GetPage(0);using var output=new InMemoryRandomAccessStream();
        await page.RenderToStreamAsync(output,new PdfPageRenderOptions{DestinationWidth=320});token.ThrowIfCancellationRequested();
        output.Seek(0);using var stream=output.AsStreamForRead();
        var image=new BitmapImage();image.BeginInit();image.CacheOption=BitmapCacheOption.OnLoad;image.StreamSource=stream;image.EndInit();image.Freeze();return image;
    }

    public async Task<ImageSource?> GetAsync(string path,CancellationToken token)
    {
        if(disposed||!File.Exists(path)||!Supports(path))return null;
        string key;try{key=Key(path);}catch{return null;}
        if(TryMemory(key,out var cached))return cached;
        await workers.WaitAsync(token).ConfigureAwait(false);
        try
        {
            if(TryMemory(key,out cached))return cached;
            var disk=await Task.Run(()=>ReadDisk(key),token).ConfigureAwait(false);
            if(disk!=null)try{return Store(key,Decode(disk));}catch{}
            token.ThrowIfCancellationRequested();
            BitmapSource? generated;
            if(Path.GetExtension(path).Equals(".pdf",StringComparison.OrdinalIgnoreCase))
                generated=await DecodePdf(path,token);
            else generated=await Task.Run(()=>DecodeImageFile(path),token).ConfigureAwait(false);
            if(generated==null)return null;
            var stored=(BitmapSource)Store(key,generated);
            _=Task.Run(()=>WriteDisk(key,stored));
            return stored;
        }
        catch(OperationCanceledException){return null;}catch(IOException){return null;}catch(UnauthorizedAccessException){return null;}catch{return null;}
        finally{workers.Release();}
    }

    public void Dispose(){disposed=true;workers.Dispose();lock(gate){memory.Clear();lru.Clear();memoryBytes=0;}}
}
