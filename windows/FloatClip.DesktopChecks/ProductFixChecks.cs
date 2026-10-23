using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using FloatClip.Desktop;
using FloatClip.Desktop.Design;
using FloatClip.Desktop.Services;
using FloatClip.Desktop.ViewModels;
using FloatClip.Share;

static class ProductFixChecks
{
    sealed class Devices:IClipboardDeviceService
    {
        public event Action? Changed;
        public DevicePanelState State=new(true,true,true,true,true,"等待已授权设备自动连接",false,new[]{new DevicePeerState("fixture","我的手机 😀",true,true,"已授权，等待自动连接")});
        public int Writes;
        public DevicePanelState Read()=>State;
        public void Configure(bool enabled,bool send,bool receive,bool apply,bool automatic){Writes++;State=State with{Enabled=enabled,Send=send,Receive=receive,Apply=apply,Automatic=automatic};Changed?.Invoke();}
        public void SetPeerEnabled(string key,bool enabled){Writes++;State=State with{Peers=State.Peers.Select(p=>p with{Enabled=enabled}).ToArray()};Changed?.Invoke();}
        public void Forget(string key){}
        public void ResetPairing(){}
        public void Reconnect(){}
        public IReadOnlyList<LanNetwork> Networks()=>Array.Empty<LanNetwork>();
        public Task PairBluetooth(Func<string,bool> verify,CancellationToken token)=>Task.CompletedTask;
        public Task<IReadOnlyList<NearbyDevice>> Discover(LanNetwork network,CancellationToken token)=>Task.FromResult<IReadOnlyList<NearbyDevice>>(Array.Empty<NearbyDevice>());
        public Task PairLan(NearbyDevice device,Func<string,bool> verify,CancellationToken token)=>Task.CompletedTask;
        public void Dispose(){}
    }
    static IEnumerable<DependencyObject> Tree(DependencyObject root){yield return root;for(int i=0;i<VisualTreeHelper.GetChildrenCount(root);i++)foreach(var child in Tree(VisualTreeHelper.GetChild(root,i)))yield return child;}
    public static void Run(string root,Action<bool,string> check,Action<int> pump,Action<Window,string> snapshot)
    {
        check(FileSizeFormatter.Format(0)=="0 KB"&&FileSizeFormatter.Format(100)=="< 1 KB","zero and tiny files use familiar KB labels");
        check(FileSizeFormatter.Format(1024)=="1 KB"&&FileSizeFormatter.Format(1024*1024)=="1 MB"&&FileSizeFormatter.Format(1024L*1024*1024)=="1 GB","size thresholds include KB MB and GB");
        check(ShellFileIcons.For("fixture.zip") is BitmapSource icon&&icon.PixelWidth>=48,"Explorer association icon keeps its native high-resolution frame");
        string zipPath=Path.Combine(root,"实际压缩包.zip");
        using(var zip=ZipFile.Open(zipPath,ZipArchiveMode.Create)){using(var text=new StreamWriter(zip.CreateEntry("资料/报告 😀.txt").Open()))text.Write("archive contents");using(var text=new StreamWriter(zip.CreateEntry("预算.csv").Open()))text.Write("a,b\n1,2");}
        var archive=DocumentPreviewService.Read(zipPath,CancellationToken.None);
        check(archive.Entries?.Count==2&&archive.Entries[0].Name=="资料/报告 😀.txt","ZIP preview lists actual filenames instead of an enlarged icon");
        check(!Directory.Exists(Path.Combine(root,"资料")),"archive preview does not extract or execute files");
        string docx=Path.Combine(root,"fixture.docx");using(var zip=ZipFile.Open(docx,ZipArchiveMode.Create)){using var writer=new StreamWriter(zip.CreateEntry("word/document.xml").Open());writer.Write("<w:document xmlns:w='word'><w:body><w:p><w:r><w:t>真实文字 😀</w:t></w:r></w:p></w:body></w:document>");}
        check(DocumentPreviewService.Read(docx,CancellationToken.None).Text.Contains("真实文字 😀"),"Word preview reads actual document paragraphs");
        string xlsx=Path.Combine(root,"fixture.xlsx");using(var zip=ZipFile.Open(xlsx,ZipArchiveMode.Create)){
            void Part(string name,string text){using var writer=new StreamWriter(zip.CreateEntry(name).Open());writer.Write(text);}
            Part("xl/workbook.xml","<workbook xmlns:r='rel'><sheets><sheet name='预算' r:id='r1'/></sheets></workbook>");Part("xl/_rels/workbook.xml.rels","<Relationships><Relationship Id='r1' Target='worksheets/sheet1.xml'/></Relationships>");Part("xl/sharedStrings.xml","<sst><si><t>金额</t></si></sst>");Part("xl/worksheets/sheet1.xml","<worksheet><sheetData><row><c r='A1' t='s'><v>0</v></c><c r='B1'><v>123</v></c></row></sheetData></worksheet>");
        }
        string cells=DocumentPreviewService.Read(xlsx,CancellationToken.None).Text;check(cells.Contains("预算")&&cells.Contains("A1: 金额")&&cells.Contains("B1: 123"),"Excel preview reads sheet names, shared strings and cell values");
        check(!FilePreviewWindow.CanPreview("fixture.exe")&&!FilePreviewWindow.CanPreview("fixture.7z")&&FilePreviewWindow.CanPreview(zipPath),"unsupported previews are explicit instead of fake icon previews");
        var preview=new FilePreviewWindow(zipPath){ShowInTaskbar=false};preview.Show();
        for(int i=0;i<40&&!Tree(preview).OfType<ListView>().Any();i++)pump(25);
        check(Tree(preview).OfType<ListView>().Single().Items.Count==2,"native ZIP preview window displays actual archive entries");snapshot(preview,"zip-content-preview");preview.Close();
        var service=new Devices();using var view=new ClipboardSharingView(service);var window=new Window{Title="FloatClip · 隔离设备页",Content=view,Width=740,Height=420,ShowInTaskbar=false};window.SetResourceReference(Window.BackgroundProperty,"FloatClip.Background");window.SetResourceReference(Window.ForegroundProperty,"FloatClip.TextPrimary");window.Show();pump(0);
        var vm=(DevicePanelViewModel)view.DataContext;var peers=(ItemsControl)view.FindName("PeerList");var container=peers.ItemContainerGenerator.ContainerFromIndex(0);var model=vm.Peers.Single();var scroll=(ScrollViewer)view.FindName("PageScroll");
        var optionRows=Tree(view).OfType<Border>().Where(b=>b.Style==Application.Current.FindResource("FloatClip.Setting.OptionRow")).ToArray();
        check(optionRows.Length==5&&optionRows.All(row=>Tree(row).OfType<Wpf.Ui.Controls.ToggleSwitch>().All(toggle=>toggle.ActualHeight<=row.ActualHeight-row.Padding.Top-row.Padding.Bottom-row.BorderThickness.Top-row.BorderThickness.Bottom)),"compact option rows contain the full switch hit area without clipping");
        scroll.ScrollToVerticalOffset(100);pump(0);double offset=scroll.VerticalOffset,height=scroll.ExtentHeight;
        vm.Send=false;vm.Receive=false;vm.Apply=false;vm.Automatic=false;pump(0);
        check(service.Writes==4&&!service.State.Send&&!service.State.Receive&&!service.State.Apply&&!service.State.Automatic,"four toggle bindings persist exact options without duplicate writes");
        check(ReferenceEquals(container,peers.ItemContainerGenerator.ContainerFromIndex(0))&&ReferenceEquals(model,vm.Peers.Single()),"sync changes preserve device row and view-model instances");
        check(Math.Abs(scroll.VerticalOffset-offset)<1&&Math.Abs(scroll.ExtentHeight-height)<1,"sync option changes preserve scroll position and layout height");
        vm.Peers[0].Enabled=false;pump(0);check(!service.State.Peers[0].Enabled&&ReferenceEquals(container,peers.ItemContainerGenerator.ContainerFromIndex(0)),"per-device toggle keeps its existing row");
        vm.ConnectionMode=1;pump(0);check(vm.IsBluetooth&&!vm.IsLan&&!Tree(view).OfType<Expander>().Any(),"Bluetooth and LAN are sibling choices without nested expanders");
        vm.ConnectionMode=2;pump(0);check(vm.IsLan&&!vm.IsBluetooth,"method picker shows only the chosen connection details");
        vm.ConnectionMode=0;window.Height=700;scroll.ScrollToTop();DesignTheme.Apply("light");pump(0);snapshot(window,"devices-refined-light");DesignTheme.Apply("dark");pump(0);snapshot(window,"devices-refined-dark");
        var options=Tree(view).OfType<Button>().Single(b=>System.Windows.Automation.AutomationProperties.GetName(b)=="设备选项");options.ContextMenu.PlacementTarget=options;options.ContextMenu.IsOpen=true;pump(0);check(options.ContextMenu.Items.OfType<MenuItem>().Single().Command==model.ForgetCommand,"device menu resolves its own peer command and uses the shared popup");options.ContextMenu.IsOpen=false;window.Close();
        var tip=new ToolTip{Content="清晰的提示文字",IsOpen=true};pump(0);check(tip.Effect==null&&!tip.HasDropShadow&&TextOptions.GetTextFormattingMode(tip)==TextFormattingMode.Display,"tooltip text has no rasterizing effect and uses display text formatting");tip.IsOpen=false;
    }
}
