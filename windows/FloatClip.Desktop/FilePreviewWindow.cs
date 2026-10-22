using System;
using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Windows.Data.Pdf;
using Windows.Storage;
using Windows.Storage.Streams;

namespace FloatClip.Desktop;

/** Preview documents without treating executable files as a preview action. */
public sealed class FilePreviewWindow:Window
{
    readonly string path;
    readonly ContentControl preview=new();
    readonly TextBlock hint=new(){Text="正在准备预览…",TextWrapping=TextWrapping.Wrap};
    public FilePreviewWindow(string path)
    {
        this.path=path;Title=Path.GetFileName(path);Width=680;Height=540;MinWidth=360;MinHeight=300;WindowStartupLocation=WindowStartupLocation.CenterOwner;
        SetResourceReference(BackgroundProperty,"FloatClip.Background");SetResourceReference(ForegroundProperty,"FloatClip.Text");
        var root=new DockPanel{Margin=new(16)};Content=root;
        var tools=new StackPanel{Orientation=Orientation.Horizontal,Margin=new(0,0,0,10)};DockPanel.SetDock(tools,Dock.Bottom);root.Children.Add(tools);
        void Action(string name,Action action){var button=new Wpf.Ui.Controls.Button{Content=name,Margin=new(0,0,8,0)};button.Click+=(_,_)=>{try{action();}catch{hint.Text="操作未完成，请重试。";}};tools.Children.Add(button);}
        Action("在资源管理器中显示",()=>{var start=new ProcessStartInfo("explorer.exe"){UseShellExecute=true};start.ArgumentList.Add("/select,"+path);Process.Start(start);});
        if(CanOpenDocument(path))Action("用默认应用打开",()=>Process.Start(new ProcessStartInfo(path){UseShellExecute=true}));
        hint.Margin=new(0,0,0,10);hint.FontSize=12;hint.SetResourceReference(TextBlock.ForegroundProperty,"FloatClip.Muted");DockPanel.SetDock(hint,Dock.Top);root.Children.Add(hint);root.Children.Add(preview);
        Loaded+=async(_,_)=>{
            try{
                string ext=Path.GetExtension(path).ToLowerInvariant();
                if(ext==".pdf"){
                    var file=await StorageFile.GetFileFromPathAsync(path);var pdf=await PdfDocument.LoadFromFileAsync(file);using var page=pdf.GetPage(0);using var output=new InMemoryRandomAccessStream();
                    await page.RenderToStreamAsync(output,new PdfPageRenderOptions{DestinationWidth=1000});output.Seek(0);using var stream=output.AsStreamForRead();ShowImage(stream);hint.Text=$"PDF · 第 1 页 / 共 {pdf.PageCount} 页";
                }else if(ext is ".png" or ".jpg" or ".jpeg" or ".bmp" or ".gif" or ".webp"){
                    using var stream=File.OpenRead(path);ShowImage(stream);hint.Text=Path.GetFileName(path);
                }else if(ext is ".txt" or ".md" or ".csv" or ".json" or ".log"){
                    using var stream=File.OpenRead(path);using var reader=new StreamReader(stream);var text=new char[24000];int length=await reader.ReadAsync(text,0,text.Length);
                    preview.Content=new ScrollViewer{Content=new TextBlock{Text=new string(text,0,length),TextWrapping=TextWrapping.Wrap}};hint.Text="文字预览 · 最多显示 24000 字符";
                }else{
                    preview.Content=new Image{Source=ShellFileIcons.For(path),Width=64,Height=64,Stretch=Stretch.Uniform};hint.Text=$"{Path.GetFileName(path)} · {new FileInfo(path).Length:N0} 字节\n此类型使用系统关联图标；可在资源管理器中查看。";
                }
            }catch{hint.Text="此文件暂无法内嵌预览，可在资源管理器中查看。";}
        };
    }
    internal static bool CanOpenDocument(string path)=>Path.GetExtension(path).ToLowerInvariant() is ".pdf" or ".doc" or ".docx" or ".xls" or ".xlsx" or ".ppt" or ".pptx" or ".txt" or ".md" or ".csv" or ".png" or ".jpg" or ".jpeg" or ".bmp" or ".gif" or ".rtf";
    void ShowImage(Stream stream){var image=new BitmapImage();image.BeginInit();image.CacheOption=BitmapCacheOption.OnLoad;image.DecodePixelWidth=1200;image.StreamSource=stream;image.EndInit();image.Freeze();preview.Content=new Image{Source=image,Stretch=Stretch.Uniform};}
}
