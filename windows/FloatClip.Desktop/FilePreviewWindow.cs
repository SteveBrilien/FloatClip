using System;
using System.Linq;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using FloatClip.Desktop.Design;
using FloatClip.Desktop.Services;
using Windows.Data.Pdf;
using Windows.Storage;
using Windows.Storage.Streams;

namespace FloatClip.Desktop;

/** Read-only contents preview. Unsupported files never masquerade as a large icon. */
public sealed class FilePreviewWindow:Window
{
    public FilePreviewWindow(string path)
    {
        Title=Path.GetFileName(path);
        Width=760;Height=620;MinWidth=420;MinHeight=340;
        WindowStartupLocation=WindowStartupLocation.CenterOwner;
        SetResourceReference(BackgroundProperty,"FloatClip.Background");
        var pane=new FilePreviewPane(path);
        Content=pane;
        ClipboardBackgroundService.Configure(this,DesignPreferences.Load(ClipboardHost.Current?.PreferencesRoot??""),caption:true);
        SourceInitialized+=(_,_)=>{WindowAppearance.Apply(this);WindowAppearance.Theme(this,Wpf.Ui.Appearance.ApplicationThemeManager.GetAppTheme()==Wpf.Ui.Appearance.ApplicationTheme.Dark);};
        Closed+=(_,_)=>pane.Dispose();
    }

    internal static bool CanPreview(string path)=>DocumentPreviewService.CanPreview(path);
    internal static bool CanOpenDocument(string path)=>FilePreviewPane.CanOpenDocument(path);
}

