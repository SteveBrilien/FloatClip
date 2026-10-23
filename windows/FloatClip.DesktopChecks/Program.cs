using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Net;
using System.Net.Sockets;
using System.Buffers.Binary;
using System.Threading.Tasks;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using FloatClip.Desktop;
using FloatClip.Share;

static class Program
{
    static int checks;
    static readonly string output=Path.GetFullPath("analysis/desktop-checks");
    static void Check(bool value,string label){if(!value)throw new Exception(label);checks++;}
    static T Field<T>(object instance,string name)=>(T)instance.GetType().GetField(name,BindingFlags.Instance|BindingFlags.NonPublic)!.GetValue(instance)!;
    static System.Collections.Generic.IEnumerable<DependencyObject> Descendants(DependencyObject root){yield return root;for(int i=0;i<VisualTreeHelper.GetChildrenCount(root);i++)foreach(var child in Descendants(VisualTreeHelper.GetChild(root,i)))yield return child;}
    static object? Call(object instance,string name,params object[] args)=>instance.GetType().GetMethod(name,BindingFlags.Instance|BindingFlags.NonPublic)!.Invoke(instance,args);
    static void Pump(int milliseconds=0)
    {
        var frame=new DispatcherFrame();
        if(milliseconds==0)Dispatcher.CurrentDispatcher.BeginInvoke(DispatcherPriority.ApplicationIdle,new Action(()=>frame.Continue=false));
        else{var timer=new DispatcherTimer{Interval=TimeSpan.FromMilliseconds(milliseconds)};timer.Tick+=(_,_)=>{timer.Stop();frame.Continue=false;};timer.Start();}
        Dispatcher.PushFrame(frame);
    }
    static void Snapshot(Window window,string name)
    {
        window.UpdateLayout();var surface=(FrameworkElement)window.Content;
        var bitmap=new RenderTargetBitmap((int)Math.Ceiling(surface.ActualWidth),(int)Math.Ceiling(surface.ActualHeight),96,96,PixelFormats.Pbgra32);bitmap.Render(surface);
        var encoder=new PngBitmapEncoder();encoder.Frames.Add(BitmapFrame.Create(bitmap));using var file=File.Create(Path.Combine(output,name+".png"));encoder.Save(file);
    }
    static void CheckFileTransfer(string dataRoot)
    {
        var listener=new TcpListener(IPAddress.Loopback,0);TcpClient? senderClient=null,receiverClient=null;Session? sender=null,receiver=null;
        try
        {
            listener.Start();senderClient=new TcpClient();
            var connecting=senderClient.ConnectAsync(IPAddress.Loopback,((IPEndPoint)listener.LocalEndpoint).Port);
            receiverClient=listener.AcceptTcpClient();connecting.GetAwaiter().GetResult();
            sender=new Session(senderClient.GetStream(),()=>senderClient.Dispose(),Path.Combine(dataRoot,"file-send-cache"),true);
            receiver=new Session(receiverClient.GetStream(),()=>receiverClient.Dispose(),Path.Combine(dataRoot,"file-receive-cache"),true);
            var outgoing=new DesktopTransfers(_=>"Phone");var incoming=new DesktopTransfers(_=>"Windows");
            outgoing.Attach("phone",sender,"lan");incoming.Attach("windows",receiver,"lan");sender.Start();receiver.Start();
            string source=Path.Combine(dataRoot,"loopback-test.pdf");byte[] content=Enumerable.Range(0,96*1024).Select(i=>(byte)((i*31+7)&255)).ToArray();File.WriteAllBytes(source,content);
            Guid id=outgoing.Send("phone",source);var deadline=DateTime.UtcNow.AddSeconds(8);DesktopTransfer? offered=null;
            while(DateTime.UtcNow<deadline){offered=incoming.Snapshot().FirstOrDefault(r=>r.Phase==TransferPhase.Offered);if(offered!=null)break;System.Threading.Thread.Sleep(10);}
            Check(offered!=null,"LAN file offer reaches receiver without auto-acceptance");incoming.Accept(offered!.Id);
            DesktopTransfer? received=null;DesktopTransfer? sent=null;deadline=DateTime.UtcNow.AddSeconds(8);
            while(DateTime.UtcNow<deadline){received=incoming.Snapshot().FirstOrDefault(r=>r.Name=="loopback-test.pdf");sent=outgoing.Snapshot().FirstOrDefault(r=>r.Id==id);if(received?.Phase==TransferPhase.Completed&&sent?.Phase==TransferPhase.Completed)break;System.Threading.Thread.Sleep(10);}
            Check(received?.Phase==TransferPhase.Completed&&sent?.Phase==TransferPhase.Completed,"LAN file completes on both sides after explicit receiver approval");
            Check(received?.Path!=null&&File.Exists(received.Path)&&SHA256.HashData(File.ReadAllBytes(received.Path)).SequenceEqual(SHA256.HashData(content)),"received file is cached only after checksum verification");
            Check(sent?.SourcePath==source&&sent.CreatedAtUtcTicks>0,"outgoing file row retains source metadata and transfer timestamp");
            string receivedPath=received!.Path!;incoming.ForgetReceived(received.Id);
            Check(!File.Exists(receivedPath),"removing received cache deletes only the app-owned cached copy");
        }
        finally{sender?.Dispose();receiver?.Dispose();senderClient?.Dispose();receiverClient?.Dispose();listener.Stop();}
    }

