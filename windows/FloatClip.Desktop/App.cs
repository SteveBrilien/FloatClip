using System;
using System.Collections.Generic;
using System.IO;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Markup;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Microsoft.Win32;
using FloatClip.Share;

namespace FloatClip.Desktop;

public static class Program
{
    [STAThread] public static void Main(string[] args)
    {
        try {
            if(args.Length==3 && args[0]=="--qa") { Acceptance.Run(args[1],args[2]); return; }
            var app=new Application(); var window=new MainWindow();
            if(args.Length==3 && args[0]=="--capture") {
                window.SetTheme(args[1],false);
                window.ContentRendered+=(_,_)=>window.Dispatcher.BeginInvoke(new Action(()=>{
                    var bitmap=new RenderTargetBitmap((int)window.ActualWidth,(int)window.ActualHeight,96,96,PixelFormats.Pbgra32);
                    bitmap.Render(window); var png=new PngBitmapEncoder(); png.Frames.Add(BitmapFrame.Create(bitmap));
                    using(var file=System.IO.File.Create(args[2])) png.Save(file); window.Close();
                }),System.Windows.Threading.DispatcherPriority.ContextIdle);
            }
            app.Run(window);
        } catch(Exception e) {
            Environment.ExitCode=1;
            if(args.Length>0 && args[0]=="--qa") return;
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
        Resources=(ResourceDictionary)XamlReader.Parse(ResourcesXaml);
        SetResourceReference(BackgroundProperty,"Bg");
        layout=(Grid)XamlReader.Parse(LayoutXaml); Content=layout;
        draft=Part<TextBox>("Draft"); invite=Part<TextBox>("Invitation"); inbox=Part<StackPanel>("Inbox");
        status=Part<TextBlock>("Status"); deviceStatus=Part<TextBlock>("DeviceStatus"); notice=Part<TextBlock>("Notice");
        progress=Part<TextBlock>("TransferStatus"); progressBar=Part<ProgressBar>("TransferProgress");
        offerCard=Part<Border>("OfferCard"); offerName=Part<TextBlock>("OfferName");
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
        Closing+=(_,_)=>{ closing=true; generation++; session?.Dispose(); SystemEvents.UserPreferenceChanged-=SystemThemeChanged; };
    }
    void SystemThemeChanged(object sender,UserPreferenceChangedEventArgs args) { if(theme=="system") Ui(()=>SetTheme("system",false)); }
    public void SetTheme(string mode,bool persist)
    {
        theme=mode is "dark" or "light" ? mode : "system";
        bool dark=theme=="dark";
        if(theme=="system") { try { dark=(int?)Registry.GetValue(@"HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize","AppsUseLightTheme",1)==0; } catch { } }
        var colors=dark ? new[]{"#0D1424","#152139","#EAF0FA","#A2B2CA","#2B3B54","#60A5FA","#20375B"} : new[]{"#F5F8FC","#FFFFFF","#18243A","#61718A","#E1E8F2","#2563EB","#EAF1FF"};
        string[] keys={"Bg","Card","Text","Muted","Line","Accent","Tint"};
        for(int i=0;i<keys.Length;i++) Resources[keys[i]]=new SolidColorBrush((Color)ColorConverter.ConvertFromString(colors[i]));
        if(layout!=null) {
            foreach(var pair in new[]{("ThemeSystem","system","跟随系统"),("ThemeLight","light","浅色"),("ThemeDark","dark","深色")}) Part<Button>(pair.Item1).Content=(theme==pair.Item2 ? "✓  " : "")+pair.Item3;
        }
        if(persist) Guard(()=>System.IO.File.WriteAllText(Path.Combine(root,"theme.txt"),theme));
    }
    void SelectPage(int index) {
        for(int i=0;i<3;i++) { pages[i].Visibility=i==index ? Visibility.Visible : Visibility.Collapsed; navigation[i].SetResourceReference(Control.BackgroundProperty,i==index ? "Tint" : "Card"); navigation[i].SetResourceReference(Control.ForegroundProperty,i==index ? "Accent" : "Muted"); }
    }
    async Task Connect()
    {
        if(connecting || session!=null) return;
        int epoch=++generation; string code=invite.Text; connecting=true; status.Text=deviceStatus.Text="正在连接…";
        try {
            var connected=await Task.Run(()=>LanClient.Connect(code,Path.Combine(root,"Cache")));
            if(closing || epoch!=generation) { connected.Dispose(); return; }
            session=connected; invite.Clear(); status.Text=deviceStatus.Text="●  已加密连接 · Android 临时设备"; notice.Text="已连接，可以共享文字和文件";
            connected.Text=(value,outgoing)=>Ui(()=>{ if(epoch!=generation) return; AddItem(value,null,outgoing); notice.Text=outgoing ? "文字已送达" : "收到文字"; });
            connected.Offered=value=>Ui(()=>{ if(epoch!=generation) return; offer=value; offerName.Text=value.Name+" · "+Size(value.Size); offerCard.Visibility=Visibility.Visible; notice.Text="收到文件请求，请在共享页确认"; });
            connected.Progress=(name,done,total,outgoing)=>Ui(()=>{ if(epoch!=generation) return; Part<Button>("CancelTransfer").Visibility=Visibility.Visible; progress.Text=(outgoing ? "发送 " : "接收 ")+name; progressBar.Value=total==0 ? 100 : done*100.0/total; });
            connected.File=(value,path,outgoing)=>Ui(()=>{ if(epoch!=generation) return; progress.Text=value.Name+" · 已校验完成"; progressBar.Value=100; Part<Button>("CancelTransfer").Visibility=Visibility.Collapsed; AddItem(value.Name,outgoing ? null : path,outgoing,true); });
            connected.Notice=value=>Ui(()=>{ if(epoch==generation) notice.Text=value; });
            connected.Closed=()=>Ui(()=>{ if(epoch!=generation) return; session=null; offer=null; offerCard.Visibility=Visibility.Collapsed; status.Text=deviceStatus.Text="○  未连接"; notice.Text="连接已结束，请重新生成邀请"; });
            connected.Start(); SelectPage(0);
        } catch { if(epoch==generation) { status.Text=deviceStatus.Text="○  未连接"; notice.Text="连接失败，请检查网络或重新生成邀请"; } }
        finally { connecting=false; }
    }
    void Disconnect() { generation++; session?.Dispose(); session=null; offer=null; offerCard.Visibility=Visibility.Collapsed; status.Text=deviceStatus.Text="○  未连接"; notice.Text="已断开连接"; Part<Button>("CancelTransfer").Visibility=Visibility.Collapsed; progress.Text=""; progressBar.Value=0; }
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
      <Style x:Key="CardStyle" TargetType="Border"><Setter Property="Background" Value="{DynamicResource Card}"/><Setter Property="BorderBrush" Value="{DynamicResource Line}"/><Setter Property="BorderThickness" Value="1"/><Setter Property="CornerRadius" Value="18"/><Setter Property="Padding" Value="22"/><Setter Property="Margin" Value="0,0,0,16"/></Style>
    </ResourceDictionary>
    """;
    const string LayoutXaml="""
    <Grid xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation" xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">
      <Grid.ColumnDefinitions><ColumnDefinition Width="182"/><ColumnDefinition Width="*"/></Grid.ColumnDefinitions>
      <Border Background="{DynamicResource Card}" BorderBrush="{DynamicResource Line}" BorderThickness="0,0,1,0"><Grid Margin="18,30,18,20"><Grid.RowDefinitions><RowDefinition Height="Auto"/><RowDefinition Height="Auto"/><RowDefinition Height="*"/><RowDefinition Height="Auto"/></Grid.RowDefinitions>
        <StackPanel><Border Background="#2563EB" CornerRadius="13" Width="44" Height="44" HorizontalAlignment="Left"><TextBlock Text="F" FontWeight="SemiBold" FontSize="26" Foreground="White" HorizontalAlignment="Center" VerticalAlignment="Center"/></Border><TextBlock Text="FloatClip" FontSize="23" FontWeight="SemiBold" Margin="0,14,0,4"/><TextBlock Text="跨设备，轻松共享" Foreground="{DynamicResource Muted}" FontSize="12"/></StackPanel>
        <StackPanel Grid.Row="1" Margin="0,34,0,0"><Button x:Name="NavShare" Content="共享" HorizontalContentAlignment="Left" Margin="0,0,0,8"/><Button x:Name="NavDevices" Content="设备" HorizontalContentAlignment="Left" Margin="0,0,0,8"/><Button x:Name="NavSettings" Content="设置" HorizontalContentAlignment="Left"/></StackPanel>
        <TextBlock Grid.Row="3" Text="0.2.0 预览版" FontSize="11" Foreground="{DynamicResource Muted}"/>
      </Grid></Border>
      <Grid Grid.Column="1" Margin="32,28,32,16"><Grid.RowDefinitions><RowDefinition Height="*"/><RowDefinition Height="Auto"/></Grid.RowDefinitions>
        <ScrollViewer x:Name="SharePage" VerticalScrollBarVisibility="Auto" HorizontalScrollBarVisibility="Disabled"><StackPanel Margin="0,0,10,0">
          <TextBlock Text="共享" FontSize="28" FontWeight="SemiBold"/><TextBlock Text="文字和文件，直达另一台设备" Foreground="{DynamicResource Muted}" Margin="0,7,0,20"/>
          <Border Style="{DynamicResource CardStyle}"><DockPanel><Button x:Name="DeviceLink" Content="连接设备" DockPanel.Dock="Right" HorizontalAlignment="Right"/><StackPanel><TextBlock x:Name="Status" Text="○  未连接" Foreground="{DynamicResource Accent}" FontWeight="SemiBold"/><TextBlock Text="局域网直连 · 内容加密" FontSize="12" Foreground="{DynamicResource Muted}" Margin="0,6,0,0"/></StackPanel></DockPanel></Border>
          <Border Style="{DynamicResource CardStyle}"><StackPanel><TextBlock Text="发送文字" FontSize="18" FontWeight="SemiBold"/><TextBox x:Name="Draft" AcceptsReturn="True" TextWrapping="Wrap" MinHeight="116" MaxHeight="220" VerticalScrollBarVisibility="Auto" Margin="0,14,0,12"/><StackPanel Orientation="Horizontal" HorizontalAlignment="Right"><Button x:Name="PasteText" Content="粘贴" Margin="0,0,10,0"/><Button x:Name="SendText" Content="发送文字" Style="{DynamicResource Primary}"/></StackPanel></StackPanel></Border>
          <Border Style="{DynamicResource CardStyle}"><StackPanel><DockPanel><Button x:Name="ChooseFile" Content="选择文件" DockPanel.Dock="Right" HorizontalAlignment="Right"/><StackPanel><TextBlock Text="发送文件" FontSize="18" FontWeight="SemiBold"/><TextBlock Text="单个文件 · 最大 64 MiB" FontSize="12" Foreground="{DynamicResource Muted}" Margin="0,6,0,0"/></StackPanel></DockPanel><TextBlock x:Name="TransferStatus" FontSize="12" Foreground="{DynamicResource Accent}" Margin="0,12,0,4" TextTrimming="CharacterEllipsis"/><ProgressBar x:Name="TransferProgress" Height="4" Maximum="100" Foreground="#2563EB" Background="{DynamicResource Tint}"/><Button x:Name="CancelTransfer" Content="取消传输并断开" Visibility="Collapsed" HorizontalAlignment="Left" Margin="0,12,0,0" Padding="12,7"/></StackPanel></Border>
          <Border x:Name="OfferCard" Style="{DynamicResource CardStyle}" Visibility="Collapsed"><StackPanel><TextBlock Text="对方想发送文件" FontWeight="SemiBold"/><TextBlock x:Name="OfferName" Margin="0,8,0,12" TextWrapping="Wrap"/><StackPanel Orientation="Horizontal"><Button x:Name="AcceptFile" Content="接收文件" Style="{DynamicResource Primary}" Margin="0,0,10,0"/><Button x:Name="RejectFile" Content="拒绝"/></StackPanel></StackPanel></Border>
          <TextBlock Text="最近共享" FontSize="18" FontWeight="SemiBold" Margin="0,8,0,6"/><TextBlock Text="收到后由你决定复制或保存" Foreground="{DynamicResource Muted}" FontSize="12" Margin="0,0,0,16"/><StackPanel x:Name="Inbox"/>
        </StackPanel></ScrollViewer>
        <ScrollViewer x:Name="DevicesPage" Visibility="Collapsed" VerticalScrollBarVisibility="Auto"><StackPanel Margin="0,0,10,0"><TextBlock Text="设备" FontSize="28" FontWeight="SemiBold"/><TextBlock Text="连接同一网络中的 Android 手机" Foreground="{DynamicResource Muted}" Margin="0,7,0,20"/>
          <Border Style="{DynamicResource CardStyle}"><StackPanel><TextBlock x:Name="DeviceStatus" Text="○  未连接" Foreground="{DynamicResource Accent}" FontWeight="SemiBold"/><TextBlock Text="在手机的设备页生成邀请，然后粘贴到这里。" Foreground="{DynamicResource Muted}" Margin="0,16,0,10" TextWrapping="Wrap"/><TextBox x:Name="Invitation" Height="108" TextWrapping="Wrap" AcceptsReturn="True" VerticalScrollBarVisibility="Auto"/><StackPanel Orientation="Horizontal" HorizontalAlignment="Right" Margin="0,14,0,0"><Button x:Name="PasteInvite" Content="粘贴邀请" Margin="0,0,10,0"/><Button x:Name="Connect" Content="连接" Style="{DynamicResource Primary}" Margin="0,0,10,0"/><Button x:Name="Disconnect" Content="断开"/></StackPanel></StackPanel></Border>
          <Border Style="{DynamicResource CardStyle}"><StackPanel><TextBlock Text="临时连接" FontSize="17" FontWeight="SemiBold"/><TextBlock Text="邀请只使用一次。手机保持前台；断开后重新生成邀请。" Foreground="{DynamicResource Muted}" TextWrapping="Wrap" Margin="0,10,0,0"/></StackPanel></Border>
        </StackPanel></ScrollViewer>
        <ScrollViewer x:Name="SettingsPage" Visibility="Collapsed" VerticalScrollBarVisibility="Auto"><StackPanel Margin="0,0,10,0"><TextBlock Text="设置" FontSize="28" FontWeight="SemiBold"/><TextBlock Text="按你的习惯，保持简单" Foreground="{DynamicResource Muted}" Margin="0,7,0,20"/>
          <Border Style="{DynamicResource CardStyle}"><StackPanel><TextBlock Text="外观" FontSize="18" FontWeight="SemiBold"/><StackPanel Orientation="Horizontal" Margin="0,16,0,0"><Button x:Name="ThemeSystem" Content="跟随系统" Margin="0,0,10,0"/><Button x:Name="ThemeLight" Content="浅色" Margin="0,0,10,0"/><Button x:Name="ThemeDark" Content="深色"/></StackPanel></StackPanel></Border>
          <Border Style="{DynamicResource CardStyle}"><StackPanel><TextBlock Text="共享方式" FontSize="18" FontWeight="SemiBold"/><TextBlock Text="前台手动共享 · 加密直连" Foreground="{DynamicResource Muted}" Margin="0,14,0,8"/><TextBlock Text="文件速度上限 2 MiB/s；暂不自动同步系统剪贴板。" TextWrapping="Wrap" Foreground="{DynamicResource Muted}"/><Button x:Name="ClearCache" Content="清理接收缓存" HorizontalAlignment="Left" Margin="0,18,0,0"/></StackPanel></Border>
        </StackPanel></ScrollViewer>
        <TextBlock x:Name="Notice" Grid.Row="1" Text="添加设备，开始共享" Foreground="{DynamicResource Muted}" FontSize="12" Margin="0,12,0,0" TextTrimming="CharacterEllipsis"/>
      </Grid>
    </Grid>
    """;
}
