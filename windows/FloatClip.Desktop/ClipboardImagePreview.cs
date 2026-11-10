using System;
using System.Text.Json.Nodes;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace FloatClip.Desktop;

public sealed partial class LocalClipboardWindow
{
    sealed class ThumbnailEntry(System.Windows.Media.Imaging.BitmapSource image,long bytes,LinkedListNode<string> node)
    {
        public readonly System.Windows.Media.Imaging.BitmapSource Image=image;
        public readonly long Bytes=bytes;
        public readonly LinkedListNode<string> Node=node;
    }
    readonly Dictionary<string,ThumbnailEntry> thumbnails=new();
    readonly Dictionary<(string Hash,CancellationToken Token),Task<System.Windows.Media.Imaging.BitmapSource?>> thumbnailRequests=new();
    readonly LinkedList<string> thumbnailLru=new();
    readonly System.Threading.SemaphoreSlim thumbnailWorkers=new(2);
    readonly object thumbnailLock=new();
    long thumbnailBytes;
    const long ThumbnailMemoryBudget=12L*1024*1024;

    bool TryThumbnail(string key,out System.Windows.Media.Imaging.BitmapSource? bitmap)
    {
        lock(thumbnailLock)
        {
            if(!thumbnails.TryGetValue(key,out var entry)){bitmap=null;return false;}
            thumbnailLru.Remove(entry.Node);thumbnailLru.AddLast(entry.Node);bitmap=entry.Image;return true;
        }
    }
    System.Windows.Media.Imaging.BitmapSource StoreThumbnail(string key,System.Windows.Media.Imaging.BitmapSource image,CancellationToken token)
    {
        long bytes=Math.Max(4096L,(long)image.PixelWidth*image.PixelHeight*4);
        lock(thumbnailLock)
        {
            token.ThrowIfCancellationRequested();
            if(thumbnails.TryGetValue(key,out var old)){thumbnailBytes-=old.Bytes;thumbnailLru.Remove(old.Node);thumbnails.Remove(key);}
            var node=thumbnailLru.AddLast(key);thumbnails[key]=new ThumbnailEntry(image,bytes,node);thumbnailBytes+=bytes;
            TrimThumbnailCache(ThumbnailMemoryBudget,32);
            return image;
        }
    }
    void TrimThumbnailCache(long bytes,int count)
    {
        lock(thumbnailLock)
        {
            while((thumbnailBytes>bytes||thumbnails.Count>count)&&thumbnailLru.First is { } first)
            {
                thumbnailLru.RemoveFirst();
                if(thumbnails.Remove(first.Value,out var removed))thumbnailBytes-=removed.Bytes;
            }
        }
    }

    Task<System.Windows.Media.Imaging.BitmapSource?> ThumbnailAsync(JsonObject clip,CancellationToken token)
    {
        string hash=clip["imageSha256"]!.GetValue<string>();
        lock(thumbnailLock)
        {
            if(token.IsCancellationRequested)return Task.FromResult<System.Windows.Media.Imaging.BitmapSource?>(null);
            if(TryThumbnail(hash,out var cached))return Task.FromResult(cached);
            var key=(hash,token);
            if(thumbnailRequests.TryGetValue(key,out var pending))return pending;
            var request=DecodeThumbnailAsync(clip,hash,token);
            thumbnailRequests.Add(key,request);
            _=request.ContinueWith(_=>{lock(thumbnailLock)thumbnailRequests.Remove(key);},TaskScheduler.Default);
            return request;
        }
    }