    static void CheckFileCatalog(string dataRoot)
    {
        string aRoot=Path.Combine(dataRoot,"catalog-a"),bRoot=Path.Combine(dataRoot,"catalog-b"),aCache=Path.Combine(aRoot,"cache"),bCache=Path.Combine(bRoot,"cache");Directory.CreateDirectory(aRoot);Directory.CreateDirectory(bRoot);
        var a=new FileCatalog(aRoot,aCache,_=>true,_=>"手机");var b=new FileCatalog(bRoot,bCache,_=>true,_=>"电脑");
        string source=Path.Combine(aRoot,"会议记录 😀.pdf");byte[] bytes=Enumerable.Range(0,180000).Select(i=>(byte)(i*17)).ToArray();File.WriteAllBytes(source,bytes);a.Store.Add(source);
        using(var legacy=new Session(new MemoryStream(),()=>{},Path.Combine(dataRoot,"legacy-catalog"),true)){bool blocked=false;try{legacy.SendCatalog(System.Text.Encoding.UTF8.GetBytes("{}"));}catch(IOException){blocked=true;}Check(blocked&&!legacy.CatalogEnabled,"FCP3 rejects catalog sends without changing clipboard negotiation");}
        var listener=new TcpListener(IPAddress.Loopback,0);listener.Start();using var client=new TcpClient();var connect=client.ConnectAsync(IPAddress.Loopback,((IPEndPoint)listener.LocalEndpoint).Port);using var server=listener.AcceptTcpClient();connect.GetAwaiter().GetResult();listener.Stop();
        using var send=new Session(client.GetStream(),()=>client.Dispose(),aCache,true,true);using var receive=new Session(server.GetStream(),()=>server.Dispose(),bCache,true,true);
        int clipboardFrames=0;var device=new SyncDevice(Guid.NewGuid(),"手机","android");var entry=new SyncEntry(Guid.NewGuid(),device,1,1,"传文件时继续同步 😀");send.Sync=f=>{if(f.Type==SyncProtocol.Clip&&SyncProtocol.DecodeEntry(f,device).Entry.Text==entry.Text)System.Threading.Interlocked.Increment(ref clipboardFrames);};receive.Sync=_=>{};
        a.Attach("phone",send);b.Attach("windows",receive);send.Start();receive.Start();
        bool Wait(Func<bool> predicate){var until=DateTime.UtcNow.AddSeconds(8);while(DateTime.UtcNow<until){if(predicate())return true;System.Threading.Thread.Sleep(10);}return false;}
        Check(Wait(()=>b.Snapshot().Length==1),"FCP4 publishes only explicitly shared metadata");var row=b.Snapshot().Single();Check(row.Name==Path.GetFileName(source)&&row.Size==bytes.Length&&!b.IsLocal(row)&&!Directory.GetFiles(bCache).Any(),"remote file lists without automatically downloading content");
        var downloading=b.Download(row);receive.SendSync(new(SyncProtocol.Clip,entry.Id,SyncProtocol.EncodeEntry(entry,Guid.NewGuid())));string downloaded=downloading.GetAwaiter().GetResult();Check(File.ReadAllBytes(downloaded).SequenceEqual(bytes),"requested catalog file is accepted by request identity and SHA-256 verified");
        Check(Wait(()=>clipboardFrames>0),"clipboard frames remain available during catalog file transfer");Check(File.ReadAllBytes(source).SequenceEqual(bytes),"download does not modify sender original");
        b.Keep(row,true);b.Clear(new[]{row},true,true);Check(b.Snapshot().Length==1&&File.Exists(downloaded),"protected file survives record and cache clearing");
        var restored=new FileCatalog(bRoot,bCache,_=>true,_=>"电脑");Check(restored.Snapshot().Single().Kept&&restored.IsLocal(restored.Snapshot().Single())&&!restored.Snapshot().Single().Online,"file protection and verified cache persist after restart without pretending source online");
        b.Keep(row,false);b.Clear(new[]{row},false,true);Check(!File.Exists(downloaded)&&b.Snapshot().Length==1,"cache clear retains downloadable metadata");
        string second=b.Download(row).GetAwaiter().GetResult();Check(File.Exists(second),"cleared file can be requested again");
        a.Store.Remove(source);a.Publish();Check(Wait(()=>!b.Snapshot().Single().Online),"withdrawn file is marked unavailable while existing download remains");
        b.Clear(new[]{row},true,true);Check(b.Snapshot().Length==0&&!File.Exists(second),"unprotected record clear removes only app cache");
        a.Store.Add(source);a.Store.Keep(source,true);Check(!a.Store.Remove(source)&&new FileShelfStore(Path.Combine(aRoot,"file-shelf.json")).Load().Single().Kept,"local shared file protection survives reload and prevents bulk removal");
        a.Store.Add(source);Check(a.Store.Load().Single().Kept,"adding the same shared file again preserves its protection");
        string brokenRoot=Path.Combine(dataRoot,"damaged-file-catalog");Directory.CreateDirectory(brokenRoot);string broken=Path.Combine(brokenRoot,"remote-file-catalog.json");File.WriteAllText(broken,"broken catalog fixture");
        var recovered=new FileCatalog(brokenRoot,Path.Combine(brokenRoot,"cache"),_=>true,_=>"fixture");Check(recovered.Snapshot().Length==0&&File.ReadAllText(broken)=="broken catalog fixture","damaged optional file metadata does not prevent clipboard startup or erase its source");
        File.WriteAllText(Path.Combine(brokenRoot,"file-shelf.json"),"broken local shelf");recovered.Publish();Check(recovered.Warning!=null,"optional file shelf damage is reported without interrupting clipboard session setup");
    }

