using System;
using System.Collections.Generic;
using System.IO;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Markup;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Threading;
using System.Linq;
using System.Diagnostics;
using Microsoft.Win32;
using FloatClip.Share;

namespace FloatClip.Desktop;

public static class Program
{
    [STAThread] public static void Main(string[] args)
    {
        try {
            if(args.Length==4 && args[0]=="--qa-nearby") { Acceptance.RunNearby(args[1],args[2],args[3]); return; }
            if(args.Length==3 && args[0]=="--qa") { Acceptance.Run(args[1],args[2]); return; }
            var app=new Application();
            if(args.Length==0) { app.Run(new UnifiedWindow()); return; }
            if(args.Length==1 && args[0]=="--clipboard") { app.Run(new LocalClipboardWindow(new ClipboardVault())); return; }
            var window=new MainWindow();
            if(args.Length==3 && args[0]=="--capture") {
                window.SetTheme(args[1],false);
                window.ContentRendered+=(_,_)=>window.Dispatcher.BeginInvoke(new Action(()=>{
                    var surface=(FrameworkElement)window.Content;
                    var bitmap=new RenderTargetBitmap((int)surface.ActualWidth,(int)surface.ActualHeight,96,96,PixelFormats.Pbgra32);
                    bitmap.Render(surface); var png=new PngBitmapEncoder(); png.Frames.Add(BitmapFrame.Create(bitmap));
                    using(var file=System.IO.File.Create(args[2])) png.Save(file); window.Close();
                }),System.Windows.Threading.DispatcherPriority.ContextIdle);
            }
            app.Run(window);
        } catch(Exception e) {
            Environment.ExitCode=1;
            if(args.Length>0 && args[0].StartsWith("--qa")) { if(args.Length>2 && !System.IO.File.Exists(args[2])) try { System.IO.File.WriteAllText(args[2],System.Text.Json.JsonSerializer.Serialize(new {result="FAIL",error=e.Message})); } catch { } return; }
            try { string logs=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"FloatClip","Share"); Directory.CreateDirectory(logs); System.IO.File.WriteAllText(Path.Combine(logs,"startup-error.log"),e.ToString()); } catch { }
            MessageBox.Show("FloatClip 无法启动："+e.GetType().Name,"FloatClip",MessageBoxButton.OK,MessageBoxImage.Error);
        }
    }
}

