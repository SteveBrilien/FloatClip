using System;
using System.Text.Json.Nodes;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace FloatClip.Desktop;

public sealed partial class LocalClipboardWindow
{
    readonly Dictionary<string,System.Windows.Media.Imaging.BitmapSource> thumbnails=new();
    System.Windows.Media.Imaging.BitmapSource Thumbnail(JsonObject clip)
    {
        string key=clip["imageRef"]!.GetValue<string>()+":"+clip["imageSha256"]!.GetValue<string>();
        if(thumbnails.TryGetValue(key,out var cached))return cached;
        var thumbnail=DecodeImage(vault.ReadImage(clip),Math.Min(400,(int)clip["imageWidth"]!.GetValue<long>()));
        if(thumbnails.Count>=24)thumbnails.Remove(thumbnails.Keys.First());thumbnails.Add(key,thumbnail);return thumbnail;
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