internal sealed class FilePreviewPane:UserControl,IDisposable
{
    readonly string path;
    readonly ContentControl preview=new();
    readonly TextBlock hint=new(){Text="正在准备预览…",TextWrapping=TextWrapping.Wrap};
    readonly CancellationTokenSource lifetime=new();
    readonly Button previous,next;
    readonly TextBlock pageLabel=new(){VerticalAlignment=VerticalAlignment.Center};
    readonly StackPanel pdfControls=new(){Orientation=Orientation.Horizontal,Visibility=Visibility.Collapsed};
    readonly StackPanel zoomControls=new(){Orientation=Orientation.Horizontal,Visibility=Visibility.Collapsed};
    readonly ScrollViewer imageScroll=new(){HorizontalScrollBarVisibility=ScrollBarVisibility.Auto,VerticalScrollBarVisibility=ScrollBarVisibility.Auto,PanningMode=PanningMode.Both};
    readonly Image imageView=new(){Stretch=Stretch.Uniform,HorizontalAlignment=HorizontalAlignment.Center,VerticalAlignment=VerticalAlignment.Center,Margin=new Thickness(12)};
    readonly Dictionary<uint,BitmapSource> pdfPages=new();
    PdfDocument? pdf;uint pageIndex;bool rendering;double imageZoom;
    bool started;
    public FilePreviewPane(string path)
    {
        this.path=Path.GetFullPath(path);
        UseLayoutRounding=true;SnapsToDevicePixels=true;TextOptions.SetTextFormattingMode(this,TextFormattingMode.Display);
        SetResourceReference(BackgroundProperty,"FloatClip.Background");SetResourceReference(ForegroundProperty,"FloatClip.TextPrimary");SetResourceReference(FontFamilyProperty,"FloatClip.FontFamily");SetResourceReference(FontSizeProperty,"FloatClip.Font.Body");
        var root=new DockPanel{Margin=(Thickness)Application.Current.FindResource("FloatClip.Padding.Page")};Content=root;
        var tools=new WrapPanel{Margin=new(0,DesignTokens.Number("Space.12"),0,0)};DockPanel.SetDock(tools,Dock.Bottom);root.Children.Add(tools);
        Button Action(string name,Action action){var button=new Wpf.Ui.Controls.Button{Content=name,Margin=new(0,0,DesignTokens.Number("Space.8"),0)};DesignTokens.Button(button);button.Click+=(_,_)=>{try{action();}catch{hint.Text="操作未完成，请重试。";}};tools.Children.Add(button);return button;}
        Action("在资源管理器中显示",()=>{var start=new ProcessStartInfo("explorer.exe"){UseShellExecute=true};start.ArgumentList.Add("/select,"+this.path);Process.Start(start);});
        if(CanOpenDocument(path))Action("用默认应用打开",()=>Process.Start(new ProcessStartInfo(this.path){UseShellExecute=true}));
        void Zoom(double value){imageZoom=value;ApplyImageZoom();}
        var fit=Action("适应窗口",()=>Zoom(0));var one=Action("100%",()=>Zoom(1));
        var minus=Action("−",()=>Zoom(Math.Max(.1,(imageZoom==0?1:imageZoom)/1.25)));
        var plus=Action("+",()=>Zoom(Math.Min(8,(imageZoom==0?1:imageZoom)*1.25)));
        foreach(var button in new[]{fit,one,minus,plus}){tools.Children.Remove(button);zoomControls.Children.Add(button);}
        tools.Children.Add(zoomControls);
        var header=new StackPanel{Margin=new(0,0,0,DesignTokens.Number("Space.12"))};DockPanel.SetDock(header,Dock.Top);root.Children.Add(header);
        header.Children.Add(new TextBlock{Text=Path.GetFileName(path),FontSize=DesignTokens.Number("Font.Section"),FontWeight=FontWeights.SemiBold,TextTrimming=TextTrimming.CharacterEllipsis});
        hint.FontSize=DesignTokens.Number("Font.Caption");hint.SetResourceReference(TextBlock.ForegroundProperty,"FloatClip.TextSecondary");header.Children.Add(hint);
        previous=new Wpf.Ui.Controls.Button{Content="上一页"};next=new Wpf.Ui.Controls.Button{Content="下一页"};foreach(var button in new[]{previous,next}){DesignTokens.Button(button,"Toolbar");button.Margin=new(0,0,DesignTokens.Number("Space.8"),0);}
        previous.Click+=async(_,_)=>{if(pageIndex>0){pageIndex--;await RenderPdf();}};next.Click+=async(_,_)=>{if(pdf!=null&&pageIndex+1<pdf.PageCount){pageIndex++;await RenderPdf();}};
        pdfControls.Children.Add(previous);pdfControls.Children.Add(pageLabel);pdfControls.Children.Add(next);header.Children.Add(pdfControls);root.Children.Add(preview);
        imageScroll.Content=imageView;imageScroll.SizeChanged+=(_,_)=>ApplyImageZoom();
        imageScroll.PreviewMouseWheel+=(_,e)=>{if(Keyboard.Modifiers.HasFlag(ModifierKeys.Control)){Zoom(e.Delta>0?Math.Min(8,(imageZoom==0?1:imageZoom)*1.12):Math.Max(.1,(imageZoom==0?1:imageZoom)/1.12));e.Handled=true;}};
        PreviewKeyDown+=async(_,e)=>{if(pdf!=null&&e.Key==Key.Left&&pageIndex>0){pageIndex--;await RenderPdf();e.Handled=true;}else if(pdf!=null&&e.Key==Key.Right&&pageIndex+1<pdf.PageCount){pageIndex++;await RenderPdf();e.Handled=true;}};
        Loaded+=async(_,_)=>{
            if(started||lifetime.IsCancellationRequested)return;
            started=true;
            await LoadPreview();
        };
    }
    public void Dispose()=>lifetime.Cancel();
    async Task LoadPreview()
    {
        try
        {
            string ext=Path.GetExtension(path).ToLowerInvariant();
            if(ext==".pdf"){var file=await StorageFile.GetFileFromPathAsync(path);pdf=await PdfDocument.LoadFromFileAsync(file);lifetime.Token.ThrowIfCancellationRequested();pdfControls.Visibility=Visibility.Visible;await RenderPdf();return;}
            if(ext is ".png" or ".jpg" or ".jpeg" or ".bmp" or ".gif" or ".webp")
            {
                var bitmap=await Task.Run(()=>LoadImage(path,2400),lifetime.Token);lifetime.Token.ThrowIfCancellationRequested();
                ShowImage(bitmap);hint.Text=FileSizeFormatter.Format(new FileInfo(path).Length)+"  |  图片";return;
            }
            if(!CanPreview(path)){hint.Text="此类型暂不支持内嵌预览，可在资源管理器中查看。";return;}
            var document=await DocumentPreviewService.ReadAsync(path,lifetime.Token);lifetime.Token.ThrowIfCancellationRequested();
            hint.Text=FileSizeFormatter.Format(new FileInfo(path).Length)+"  |  "+document.Description;
            if(document.Entries!=null)
            {
                var list=new ListView{ItemsSource=document.Entries,BorderThickness=new(0)};list.SetResourceReference(Control.BackgroundProperty,"FloatClip.Surface");list.SetResourceReference(Control.ForegroundProperty,"FloatClip.TextPrimary");list.ItemContainerStyle=(Style)Application.Current.FindResource("FloatClip.File.Row");list.Resources.Add(typeof(GridViewColumnHeader),Application.Current.FindResource("FloatClip.File.Header"));
                var grid=new GridView();foreach(var col in new[]{("名称","Name",390.0),("大小","Size",100.0),("修改时间","Modified",160.0)})grid.Columns.Add(new GridViewColumn{Header=col.Item1,DisplayMemberBinding=new Binding(col.Item2),Width=col.Item3});
                list.View=grid;list.SizeChanged+=(_,_)=>grid.Columns[0].Width=Math.Max(120,list.ActualWidth-280);preview.Content=list;
            }
            else
            {
                var text=new TextBox{Text=document.Text,IsReadOnly=true,TextWrapping=TextWrapping.Wrap,VerticalScrollBarVisibility=ScrollBarVisibility.Auto,BorderThickness=new(0),Padding=(Thickness)Application.Current.FindResource("FloatClip.Padding.Card")};text.SetResourceReference(Control.BackgroundProperty,"FloatClip.Surface");text.SetResourceReference(Control.ForegroundProperty,"FloatClip.TextPrimary");preview.Content=text;
            }
        }
        catch(OperationCanceledException){}
        catch{if(!lifetime.IsCancellationRequested)hint.Text="此文件暂无法内嵌预览，可在资源管理器中查看。";}
    }
    async Task RenderPdf()
    {
        if(pdf==null||rendering||lifetime.IsCancellationRequested)return;rendering=true;previous.IsEnabled=next.IsEnabled=false;
        try
        {
            if(!pdfPages.TryGetValue(pageIndex,out var bitmap))
            {
                using var page=pdf.GetPage(pageIndex);using var output=new InMemoryRandomAccessStream();
                uint destination=(uint)Math.Clamp(ActualWidth*1.65,900,1800);
                await page.RenderToStreamAsync(output,new PdfPageRenderOptions{DestinationWidth=destination});lifetime.Token.ThrowIfCancellationRequested();
                output.Seek(0);byte[] bytes;
                using(var stream=output.AsStreamForRead())using(var memory=new MemoryStream()){await stream.CopyToAsync(memory,lifetime.Token);bytes=memory.ToArray();}
                bitmap=await Task.Run(()=>LoadImage(bytes,0),lifetime.Token);pdfPages[pageIndex]=bitmap;
                while(pdfPages.Count>3)
                {
                    uint victim=pdfPages.Keys.OrderByDescending(key=>Math.Abs((long)key-pageIndex)).First();pdfPages.Remove(victim);
                }
            }
            ShowImage(bitmap);pageLabel.Text=$"{pageIndex+1} / {pdf.PageCount}";hint.Text=FileSizeFormatter.Format(new FileInfo(path).Length)+"  |  PDF · ← / → 翻页";
        }
        catch(OperationCanceledException){}catch{if(!lifetime.IsCancellationRequested)hint.Text="此页暂无法预览。";}
        finally{rendering=false;previous.IsEnabled=pageIndex>0;next.IsEnabled=pdf!=null&&pageIndex+1<pdf.PageCount;}
    }
    internal static bool CanPreview(string path)=>DocumentPreviewService.CanPreview(path);
    internal static bool CanOpenDocument(string path)=>Path.GetExtension(path).ToLowerInvariant() is ".pdf" or ".doc" or ".docx" or ".xls" or ".xlsx" or ".ppt" or ".pptx" or ".txt" or ".md" or ".csv" or ".png" or ".jpg" or ".jpeg" or ".bmp" or ".gif" or ".webp" or ".rtf";
    static BitmapSource LoadImage(string path,int decodeWidth){using var stream=new FileStream(path,FileMode.Open,FileAccess.Read,FileShare.ReadWrite|FileShare.Delete);return LoadImage(stream,decodeWidth);}
    static BitmapSource LoadImage(byte[] bytes,int decodeWidth){using var stream=new MemoryStream(bytes);return LoadImage(stream,decodeWidth);}
    static BitmapSource LoadImage(Stream stream,int decodeWidth)
    {
        var image=new BitmapImage();image.BeginInit();image.CacheOption=BitmapCacheOption.OnLoad;if(decodeWidth>0)image.DecodePixelWidth=decodeWidth;image.StreamSource=stream;image.EndInit();image.Freeze();return image;
    }
    void ShowImage(BitmapSource bitmap)
    {
        imageView.Source=bitmap;imageZoom=0;zoomControls.Visibility=Visibility.Visible;preview.Content=imageScroll;ApplyImageZoom();
    }
    void ApplyImageZoom()
    {
        if(imageView.Source is not BitmapSource bitmap)return;
        if(imageZoom==0)
        {
            imageView.Width=double.NaN;imageView.Height=double.NaN;imageView.MaxWidth=Math.Max(1,imageScroll.ActualWidth-28);imageView.MaxHeight=Math.Max(1,imageScroll.ActualHeight-28);
        }
        else
        {
            imageView.MaxWidth=imageView.MaxHeight=double.PositiveInfinity;imageView.Width=bitmap.PixelWidth*imageZoom;imageView.Height=bitmap.PixelHeight*imageZoom;
        }
    }
}