    [STAThread] static int Main(string[] args)
    {
        string dataRoot=Path.Combine(Path.GetTempPath(),"FloatClip-DesktopChecks-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(output);
        var app=new Application{ShutdownMode=ShutdownMode.OnExplicitShutdown};
        DesktopFluent.Initialize(app.Resources);
        Directory.CreateDirectory(output);
        System.Collections.Generic.IEnumerable<string> ResourceKeys(ResourceDictionary dictionary){foreach(object key in dictionary.Keys)yield return key.ToString()??"";foreach(var child in dictionary.MergedDictionaries)foreach(var key in ResourceKeys(child))yield return key;}
        File.WriteAllText(Path.Combine(output,"fluent-control-keys.json"),JsonSerializer.Serialize(ResourceKeys(app.Resources).Where(k=>new[]{"Button","ComboBox","TextBox","MenuItem","ContextMenu","ScrollBar"}.Any(k.Contains)).Distinct().ToArray(),new JsonSerializerOptions{WriteIndented=true}));
        ClipboardHost? host=null;
        try
        {
            var components=app.Resources.MergedDictionaries.Last();
            foreach(object key in components.Keys)Check(components[key] is Style,"shared style resolves before opening UI: "+key);
            // Synthetic pixels include a semitransparent red pixel to exercise PNG alpha.
            byte[] pixels={0,0,128,128,0,255,0,255,255,0,0,255,255,255,255,255};
            var bitmap=BitmapSource.Create(2,2,96,96,PixelFormats.Pbgra32,null,pixels,8);bitmap.Freeze();
            byte[] png=ClipboardImages.Encode(bitmap);var data=new DataObject();data.SetData(ClipboardImages.PngFormat,new MemoryStream(png),false);
            var read=ClipboardImages.Read(data);Check(read?.Width==2&&read?.Height==2,"PNG clipboard format capture");
            var decoded=ClipboardImages.Decode(read!.Value.Png);var bgra=new FormatConvertedBitmap(decoded,PixelFormats.Bgra32,null,0);byte[] result=new byte[16];bgra.CopyPixels(result,8,0);Check(result[3]==128,"PNG transparency preserved");
            var dib=new DataObject();dib.SetImage(bitmap);Check(ClipboardImages.Read(dib)?.Width==2,"DIB bitmap fallback");
            byte[] dibV5=new byte[140];void Put(int offset,int value)=>BinaryPrimitives.WriteInt32LittleEndian(dibV5.AsSpan(offset,4),value);
            Put(0,124);Put(4,2);Put(8,-2);Put(12,1|(32<<16));Put(16,3);Put(40,0xff0000);Put(44,0xff00);Put(48,0xff);Put(52,unchecked((int)0xff000000));pixels.CopyTo(dibV5,124);
            var v5=ClipboardImages.DecodeDib(dibV5);byte[] alpha=new byte[16];v5.CopyPixels(alpha,8,0);Check(alpha[3]==128&&v5.PixelHeight==2,"DIBV5 alpha and top-down rows preserved");
            var v5Data=new DataObject();v5Data.SetData(DataFormats.GetDataFormat(17).Name,new MemoryStream(dibV5),false);Check(ClipboardImages.Read(v5Data)?.Width==2,"native DIBV5 clipboard format captured");
            bool truncated=false;try{ClipboardImages.DecodeDib(dibV5[..130]);}catch(IOException){truncated=true;}Check(truncated,"truncated DIB refused before pixel allocation");
            using var updates=new DesktopUpdates(Path.Combine(dataRoot,"Updates"),_=>Task.FromResult(new FloatClip.Share.UpdateRelease(ShareUpdates.Build+1,"0.8.0","synthetic update","https://github.com/SteveBrilien/FloatClip/releases/download/test/test.zip")));
            host=new ClipboardHost(dataRoot,false,updates);host.SetPinned(true);
            var vault=host.Vault;var first=vault.AddText("alpha 中文 😀 👍🏽 👨‍👩‍👧‍👦 🇨🇳 1️⃣");
            var row=first["entries"]![0]!.AsObject();row["pinned"]=true;row["category"]="工作";string textId=row["id"]!.GetValue<string>();vault.Commit(first["revision"]!.GetValue<string>(),first["entries"]!.AsArray());
            var second=vault.AddText("beta protected");var beta=second["entries"]!.AsArray().OfType<JsonObject>().Single(c=>c["text"]!.GetValue<string>()=="beta protected");beta["locked"]=true;vault.Commit(second["revision"]!.GetValue<string>(),second["entries"]!.AsArray());
            vault.AddText("gamma ordinary");var image=vault.AddImage(png,2,2)["entries"]!.AsArray().OfType<JsonObject>().Single(c=>ClipboardVault.Kind(c)=="image");
            host.ShowPopup();Pump();var window=app.Windows.OfType<LocalClipboardWindow>().Single();window.Title="FloatClip · 隔离界面验收";
            string shelfSample=Path.Combine(dataRoot,"shelf-sample.pdf");byte[] shelfBytes=System.Text.Encoding.UTF8.GetBytes("metadata-only test fixture");File.WriteAllBytes(shelfSample,shelfBytes);
            var shelfStore=new FileShelfStore(Path.Combine(dataRoot,"file-shelf.json"));string shelfHash=Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(shelfSample)));shelfStore.Add(shelfSample);
            Check(Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(shelfSample)))==shelfHash,"file shelf persists metadata without changing file content");
            Check(shelfStore.Load().Single().Name=="shelf-sample.pdf"&&shelfStore.Load().Single().Size==shelfBytes.Length,"file shelf restores filename and exact byte size");
            double clipboardWidthBeforeFiles=window.Width;Call(window,"Navigate",typeof(FilesPage));Pump();Check(window.Width==clipboardWidthBeforeFiles,"opening files preserves the existing window width");
            var fileView=Field<FileShelfView>(window,"filesView");var fileRows=Field<System.Collections.ObjectModel.ObservableCollection<FileShelfRow>>(fileView,"localRows");
            Check(Descendants(fileView).OfType<TextBlock>().Any(t=>t.Text=="共享文件"),"native file page uses the approved shared-files heading");
            Check(fileRows.Count==1&&fileRows[0].HasLocalFile&&fileRows[0].Name=="shelf-sample.pdf","native file page renders persistent local shelf row");
            Check(System.Windows.Automation.AutomationProperties.GetAutomationId(Field<ListView>(fileView,"localList"))=="FileShelfList"&&System.Windows.Automation.AutomationProperties.GetAutomationId(Field<ListView>(fileView,"transferList"))=="TransferActivityList","file and transfer lists expose accessible automation identifiers");
            Check(Descendants(fileView).OfType<ToolBar>().Count()==1,"file commands use one standard overflow toolbar");
            Check(Field<Border>(fileView,"activity").Height==126&&!Descendants(fileView).OfType<Expander>().Any(),"transfer activity uses a fixed dock without animated expansion");
            Check(Field<Border>(fileView,"activity").Visibility==Visibility.Collapsed,"empty activity does not leave a blank annotated panel");
            var commands=new[]{"downloadCommand","previewCommand","saveCommand","forwardCommand","propertiesCommand"}.Select(name=>Field<Button>(fileView,name)).ToArray();Check(commands.Length==5&&Descendants(fileView).OfType<Separator>().Any(),"approved file toolbar has bounded command group and distinct actions");
            Check(((Wpf.Ui.Controls.Button)commands[0]).Appearance==Wpf.Ui.Controls.ControlAppearance.Primary,"download remains the primary command");
            // Presentation fixtures cover solid/local and dashed/remote type icons.
            foreach(var sample in new[]{("会议纪要与下一步安排.docx","DOCX","Word",true,"本机文件"),("项目进度与预算.xlsx","XLSX","Excel",true,"已下载"),("界面参考.png","PNG","图片",false,"未下载"),("结构草图.dwg","DWG","文件",false,"来源离线"),("UI 资源.zip","ZIP","压缩包",true,"本机文件")})
                fileRows.Add(new FileShelfRow{Name=sample.Item1,Extension=sample.Item2,Type=sample.Item3,HasLocalFile=sample.Item4,Status=sample.Item5,Modified="2026-10-22 16:28",Source=sample.Item4?"本机":"我的手机",SizeLabel="62 KB"});
            window.GetType().GetField("mode",BindingFlags.Instance|BindingFlags.NonPublic)!.SetValue(window,"tokyo-night");Call(window,"ApplyPalette");Call(window,"BuildHeader");Call(window,"Refresh",true);Pump(320);Snapshot(window,"files-page-tokyo-night"); File.WriteAllText(Path.Combine(output,"combo-style.txt"), string.Join("\n", new[]{typeof(ComboBox),typeof(CheckBox),typeof(Button)}.Select(t=>{var s=app.TryFindResource(t) as Style;return t+" style: "+(s?.TargetType.ToString()??"none")+" base "+s?.BasedOn?.TargetType+" setters: "+string.Join(", ",s?.Setters.OfType<Setter>().Select(v=>v.Property.Name+"="+v.Value)??Array.Empty<string>());})));window.GetType().GetField("mode",BindingFlags.Instance|BindingFlags.NonPublic)!.SetValue(window,"system");Call(window,"ApplyPalette");Call(window,"BuildHeader");Call(window,"Refresh",true);Pump();
            File.Move(shelfSample,shelfSample+".offline");Check(shelfStore.Load().Single().IsAvailable==false,"missing source remains listed as unavailable instead of silently disappearing");File.Move(shelfSample+".offline",shelfSample);
            Check(shelfStore.Load().Single().IsAvailable,"restored source returns to available state");
            var nativeFileList=Field<ListView>(fileView,"localList");Check(((GridView)nativeFileList.View).Columns.All(c=>c.Width>0),"opening the file page exposes every Explorer metadata column");
            nativeFileList.SelectedIndex=0;Pump();Check(!Field<TextBlock>(fileView,"selectedInfo").Text.Contains('·')&&!Field<TextBlock>(fileView,"selectedInfo").Text.Contains("KiB"),"file footer shows counts with a separator, without repeated size and status annotations");
            window.Width=760;Pump();Check(((GridView)nativeFileList.View).Columns.Sum(c=>c.Width)<=nativeFileList.ActualWidth,"normal desktop width fits all file metadata columns without forcing window resize");Snapshot(window,"files-page-approved-light");
            Check(!FilePreviewWindow.CanOpenDocument("example.exe")&&!FilePreviewWindow.CanOpenDocument("example.bat")&&FilePreviewWindow.CanOpenDocument("example.pdf"),"preview cannot launch executable files");
            double fileWidth=window.Width;Call(window,"Navigate",typeof(ClipboardPage));Pump();Check(window.Width==fileWidth,"page navigation preserves user window width");
            updates.CheckAsync().GetAwaiter().GetResult();Pump();Check(Field<Border>(window,"updateBanner").Visibility==Visibility.Visible,"startup new-version banner visible without modal window");
            updates.Postpone();Pump();Check(Field<Border>(window,"updateBanner").Visibility==Visibility.Collapsed,"postpone keeps clipboard usable and closes startup banner");
            Check(!File.Exists(Path.Combine(dataRoot,"clipboard-sync.v1.bin")),"isolated desktop does not create sync state");
            host.ShowLibrary();Check(app.Windows.OfType<LocalClipboardWindow>().Count()==1,"legacy library reuses one clipboard window");
            var search=Field<TextBox>(window,"search");var list=Field<StackPanel>(window,"list");
            var dragRegion=Descendants(window).OfType<DockPanel>().Single(p=>System.Windows.Automation.AutomationProperties.GetAutomationId(p)=="WindowDragRegion");Check(dragRegion.Background!=null&&dragRegion.ActualWidth>=window.ActualWidth-12,"entire blank title region is hit-testable for dragging");
            var pinButton=Descendants(dragRegion).OfType<Button>().Single(b=>b.Content is Wpf.Ui.Controls.SymbolIcon);Check(((Wpf.Ui.Controls.SymbolIcon)pinButton.Content).Filled,"pinned window uses a filled pin");host.SetPinned(false);Call(window,"BuildShellTitle");Check(!((Wpf.Ui.Controls.SymbolIcon)pinButton.Content).Filled,"unpinned window uses an outlined pin");host.SetPinned(true);Call(window,"BuildShellTitle");
            Check(search.Template.FindName("PART_ContentHost",search) is ScrollViewer&&!Descendants(search).OfType<Border>().Any(),"search has one outer focus boundary and no nested partial underline");
            void Query(string value,int count){search.Text=value;Pump(180);Check(list.Children.Count==count,"search TextChanged matches "+value);}
            Query("alpha",1);Query("工作",1);Query("图片",1);Query("👍🏽",1);Query("",4);
            window.OpenSettings();window.OpenSettings();Pump();var settings=Field<System.Collections.Generic.Dictionary<Type,FrameworkElement>>(window,"pages")[typeof(SettingsPage)];
            Check(Descendants(window).OfType<SettingsPage>().Count()==1&&window.OwnedWindows.Count==0,"settings use one cached page in the main window");Check(window.IsEnabled,"settings navigation keeps the main window enabled");
            Check(Descendants(settings).OfType<Wpf.Ui.Controls.ToggleSwitch>().Count()>=5,"settings use native Fluent toggle controls");
            Pump(320);Snapshot(window,"settings-native");
            var fontSlider=Descendants(settings).OfType<Slider>().Single(s=>System.Windows.Automation.AutomationProperties.GetAutomationId(s)=="ClipFontSize");fontSlider.Value=13;
            var spacingSlider=Descendants(settings).OfType<Slider>().Single(s=>System.Windows.Automation.AutomationProperties.GetAutomationId(s)=="CardSpacing");spacingSlider.Value=6;
            var savedUi=JsonNode.Parse(File.ReadAllText(Path.Combine(dataRoot,"clipboard-ui.json")))!;Check(savedUi["clipFontSize"]!.GetValue<double>()==13&&savedUi["cardSpacing"]!.GetValue<double>()==6,"real Fluent sliders persist text size and card spacing");
            window.OpenDevices();Pump();Check(Descendants(window).OfType<DevicesPage>().Count()==1&&window.OwnedWindows.Count==0,"device navigation stays in the same window without connecting isolated peers");
            using(var deviceSync=new NativeClipboardSync(vault,(_,_)=>{},Path.Combine(dataRoot,"device-ui"))){
                byte[] deviceToken=Enumerable.Range(1,32).Select(i=>(byte)i).ToArray();deviceSync.Pairing.Put(new string('a',64),deviceToken);string peerKey=Convert.ToHexString(SHA256.HashData(deviceToken)).ToLowerInvariant();deviceSync.Pairing.RememberDevice(peerKey,new SyncDevice(Guid.NewGuid(),"我的手机","android"));
                using var deviceView=new ClipboardSharingView(deviceSync);var deviceWindow=new Window{Content=deviceView,Width=740,Height=700,ShowInTaskbar=false,Background=window.Background,Foreground=window.Foreground};deviceWindow.Show();Pump(320);Snapshot(deviceWindow,"devices-native");
                Check(Descendants(deviceView).OfType<Button>().All(b=>b.HorizontalAlignment!=HorizontalAlignment.Stretch),"device actions keep standard button widths");
                Check(!Descendants(deviceView).OfType<Expander>().Any()&&Descendants(deviceView).OfType<ComboBox>().Single(c=>System.Windows.Automation.AutomationProperties.GetAutomationId(c)=="ConnectionMethod").SelectedIndex==0,"device setup uses a single method picker without nested expanders");deviceWindow.Close();
            }
            Call(window,"ShowUpdates");Pump();Check(Descendants(window).OfType<UpdatesPage>().Count()==1&&window.OwnedWindows.Count==0,"update navigation stays in the same window");
            Pump(320);Snapshot(window,"updates-native");
            window.Activate();window.FocusSearch();Pump();Check(search.IsKeyboardFocusWithin&&Field<Type>(window,"currentPage")==typeof(ClipboardPage),"returning to clipboard restores searchable content");
            Check(search.MinHeight<=34&&Descendants(window).OfType<Button>().Where(b=>b.Tag as string=="icon").All(b=>b.Width<=32),"search and title controls use compact desktop sizes");
            Call(window,"FullText",textId,row["text"]!.GetValue<string>());Pump();var full=window.OwnedWindows.Cast<Window>().Single(w=>w.Title=="剪贴板全文");
            var rich=(Emoji.Wpf.RichTextBox)full.Content;rich.SelectAll();string fullValue=rich.Selection.Text;
            Check(fullValue==row["text"]!.GetValue<string>(),"emoji copy selection preserves exact original Unicode");
            // A native TextRange serializes EmojiInline as an object placeholder; the
            // production Copy command explicitly uses Emoji.Wpf.TextSelection.Text.
            Check(rich.EmojiInlines.Count()>=4&&fullValue.Contains("👍🏽 👨‍👩‍👧‍👦 🇨🇳",StringComparison.Ordinal),"colored emoji selection restores skin tone, ZWJ and flag sequences");
            Check(rich.EmojiInlines.Any(e=>e.Text=="1️⃣"),"keycap emoji retains its complete color glyph sequence");
            var familyInline=rich.EmojiInlines.Single(e=>e.Text=="👨‍👩‍👧‍👦");((RichTextBox)rich).Selection.Select(familyInline.ContentStart,familyInline.ContentEnd);
            Check(rich.Selection.Text=="👨‍👩‍👧‍👦","partial selection copies a family ZWJ sequence without object placeholders");rich.SelectAll();
            const string multiline="\n第一行 👍🏽\n\nsecond 👨‍👩‍👧‍👦 🇨🇳\r\n末尾\n";
            Call(window,"FullText","multiline-copy",multiline);Pump();var multilineWindow=window.OwnedWindows.Cast<Window>().Single(w=>w.Title=="剪贴板全文"&&!ReferenceEquals(w,full));
            var multilineBox=(Emoji.Wpf.RichTextBox)multilineWindow.Content;multilineBox.SelectAll();
            Check(multilineBox.Selection.Text==multiline,"emoji copy preserves blank lines, CRLF and trailing newline");multilineWindow.Close();
            var emojiSample=new Emoji.Wpf.TextBlock{FontSize=64,Text="😀 👍🏽 👨‍👩‍👧‍👦 🇨🇳",Margin=new(16),TextWrapping=TextWrapping.Wrap};
            var emojiWindow=new Window{Owner=window,ShowInTaskbar=false,Width=800,Height=220,Background=Brushes.White,Foreground=Brushes.Black,Content=emojiSample};emojiWindow.Show();Pump();Snapshot(emojiWindow,"emoji-64dip");
            File.WriteAllText(Path.Combine(output,"emoji-sequences.json"),JsonSerializer.Serialize(emojiSample.EmojiInlines.Select(e=>e.Text).ToArray()));
            var nativeEmoji=new DrawingVisual();using(var drawing=nativeEmoji.RenderOpen())drawing.DrawText(new FormattedText("👨‍👩‍👧‍👦",System.Globalization.CultureInfo.InvariantCulture,FlowDirection.LeftToRight,new Typeface("Segoe UI Emoji"),64,Brushes.Black,1),new Point());
            System.Collections.Generic.IEnumerable<ushort> NativeGlyphs(Drawing drawing)=>drawing is GlyphRunDrawing glyph?glyph.GlyphRun.GlyphIndices:drawing is DrawingGroup group?group.Children.SelectMany(NativeGlyphs):Array.Empty<ushort>();
            File.WriteAllText(Path.Combine(output,"native-family-glyphs.json"),JsonSerializer.Serialize(new{native=NativeGlyphs(nativeEmoji.Drawing).ToArray(),emoji=Emoji.Wpf.EmojiData.Typeface.MakeGlyphIndexList("👨‍👩‍👧‍👦").ToArray(),zwj=Emoji.Wpf.EmojiData.Typeface.ZwjGlyph}));
            Check(emojiSample.EmojiInlines.Count()==4,"emoji preview keeps each skin tone, family ZWJ and flag sequence in one inline");emojiWindow.Close();
            Call(window,"PreviewImage",image);Call(window,"PreviewImage",image);Pump();Check(window.OwnedWindows.Cast<Window>().Count(w=>w.Title=="图片预览")==1,"image preview reused");
            void Theme(string value){window.GetType().GetField("mode",BindingFlags.Instance|BindingFlags.NonPublic)!.SetValue(window,value);Call(window,"ApplyPalette");Call(window,"BuildHeader");Call(window,"Refresh",true);Pump();}
            Theme("light");Snapshot(window,"clipboard-light");Theme("dark");Snapshot(window,"clipboard-dark");Snapshot(full,"fulltext-dark");
            foreach(var child in window.OwnedWindows.Cast<Window>())Check(((SolidColorBrush)child.Background).Color==((SolidColorBrush)window.Background).Color,"open child follows theme: "+child.Title);
            var themePicker=Descendants(settings).OfType<ComboBox>().Single(c=>System.Windows.Automation.AutomationProperties.GetAutomationId(c)=="ThemeMode");
            themePicker.SelectedIndex=2;Pump();
            Check(File.ReadAllText(Path.Combine(dataRoot,"theme.txt"))=="tokyo-night"&&Field<bool>(window,"dark"),"Tokyo Night chosen through settings persists and uses dark window chrome");
            Check(((SolidColorBrush)window.Background).Color==Color.FromRgb(26,27,38)&&((SolidColorBrush)app.Resources["FloatClip.Surface"]).Color==Color.FromRgb(36,40,59),"Tokyo Night matches phone background and card palette");
            foreach(var child in window.OwnedWindows.Cast<Window>())Check(((SolidColorBrush)child.Background).Color==((SolidColorBrush)window.Background).Color,"open child follows Tokyo Night: "+child.Title);
            Pump(280);Snapshot(window,"clipboard-tokyo-night");window.OpenSettings();Pump(280);Snapshot(window,"settings-tokyo-night");window.FocusSearch();Pump(280);
            using(var reloadedHost=new ClipboardHost(dataRoot,false)){
                var reloaded=new LocalClipboardWindow(reloadedHost,true);
                Check(Field<string>(reloaded,"mode")=="tokyo-night"&&((SolidColorBrush)reloaded.Background).Color==((SolidColorBrush)window.Background).Color,"Tokyo Night restored from disk when a new window opens");
                reloadedHost.Dispose();reloaded.Close();
            }
            Call(window,"ClearUnprotectedHistory");Check(vault.Load()["entries"]!.AsArray().Count==2,"clear keeps pinned and locked only");
            var undo=Field<Border>(window,"undo");Check(undo.Visibility==Visibility.Collapsed&&Field<System.Collections.Generic.List<JsonObject>?>(window,"deleted")==null,"bulk clear has no undo action or retained deleted snapshot");
            Call(window,"ClearUnprotectedHistory");Check(undo.Visibility==Visibility.Collapsed&&vault.Load()["entries"]!.AsArray().Count==2,"repeated empty clear keeps protected records without stale undo");vault.AddImage(png,2,2);Call(window,"Refresh",true);
            Theme("light");Snapshot(window,"clipboard-restored");
            var demonstration=vault.Load()["entries"]!.DeepClone();var capacity=vault.Load();var capacityRows=new JsonArray();
            for(int index=0;index<500;index++){var clip=(JsonObject)row.DeepClone();clip["id"]=Guid.NewGuid().ToString();clip["text"]="capacity "+index;clip["pinned"]=index<499;clip["locked"]=false;capacityRows.Add(clip);}
            vault.Commit(capacity["revision"]!.GetValue<string>(),capacityRows);
            var pending=new System.Collections.Generic.List<JsonObject>();
            for(int index=0;index<3;index++){var clip=(JsonObject)row.DeepClone();clip["id"]=Guid.NewGuid().ToString();clip["text"]="restore old "+index;clip["pinned"]=false;clip["locked"]=false;clip["updatedAt"]=(long)index;pending.Add(clip);}
            window.GetType().GetField("deleted",BindingFlags.Instance|BindingFlags.NonPublic)!.SetValue(window,pending);Call(window,"UndoBar");
            ((DockPanel)undo.Child).Children.OfType<Button>().Single().RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            var capacityAfter=vault.Load()["entries"]!.AsArray().OfType<JsonObject>().ToList();var requestedIds=pending.Select(c=>c["id"]!.GetValue<string>()).ToHashSet();
            Check(capacityAfter.Count(ClipboardVault.IsPinned)==499,"capacity-limited undo preserves every protected record");
            Check(capacityAfter.Count(c=>requestedIds.Contains(c["id"]!.GetValue<string>()))==1&&Field<System.Collections.Generic.List<JsonObject>>(window,"deleted").Count==2,"capacity-limited undo does not evict restored clips or report false restores");
            string installerRoot=Path.Combine(dataRoot,"InstallerTest");Directory.CreateDirectory(installerRoot);string target=Path.Combine(installerRoot,"FloatClip.exe"),staged=Path.Combine(installerRoot,"FloatClip.new.exe"),planPath=Path.Combine(installerRoot,"plan.json"),resultPath=Path.Combine(installerRoot,"last-install.json");
            File.WriteAllText(target,"synthetic old executable");File.WriteAllText(staged,"synthetic new executable");
            string stagedHash=Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(staged)));
            File.WriteAllText(planPath,JsonSerializer.Serialize(new{ProcessId=int.MaxValue,Target=target,Staged=staged,Sha256=stagedHash,ResultFile=resultPath}));
            Check(DesktopUpdateInstaller.ApplyInRoot(planPath,installerRoot,false)==0&&File.ReadAllText(target)=="synthetic new executable"&&File.ReadAllText(target+".previous")=="synthetic old executable","updater atomically replaces isolated executable and preserves rollback copy");
            File.WriteAllText(staged,"corrupt after staging");Check(DesktopUpdateInstaller.ApplyInRoot(planPath,installerRoot,false)==1&&File.ReadAllText(target)=="synthetic new executable","installer refuses tampered staged executable without replacing app");
            Check(DesktopUpdateInstaller.ApplyInRoot(planPath,Path.Combine(installerRoot,"other"),false)==1,"installer rejects a plan outside its update directory");
            vault.Commit(vault.Load()["revision"]!.GetValue<string>(),demonstration.AsArray());window.GetType().GetField("deleted",BindingFlags.Instance|BindingFlags.NonPublic)!.SetValue(window,null);Call(window,"Refresh",true);
            CheckFileTransfer(dataRoot);
            CheckFileCatalog(dataRoot);
            NativeSyncChecks.Run(dataRoot,Check);
            DesignChecks.Run(app,window,host,output,Check,Pump);
            ProductFixChecks.Run(dataRoot,Check,Pump,Snapshot);
            Check(host.UiFailureCount==0,"no guarded UI exceptions were hidden during acceptance");
            File.WriteAllText(Path.Combine(output,"report.json"),JsonSerializer.Serialize(new{result="PASS",checks,isolated=true,userClipboardUntouched=true,uiFailures=host.UiFailureCount,utc=DateTime.UtcNow},new JsonSerializerOptions{WriteIndented=true}));
            if(args.Contains("--interactive"))
            {
                foreach(var child in window.OwnedWindows.Cast<Window>().ToArray())child.Close();
                vault.AddImage(png,2,2);Call(window,"Refresh",true);
                window.ShowInTaskbar=true;window.Hide();window.Show();window.Activate();app.Run();
            }
            return 0;
        }
        catch(Exception error){File.WriteAllText(Path.Combine(output,"report.json"),JsonSerializer.Serialize(new{result="FAIL",checks,error=error.ToString()},new JsonSerializerOptions{WriteIndented=true}));return 1;}
        finally{host?.Dispose();app.Shutdown();try{Directory.Delete(dataRoot,true);}catch{}}
    }
}