public sealed class MainWindow : Window
{
    readonly string root=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"FloatClip","Share");
    readonly List<(Border Card,string? Path)> items=new();
    Session? session; Offer? offer; bool closing,connecting; int generation;
    string theme="system";
    readonly PairStore pairStore;
    CancellationTokenSource? discoveryCancel,pairCancel;
    UpdateRelease? updateRelease;
    bool checkingUpdate;
    bool discoveredOnce;
    readonly ComboBox networks;
    readonly StackPanel nearbyList;
    LanNetwork? Network=>networks.SelectedItem as LanNetwork;
    readonly Grid layout; readonly TextBox draft,invite;
    readonly StackPanel inbox; readonly TextBlock status,deviceStatus,notice,progress,offerName;
    readonly ProgressBar progressBar; readonly Border offerCard;
    readonly FrameworkElement[] pages; readonly Button[] navigation;
    T Part<T>(string name) where T:class => (T)layout.FindName(name);

    public MainWindow()
    {
        Title="FloatClip"; Width=1000; Height=780; MinWidth=820; MinHeight=590;
        WindowStartupLocation=WindowStartupLocation.CenterScreen; FontFamily=new FontFamily("Microsoft YaHei UI, Segoe UI");
        Directory.CreateDirectory(root); Directory.CreateDirectory(Path.Combine(root,"Cache"));
        foreach(string path in Directory.GetFiles(Path.Combine(root,"Cache"))) {
            if(path.EndsWith(".part") || System.IO.File.GetLastWriteTimeUtc(path)<DateTime.UtcNow.AddDays(-1)) try { System.IO.File.Delete(path); } catch { }
        }
        pairStore=new PairStore(root);
        Resources=(ResourceDictionary)XamlReader.Parse(ResourcesXaml);
        SetResourceReference(BackgroundProperty,"Bg");
        layout=(Grid)XamlReader.Parse(LayoutXaml); Content=layout;
        Part<TextBlock>("AppVersion").Text=ShareUpdates.Version+" 预览版";
        Part<TextBlock>("UpdateStatus").Text="当前版本 "+ShareUpdates.Version+" · 共享预览通道";
        draft=Part<TextBox>("Draft"); invite=Part<TextBox>("Invitation"); inbox=Part<StackPanel>("Inbox");
        status=Part<TextBlock>("Status"); deviceStatus=Part<TextBlock>("DeviceStatus"); notice=Part<TextBlock>("Notice");
        progress=Part<TextBlock>("TransferStatus"); progressBar=Part<ProgressBar>("TransferProgress");
        offerCard=Part<Border>("OfferCard"); offerName=Part<TextBlock>("OfferName");
        networks=Part<ComboBox>("Networks"); nearbyList=Part<StackPanel>("NearbyList");
        networks.SelectionChanged+=(_,_)=>{ discoveryCancel?.Cancel(); nearbyList.Children.Clear(); Part<TextBlock>("NetworkInfo").Text=Nearby.Diagnostics(Network); };
        networks.ItemsSource=Nearby.Networks(); if(networks.Items.Count>0) networks.SelectedIndex=0;
        Part<Button>("Discover").Click+=async(_,_)=>await Discover();
        Part<Button>("RefreshNetworks").Click+=(_,_)=>{ discoveryCancel?.Cancel(); networks.ItemsSource=Nearby.Networks(); if(networks.Items.Count>0) networks.SelectedIndex=0; };
        Part<Button>("CopyBypass").Click+=(_,_)=>Guard(()=>{ if(Network==null) throw new IOException("请先选择网络"); Clipboard.SetText("tun:\n  route-exclude-address:\n    - "+Network.Subnet); notice.Text="已复制当前网段的 TUN 排除片段；请合并并保留原配置"; });
        Part<Button>("ForgetPeers").Click+=(_,_)=>Guard(()=>{ if(connecting) throw new IOException("请等待当前连接完成或取消后再移除"); if(MessageBox.Show(this,"移除所有记住的手机？下次连接需要重新核对识别码。","FloatClip",MessageBoxButton.OKCancel)!=MessageBoxResult.OK) return; Disconnect(); pairStore.Clear(); notice.Text="已移除记住的设备"; });
        Part<Button>("CheckUpdates").Click+=async(_,_)=>await CheckUpdates();
        Part<Button>("DownloadUpdate").Click+=(_,_)=>Guard(()=>{ if(updateRelease!=null) Process.Start(new ProcessStartInfo(updateRelease.Url){UseShellExecute=true}); });
        pages=new FrameworkElement[]{Part<FrameworkElement>("SharePage"),Part<FrameworkElement>("DevicesPage"),Part<FrameworkElement>("SettingsPage")};
        navigation=new Button[]{Part<Button>("NavShare"),Part<Button>("NavDevices"),Part<Button>("NavSettings")};
        for(int i=0;i<3;i++) { int index=i; navigation[i].Click+=(_,_)=>SelectPage(index); }
        Part<Button>("DeviceLink").Click+=(_,_)=>SelectPage(1);
        Part<Button>("PasteText").Click+=(_,_)=>Guard(()=>{ if(Clipboard.ContainsText()) { string text=Clipboard.GetText(); if(System.Text.Encoding.UTF8.GetByteCount(text)>Wire.MaxText) throw new IOException("文字最多 64 KiB"); draft.Text=text; } else notice.Text="剪贴板中没有文字"; });
        Part<Button>("SendText").Click+=async(_,_)=>{
            if(session==null) { notice.Text="请先连接设备"; return; }
            var active=session; string value=draft.Text; notice.Text="正在发送…";
            try { await Task.Run(()=>active.SendText(value)); } catch { notice.Text="发送失败，请检查连接和文字长度"; }
        };
        Part<Button>("ChooseFile").Click+=(_,_)=>Guard(()=>{
            if(session==null) throw new IOException("请先连接设备");
            var dialog=new OpenFileDialog{Title="选择要共享的文件",Multiselect=false,CheckFileExists=true};
            if(dialog.ShowDialog(this)==true) session.SendFile(dialog.FileName);
        });
        Part<Button>("CancelTransfer").Click+=(_,_)=>Disconnect();
        Part<Button>("PasteInvite").Click+=(_,_)=>Guard(()=>{ if(Clipboard.ContainsText()) invite.Text=Clipboard.GetText(); });
        Part<Button>("Connect").Click+=async(_,_)=>await Connect();
        Part<Button>("Disconnect").Click+=(_,_)=>Disconnect();
        Part<Button>("AcceptFile").Click+=(_,_)=>Guard(()=>{ if(offer==null || session==null) return; session.Accept(offer.Id); offer=null; offerCard.Visibility=Visibility.Collapsed; });
        Part<Button>("RejectFile").Click+=(_,_)=>Guard(()=>{ if(offer==null || session==null) return; session.Reject(offer.Id); offer=null; offerCard.Visibility=Visibility.Collapsed; notice.Text="已拒绝文件"; });
        foreach(var pair in new[]{("ThemeSystem","system"),("ThemeLight","light"),("ThemeDark","dark")}) {
            string mode=pair.Item2; Part<Button>(pair.Item1).Click+=(_,_)=>SetTheme(mode,true);
        }
        Part<Button>("ClearCache").Click+=(_,_)=>Guard(()=>{
            if(session!=null) throw new IOException("请先断开连接");
            if(MessageBox.Show(this,"清理接收缓存？已另存的文件不受影响。","FloatClip",MessageBoxButton.OKCancel)!=MessageBoxResult.OK) return;
            foreach(string file in Directory.GetFiles(Path.Combine(root,"Cache"))) System.IO.File.Delete(file);
            items.Clear(); inbox.Children.Clear(); inbox.Children.Add(EmptyInbox()); notice.Text="缓存已清理";
        });
        try { theme=System.IO.File.ReadAllText(Path.Combine(root,"theme.txt")).Trim(); } catch { }
        SetTheme(theme,false); SelectPage(0); inbox.Children.Add(EmptyInbox());
        SystemEvents.UserPreferenceChanged+=SystemThemeChanged;
        Closing+=(_,_)=>{ closing=true; generation++; discoveryCancel?.Cancel(); pairCancel?.Cancel(); session?.Dispose(); SystemEvents.UserPreferenceChanged-=SystemThemeChanged; };
    }
    void SystemThemeChanged(object sender,UserPreferenceChangedEventArgs args) { if(theme=="system") Ui(()=>SetTheme("system",false)); }
    public void SetTheme(string mode,bool persist)
    {
        theme=mode is "dark" or "light" ? mode : "system";
        bool dark=theme=="dark";
        if(theme=="system") { try { dark=(int?)Registry.GetValue(@"HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize","AppsUseLightTheme",1)==0; } catch { } }
        var colors=dark ? new[]{"#0C1422","#162235","#EDF3FC","#A9B8CD","#2D3C52","#8DBBFF","#243B5A"} : new[]{"#F4F7FC","#FFFFFF","#19283F","#596C85","#DFE7F2","#245DCE","#EBF2FF"};
        string[] keys={"Bg","Card","Text","Muted","Line","Accent","Tint"};
        for(int i=0;i<keys.Length;i++) Resources[keys[i]]=new SolidColorBrush((Color)ColorConverter.ConvertFromString(colors[i]));
        if(layout!=null) {
            foreach(var pair in new[]{("ThemeSystem","system","跟随系统"),("ThemeLight","light","浅色"),("ThemeDark","dark","深色")}) Part<Button>(pair.Item1).Content=(theme==pair.Item2 ? "✓  " : "")+pair.Item3;
        }
        if(persist) Guard(()=>System.IO.File.WriteAllText(Path.Combine(root,"theme.txt"),theme));
    }
    void SelectPage(int index) {
        for(int i=0;i<3;i++) { pages[i].Visibility=i==index ? Visibility.Visible : Visibility.Collapsed; navigation[i].SetResourceReference(Control.BackgroundProperty,i==index ? "Tint" : "Card"); navigation[i].SetResourceReference(Control.ForegroundProperty,i==index ? "Accent" : "Muted"); }
        if(index==1 && !discoveredOnce && session==null) { discoveredOnce=true; _=Discover(); }
    }
    async Task Discover()
    {
        if(connecting || session!=null) { notice.Text="请先断开当前连接"; return; }
        var network=Network; if(network==null) { notice.Text="请连接 Wi-Fi 或热点，再点击刷新网卡"; return; }
        discoveryCancel?.Cancel(); var cancel=new CancellationTokenSource(); discoveryCancel=cancel;
        nearbyList.Children.Clear(); nearbyList.Children.Add(Label("正在寻找附近的手机…",14,"Muted"));
        try {
            var found=await Nearby.Discover(network,cancel.Token);
            if(closing || discoveryCancel!=cancel) return;
            nearbyList.Children.Clear();
            foreach(var device in found) {
                var row=new Button{Content="▣  "+device.Name+(pairStore.Get(device.Pin).Length==32 ? " · 已记住" : " · 首次配对"),HorizontalContentAlignment=HorizontalAlignment.Left,Margin=new(0,0,0,10),Padding=new(16,16,16,16)};
                row.Click+=async(_,_)=>await ConnectNearby(device); nearbyList.Children.Add(row);
            }
            if(found.Count==0) nearbyList.Children.Add(Label("暂未发现手机。请打开手机 FloatClip 并允许连接；也可查看下方网络诊断。",14,"Muted"));
            notice.Text=found.Count>0 ? "选择一台手机开始连接" : "未发现设备，请检查手机发现状态和当前网络";
        } catch(OperationCanceledException) { } catch { if(discoveryCancel==cancel) { nearbyList.Children.Clear(); nearbyList.Children.Add(Label("发现失败，请检查所选网卡与网络诊断。",14,"Muted")); } }
        finally { if(discoveryCancel==cancel) discoveryCancel=null; cancel.Dispose(); }
    }
    async Task ConnectNearby(NearbyDevice device)
    {
        if(connecting || session!=null) return;
        discoveryCancel?.Cancel(); int epoch=++generation; connecting=true; status.Text=deviceStatus.Text="正在连接 "+device.Name+"…";
        var cancel=new CancellationTokenSource(); pairCancel=cancel;
        try {
            var connected=await Task.Run(()=>Nearby.Connect(device,Path.Combine(root,"Cache"),pairStore,code=>Dispatcher.Invoke(()=>
                !closing && epoch==generation && MessageBox.Show(this,"请核对手机显示的识别码：\n\n"+code+"\n\n两端完全相同才继续；手机端也需要允许连接。","首次配对 · "+device.Name,MessageBoxButton.OKCancel,MessageBoxImage.Question)==MessageBoxResult.OK),cancel.Token));
            if(closing || epoch!=generation) { connected.Dispose(); return; }
            Attach(connected,epoch); status.Text=deviceStatus.Text="●  已连接 · "+device.Name; invite.Clear();
        } catch(IOException e) { if(epoch==generation) { status.Text=deviceStatus.Text="○  未连接"; notice.Text=e.Message; } }
        catch { if(epoch==generation) { status.Text=deviceStatus.Text="○  未连接"; notice.Text="连接已取消或超时，请重试"; } }
        finally { if(pairCancel==cancel) pairCancel=null; cancel.Dispose(); connecting=false; }
    }
    async Task CheckUpdates()
    {
        if(checkingUpdate) return; checkingUpdate=true; updateRelease=null;
        Part<TextBlock>("UpdateStatus").Text="正在检查更新…"; Part<Button>("DownloadUpdate").Visibility=Visibility.Collapsed;
        try {
            var found=await ShareUpdates.Check(); if(closing) return; updateRelease=found;
            Part<TextBlock>("UpdateStatus").Text=(found.Build>ShareUpdates.Build ? "发现新版本 "+found.Version : found.Build==ShareUpdates.Build ? "已是最新版本 "+ShareUpdates.Version : "当前版本 "+ShareUpdates.Version+"，更新源尚未收录此预览包")+"\n"+found.Notes;
            Part<Button>("DownloadUpdate").Content=found.Build>ShareUpdates.Build ? "下载 "+found.Version+" 更新" : "下载当前版本";
            Part<Button>("DownloadUpdate").Visibility=found.Build>=ShareUpdates.Build ? Visibility.Visible : Visibility.Collapsed;
        } catch { if(!closing) Part<TextBlock>("UpdateStatus").Text="检查失败，请确认网络后重试"; }
        finally { checkingUpdate=false; }
    }
    async Task Connect()
    {
        if(connecting || session!=null) return;
        int epoch=++generation; string code=invite.Text; connecting=true; status.Text=deviceStatus.Text="正在连接…";
        try {
            var selected=Network;
            var connected=await Task.Run(()=>LanClient.Connect(code,Path.Combine(root,"Cache"),selected?.Address,selected?.Index ?? 0));
            if(closing || epoch!=generation) { connected.Dispose(); return; }
            Attach(connected,epoch);
        } catch { if(epoch==generation) { status.Text=deviceStatus.Text="○  未连接"; notice.Text="连接失败，请检查网络或重新生成邀请"; } }
        finally { connecting=false; }
    }
    void Attach(Session connected,int epoch)
    {
            session=connected; invite.Clear(); status.Text=deviceStatus.Text="●  已加密连接 · Android 临时设备"; notice.Text="已连接，可以共享文字和文件";
            connected.Text=(value,outgoing)=>Ui(()=>{ if(epoch!=generation) return; AddItem(value,null,outgoing); notice.Text=outgoing ? "文字已送达" : "收到文字"; });
            connected.Offered=value=>Ui(()=>{ if(epoch!=generation) return; offer=value; offerName.Text=value.Name+" · "+Size(value.Size); offerCard.Visibility=Visibility.Visible; notice.Text="收到文件请求，请在共享页确认"; });
            connected.Progress=(name,done,total,outgoing)=>Ui(()=>{ if(epoch!=generation) return; Part<Button>("CancelTransfer").Visibility=Visibility.Visible; progress.Text=(outgoing ? "发送 " : "接收 ")+name; progressBar.Value=total==0 ? 100 : done*100.0/total; });
            connected.File=(value,path,outgoing)=>Ui(()=>{ if(epoch!=generation) return; progress.Text=value.Name+" · 已校验完成"; progressBar.Value=100; Part<Button>("CancelTransfer").Visibility=Visibility.Collapsed; AddItem(value.Name,outgoing ? null : path,outgoing,true); });
            connected.Notice=value=>Ui(()=>{ if(epoch==generation) notice.Text=value; });
            connected.Closed=()=>Ui(()=>{ if(epoch!=generation) return; session=null; offer=null; offerCard.Visibility=Visibility.Collapsed; status.Text=deviceStatus.Text="○  未连接"; notice.Text="连接已结束，前往设备页重连"; });
            connected.Start(); SelectPage(0);
    }
    void Disconnect() { generation++; pairCancel?.Cancel(); discoveryCancel?.Cancel(); session?.Dispose(); session=null; offer=null; offerCard.Visibility=Visibility.Collapsed; status.Text=deviceStatus.Text="○  未连接"; notice.Text="已断开连接"; Part<Button>("CancelTransfer").Visibility=Visibility.Collapsed; progress.Text=""; progressBar.Value=0; }
    void AddItem(string value,string? file,bool outgoing,bool isFile=false)
    {
        if(items.Count==0) inbox.Children.Clear();
        var panel=new StackPanel(); var card=new Border{CornerRadius=new(14),Padding=new(16),Margin=new(0,0,0,10),BorderThickness=new(1)};
        card.SetResourceReference(Border.BackgroundProperty,"Card"); card.SetResourceReference(Border.BorderBrushProperty,"Line"); card.Child=panel;
        panel.Children.Add(Label(outgoing ? "已送达" : isFile ? "收到文件" : "收到文字",12,"Accent"));
        panel.Children.Add(Label(value.Length>500 ? value[..500]+"…" : value,15,"Text"));
        if(!isFile) { var copy=new Button{Content="复制",HorizontalAlignment=HorizontalAlignment.Left,Margin=new(0,10,0,0),Padding=new(18,8,18,8)}; copy.Click+=(_,_)=>Guard(()=>{ Clipboard.SetText(value); notice.Text="已复制"; }); panel.Children.Add(copy); }
        else if(file!=null) { var save=new Button{Content="另存为…",HorizontalAlignment=HorizontalAlignment.Left,Margin=new(0,10,0,0),Padding=new(18,8,18,8)}; save.Click+=(_,_)=>Guard(()=>{
            var dialog=new SaveFileDialog{FileName=value,Title="保存收到的文件",OverwritePrompt=true}; if(dialog.ShowDialog(this)!=true) return;
            using var input=System.IO.File.OpenRead(file); using var output=new FileStream(dialog.FileName,FileMode.CreateNew,FileAccess.Write); input.CopyTo(output); output.Flush(true); notice.Text="文件已保存";
        }); panel.Children.Add(save); }
        items.Insert(0,(card,file)); inbox.Children.Insert(0,card);
        if(items.Count>8) { var old=items[^1]; items.RemoveAt(items.Count-1); inbox.Children.Remove(old.Card); if(old.Path!=null) try { System.IO.File.Delete(old.Path); } catch { } }
    }
    TextBlock Label(string text,double size,string color) { var label=new TextBlock{Text=text,FontSize=size,TextWrapping=TextWrapping.Wrap,Margin=new(0,4,0,4),MaxHeight=130}; label.SetResourceReference(TextBlock.ForegroundProperty,color); return label; }
    TextBlock EmptyInbox()=>Label("还没有共享内容。连接后，发送第一条文字吧。",14,"Muted");
    void Guard(Action action) { try { action(); } catch(IOException e) { notice.Text=e.Message; } catch { notice.Text="操作未完成，请重试"; } }
    void Ui(Action action) { if(!closing) Dispatcher.BeginInvoke(new Action(()=>{ if(!closing) action(); })); }
    static string Size(long bytes)=>bytes<1024 ? bytes+" B" : (bytes/1048576.0).ToString("F1")+" MiB";

    const string ResourcesXaml="""
    <ResourceDictionary xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation" xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">
      <Style TargetType="TextBlock"><Setter Property="Foreground" Value="{DynamicResource Text}"/><Setter Property="FontSize" Value="14"/></Style>
      <Style TargetType="Button"><Setter Property="Background" Value="{DynamicResource Tint}"/><Setter Property="Foreground" Value="{DynamicResource Accent}"/><Setter Property="FontSize" Value="14"/><Setter Property="Padding" Value="16,11"/><Setter Property="Cursor" Value="Hand"/><Setter Property="Template"><Setter.Value><ControlTemplate TargetType="Button"><Border x:Name="Surface" Background="{TemplateBinding Background}" CornerRadius="10" Padding="{TemplateBinding Padding}"><ContentPresenter HorizontalAlignment="{TemplateBinding HorizontalContentAlignment}" VerticalAlignment="Center"/></Border><ControlTemplate.Triggers><Trigger Property="IsMouseOver" Value="True"><Setter TargetName="Surface" Property="Opacity" Value="0.82"/></Trigger><Trigger Property="IsPressed" Value="True"><Setter TargetName="Surface" Property="Opacity" Value="0.65"/></Trigger><Trigger Property="IsKeyboardFocused" Value="True"><Setter TargetName="Surface" Property="BorderThickness" Value="2"/><Setter TargetName="Surface" Property="BorderBrush" Value="{DynamicResource Accent}"/></Trigger><Trigger Property="IsEnabled" Value="False"><Setter TargetName="Surface" Property="Opacity" Value="0.4"/></Trigger></ControlTemplate.Triggers></ControlTemplate></Setter.Value></Setter></Style>
      <Style x:Key="Primary" TargetType="Button" BasedOn="{StaticResource {x:Type Button}}"><Setter Property="Background" Value="#2563EB"/><Setter Property="Foreground" Value="White"/></Style>
      <Style TargetType="TextBox"><Setter Property="Background" Value="{DynamicResource Bg}"/><Setter Property="Foreground" Value="{DynamicResource Text}"/><Setter Property="CaretBrush" Value="{DynamicResource Text}"/><Setter Property="BorderBrush" Value="{DynamicResource Line}"/><Setter Property="Padding" Value="12"/><Setter Property="FontSize" Value="15"/><Setter Property="SelectionBrush" Value="#80A9FF"/></Style>
      <Style TargetType="ScrollBar"><Setter Property="Width" Value="8"/><Setter Property="Background" Value="{DynamicResource Bg}"/><Setter Property="Template"><Setter.Value><ControlTemplate TargetType="ScrollBar"><Track x:Name="PART_Track" IsDirectionReversed="True" Orientation="Vertical"><Track.DecreaseRepeatButton><RepeatButton Command="ScrollBar.PageUpCommand" Opacity="0" Focusable="False"/></Track.DecreaseRepeatButton><Track.Thumb><Thumb><Thumb.Template><ControlTemplate TargetType="Thumb"><Border Background="{DynamicResource Line}" CornerRadius="4" Margin="1,0"/></ControlTemplate></Thumb.Template></Thumb></Track.Thumb><Track.IncreaseRepeatButton><RepeatButton Command="ScrollBar.PageDownCommand" Opacity="0" Focusable="False"/></Track.IncreaseRepeatButton></Track></ControlTemplate></Setter.Value></Setter></Style>
      <Style x:Key="CardStyle" TargetType="Border"><Setter Property="Background" Value="{DynamicResource Card}"/><Setter Property="BorderBrush" Value="{DynamicResource Line}"/><Setter Property="BorderThickness" Value="1"/><Setter Property="CornerRadius" Value="18"/><Setter Property="Padding" Value="22"/><Setter Property="Margin" Value="0,0,0,16"/></Style>
    </ResourceDictionary>
    """;
    const string LayoutXaml="""
    <Grid xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation" xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">
      <Grid.ColumnDefinitions><ColumnDefinition Width="182"/><ColumnDefinition Width="*"/></Grid.ColumnDefinitions>
      <Border Background="{DynamicResource Card}" BorderBrush="{DynamicResource Line}" BorderThickness="0,0,1,0"><Grid Margin="18,30,18,20"><Grid.RowDefinitions><RowDefinition Height="Auto"/><RowDefinition Height="Auto"/><RowDefinition Height="*"/><RowDefinition Height="Auto"/></Grid.RowDefinitions>
        <StackPanel><Border Background="#2563EB" CornerRadius="13" Width="44" Height="44" HorizontalAlignment="Left"><TextBlock Text="F" FontWeight="SemiBold" FontSize="26" Foreground="White" HorizontalAlignment="Center" VerticalAlignment="Center"/></Border><TextBlock Text="FloatClip" FontSize="23" FontWeight="SemiBold" Margin="0,14,0,4"/><TextBlock Text="跨设备，轻松共享" Foreground="{DynamicResource Muted}" FontSize="12"/></StackPanel>
        <StackPanel Grid.Row="1" Margin="0,34,0,0"><Button x:Name="NavShare" Content="共享" HorizontalContentAlignment="Left" Margin="0,0,0,8"/><Button x:Name="NavDevices" Content="设备" HorizontalContentAlignment="Left" Margin="0,0,0,8"/><Button x:Name="NavSettings" Content="设置" HorizontalContentAlignment="Left"/></StackPanel>
        <TextBlock Grid.Row="3" x:Name="AppVersion" Text="预览版" FontSize="11" Foreground="{DynamicResource Muted}"/>
      </Grid></Border>
      <Grid Grid.Column="1" Margin="32,28,32,16"><Grid.RowDefinitions><RowDefinition Height="*"/><RowDefinition Height="Auto"/></Grid.RowDefinitions>
        <ScrollViewer x:Name="SharePage" VerticalScrollBarVisibility="Auto" HorizontalScrollBarVisibility="Disabled"><StackPanel Margin="0,0,10,0">
          <TextBlock Text="共享" FontSize="28" FontWeight="SemiBold"/><TextBlock Text="文字和文件，直达另一台设备" Foreground="{DynamicResource Muted}" Margin="0,7,0,20"/>
          <Border Style="{DynamicResource CardStyle}"><DockPanel><Button x:Name="DeviceLink" Content="连接设备" DockPanel.Dock="Right" HorizontalAlignment="Right"/><StackPanel><TextBlock x:Name="Status" Text="○  未连接" Foreground="{DynamicResource Accent}" FontWeight="SemiBold"/><TextBlock Text="局域网直连 · 内容加密" FontSize="12" Foreground="{DynamicResource Muted}" Margin="0,6,0,0"/></StackPanel></DockPanel></Border>
          <Border Style="{DynamicResource CardStyle}"><StackPanel><TextBlock Text="发送文字" FontSize="18" FontWeight="SemiBold"/><TextBox x:Name="Draft" AcceptsReturn="True" TextWrapping="Wrap" MinHeight="100" MaxHeight="220" VerticalScrollBarVisibility="Auto" Margin="0,14,0,12"/><StackPanel Orientation="Horizontal" HorizontalAlignment="Right"><Button x:Name="PasteText" Content="粘贴" Margin="0,0,10,0"/><Button x:Name="SendText" Content="发送文字" Style="{DynamicResource Primary}"/></StackPanel></StackPanel></Border>
          <Border Style="{DynamicResource CardStyle}"><StackPanel><DockPanel><Button x:Name="ChooseFile" Content="选择文件" DockPanel.Dock="Right" HorizontalAlignment="Right"/><StackPanel><TextBlock Text="发送文件" FontSize="18" FontWeight="SemiBold"/><TextBlock Text="单个文件 · 最大 64 MiB" FontSize="12" Foreground="{DynamicResource Muted}" Margin="0,6,0,0"/></StackPanel></DockPanel><TextBlock x:Name="TransferStatus" FontSize="12" Foreground="{DynamicResource Accent}" Margin="0,12,0,4" TextTrimming="CharacterEllipsis"/><ProgressBar x:Name="TransferProgress" Height="4" Maximum="100" Foreground="#2563EB" Background="{DynamicResource Tint}"/><Button x:Name="CancelTransfer" Content="取消传输并断开" Visibility="Collapsed" HorizontalAlignment="Left" Margin="0,12,0,0" Padding="12,7"/></StackPanel></Border>
          <Border x:Name="OfferCard" Style="{DynamicResource CardStyle}" Visibility="Collapsed"><StackPanel><TextBlock Text="对方想发送文件" FontWeight="SemiBold"/><TextBlock x:Name="OfferName" Margin="0,8,0,12" TextWrapping="Wrap"/><StackPanel Orientation="Horizontal"><Button x:Name="AcceptFile" Content="接收文件" Style="{DynamicResource Primary}" Margin="0,0,10,0"/><Button x:Name="RejectFile" Content="拒绝"/></StackPanel></StackPanel></Border>
          <TextBlock Text="最近共享" FontSize="18" FontWeight="SemiBold" Margin="0,8,0,6"/><TextBlock Text="收到后由你决定复制或保存" Foreground="{DynamicResource Muted}" FontSize="12" Margin="0,0,0,16"/><StackPanel x:Name="Inbox"/>
        </StackPanel></ScrollViewer>
        <ScrollViewer x:Name="DevicesPage" Visibility="Collapsed" VerticalScrollBarVisibility="Auto"><StackPanel Margin="0,0,10,0">
          <TextBlock Text="连接设备" FontSize="28" FontWeight="SemiBold"/><TextBlock Text="同一 Wi-Fi 或手机热点，无需复制链接" Foreground="{DynamicResource Muted}" Margin="0,7,0,20"/>
          <Border Style="{DynamicResource CardStyle}"><StackPanel>
            <DockPanel><Button x:Name="Disconnect" Content="断开" DockPanel.Dock="Right" HorizontalAlignment="Right"/><TextBlock x:Name="DeviceStatus" Text="○  未连接" Foreground="{DynamicResource Accent}" FontWeight="SemiBold" VerticalAlignment="Center"/></DockPanel>
            <TextBlock Text="附近的手机" FontSize="20" FontWeight="SemiBold" Margin="0,20,0,8"/>
            <TextBlock Text="在手机打开 FloatClip 并允许连接。首次核对识别码，以后可直接重连。" TextWrapping="Wrap" Foreground="{DynamicResource Muted}" Margin="0,0,0,16"/>
            <StackPanel x:Name="NearbyList"/>
            <Grid Margin="0,8,0,0"><Grid.ColumnDefinitions><ColumnDefinition Width="*"/><ColumnDefinition Width="Auto"/><ColumnDefinition Width="Auto"/></Grid.ColumnDefinitions>
              <ComboBox x:Name="Networks" MinHeight="40" VerticalContentAlignment="Center" Foreground="#18243A" Margin="0,0,10,0"/>
              <Button x:Name="RefreshNetworks" Grid.Column="1" Content="刷新网卡" Margin="0,0,10,0"/><Button x:Name="Discover" Grid.Column="2" Content="查找设备" Style="{DynamicResource Primary}"/>
            </Grid>
          </StackPanel></Border>
          <Border Style="{DynamicResource CardStyle}"><Expander Header="连接不成功？查看网络诊断" Foreground="{DynamicResource Text}"><StackPanel Margin="0,16,0,0"><TextBlock x:Name="NetworkInfo" Text="请选择可用网卡" TextWrapping="Wrap" Foreground="{DynamicResource Muted}"/><Button x:Name="CopyBypass" Content="复制当前网段的 TUN 排除配置" HorizontalAlignment="Left" Margin="0,16,0,0"/></StackPanel></Expander></Border>
          <Border Style="{DynamicResource CardStyle}"><Expander Header="备用：粘贴邀请链接" Foreground="{DynamicResource Text}"><StackPanel Margin="0,16,0,0"><TextBox x:Name="Invitation" Height="88" TextWrapping="Wrap" AcceptsReturn="True" VerticalScrollBarVisibility="Auto"/><StackPanel Orientation="Horizontal" HorizontalAlignment="Right" Margin="0,14,0,0"><Button x:Name="PasteInvite" Content="粘贴邀请" Margin="0,0,10,0"/><Button x:Name="Connect" Content="连接" Style="{DynamicResource Primary}"/></StackPanel></StackPanel></Expander></Border>
          <Button x:Name="ForgetPeers" Content="移除所有记住的手机" HorizontalAlignment="Left"/>
        </StackPanel></ScrollViewer>
        <ScrollViewer x:Name="SettingsPage" Visibility="Collapsed" VerticalScrollBarVisibility="Auto"><StackPanel Margin="0,0,10,0"><TextBlock Text="设置" FontSize="28" FontWeight="SemiBold"/><TextBlock Text="按你的习惯，保持简单" Foreground="{DynamicResource Muted}" Margin="0,7,0,20"/>
          <Border Style="{DynamicResource CardStyle}"><StackPanel><TextBlock Text="外观" FontSize="18" FontWeight="SemiBold"/><StackPanel Orientation="Horizontal" Margin="0,16,0,0"><Button x:Name="ThemeSystem" Content="跟随系统" Margin="0,0,10,0"/><Button x:Name="ThemeLight" Content="浅色" Margin="0,0,10,0"/><Button x:Name="ThemeDark" Content="深色"/></StackPanel></StackPanel></Border>
          <Border Style="{DynamicResource CardStyle}"><StackPanel><TextBlock Text="共享方式" FontSize="18" FontWeight="SemiBold"/><TextBlock Text="前台手动共享 · 加密直连" Foreground="{DynamicResource Muted}" Margin="0,14,0,8"/><TextBlock Text="文件速度上限 2 MiB/s；暂不自动同步系统剪贴板。" TextWrapping="Wrap" Foreground="{DynamicResource Muted}"/><Button x:Name="ClearCache" Content="清理接收缓存" HorizontalAlignment="Left" Margin="0,18,0,0"/></StackPanel></Border>
          <Border Style="{DynamicResource CardStyle}"><StackPanel><TextBlock Text="版本与更新" FontSize="18" FontWeight="SemiBold"/><TextBlock x:Name="UpdateStatus" Text="共享预览通道" TextWrapping="Wrap" Foreground="{DynamicResource Muted}" Margin="0,12,0,14"/><StackPanel Orientation="Horizontal"><Button x:Name="CheckUpdates" Content="检查更新" Margin="0,0,10,0"/><Button x:Name="DownloadUpdate" Content="下载更新" Style="{DynamicResource Primary}" Visibility="Collapsed"/></StackPanel></StackPanel></Border>
        </StackPanel></ScrollViewer>
        <TextBlock x:Name="Notice" Grid.Row="1" Text="添加设备，开始共享" Foreground="{DynamicResource Muted}" FontSize="12" Margin="0,12,0,0" TextTrimming="CharacterEllipsis"/>
      </Grid>
    </Grid>
    """;
}