    async Task<System.Windows.Media.Imaging.BitmapSource?> DecodeThumbnailAsync(JsonObject clip,string hash,CancellationToken token)
    {
        try
        {
            await thumbnailWorkers.WaitAsync(token).ConfigureAwait(false);
            try
            {
                return await Task.Run(()=>{
                    token.ThrowIfCancellationRequested();if(TryThumbnail(hash,out var existing))return existing;
                    byte[]? disk=vault.ReadThumbnail(hash);
                    if(disk!=null)try{return StoreThumbnail(hash,DecodeImage(disk),token);}catch(Exception error)when(error is not OperationCanceledException){}
                    long width=clip["imageWidth"]!.GetValue<long>(),height=clip["imageHeight"]!.GetValue<long>();
                    using var stream=new System.IO.MemoryStream(vault.ReadImage(clip));
                    var image=new System.Windows.Media.Imaging.BitmapImage();image.BeginInit();image.CacheOption=System.Windows.Media.Imaging.BitmapCacheOption.OnLoad;
                    if(height>width)image.DecodePixelHeight=(int)Math.Min(320,height);else image.DecodePixelWidth=(int)Math.Min(320,width);
                    image.StreamSource=stream;image.EndInit();image.Freeze();token.ThrowIfCancellationRequested();
                    try
                    {
                        var encoder=new System.Windows.Media.Imaging.PngBitmapEncoder();encoder.Frames.Add(System.Windows.Media.Imaging.BitmapFrame.Create(image));
                        using var output=new System.IO.MemoryStream();encoder.Save(output);vault.WriteThumbnail(hash,output.ToArray());
                    }catch{}
                    return StoreThumbnail(hash,image,token);
                },token).ConfigureAwait(false);
            }
            finally{thumbnailWorkers.Release();}
        }
        catch(System.OperationCanceledException){return null;}catch(System.IO.IOException){return null;}catch(System.Exception){return null;}
    }
    async void LoadThumbnail(Image target,JsonObject clip,System.Threading.CancellationToken token)
    {
        var bitmap=await ThumbnailAsync(clip,token).ConfigureAwait(false);
        if(token.IsCancellationRequested || Dispatcher.HasShutdownStarted)return;
        await Dispatcher.InvokeAsync(()=>{
            if(token.IsCancellationRequested)return;
            target.Source=bitmap;
            System.Windows.Automation.AutomationProperties.SetName(target,bitmap==null?"图片暂不可用":"图片缩略图");
        });
    }
    void PreviewImage(JsonObject clip)
    {
        string key="image:"+clip["id"]!.GetValue<string>();if(ActivateAuxiliary(key))return;
        var bitmap=ClipboardImages.Decode(vault.ReadImage(clip));
        var image=new Image{Source=bitmap,Stretch=Stretch.Uniform,HorizontalAlignment=HorizontalAlignment.Center,VerticalAlignment=VerticalAlignment.Center,Margin=new(12)};
        var viewer=new ScrollViewer{Content=image,HorizontalScrollBarVisibility=ScrollBarVisibility.Auto,VerticalScrollBarVisibility=ScrollBarVisibility.Auto};
        var panel=new DockPanel();var actions=new WrapPanel{Margin=new(12,8,12,12)};double zoom=0;
        var size=MutedLabel($"{bitmap.PixelWidth} × {bitmap.PixelHeight}",new(0,8,12,0));
        void ApplyZoom(){if(zoom==0){image.Width=double.NaN;image.Height=double.NaN;image.MaxWidth=Math.Max(1,viewer.ActualWidth-28);image.MaxHeight=Math.Max(1,viewer.ActualHeight-28);}else{image.MaxWidth=image.MaxHeight=double.PositiveInfinity;image.Width=bitmap.PixelWidth*zoom;image.Height=bitmap.PixelHeight*zoom;}size.Text=$"{bitmap.PixelWidth} × {bitmap.PixelHeight} · "+(zoom==0?"适应窗口":$"{zoom*100:F0}%");}
        actions.Children.Add(Button("适应窗口",()=>{zoom=0;ApplyZoom();}));actions.Children.Add(Button("100%",()=>{zoom=1;ApplyZoom();}));
        actions.Children.Add(Button("−",()=>{zoom=Math.Max(.1,(zoom==0?1:zoom)/1.25);ApplyZoom();}));actions.Children.Add(Button("+",()=>{zoom=Math.Min(8,(zoom==0?1:zoom)*1.25);ApplyZoom();}));
        actions.Children.Add(Button("复制图片",()=>CopyImage(clip)));actions.Children.Add(Button("另存为",()=>SaveImage(clip)));actions.Children.Add(size);
        DockPanel.SetDock(actions,Dock.Bottom);panel.Children.Add(actions);panel.Children.Add(viewer);viewer.SizeChanged+=(_,_)=>ApplyZoom();
        var window=new Window{Title="图片预览",Width=900,Height=700,MinWidth=480,MinHeight=360,Content=panel};window.Loaded+=(_,_)=>ApplyZoom();ShowAuxiliary(key,window);
    }
}
