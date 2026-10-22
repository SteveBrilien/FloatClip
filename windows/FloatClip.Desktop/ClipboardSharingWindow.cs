using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using FloatClip.Share;

namespace FloatClip.Desktop;

/** A modeless device surface; trusted sessions belong to the resident host. */
public sealed class ClipboardSharingView:UserControl,IDisposable
{
    readonly NativeClipboardSync sync;
    readonly StackPanel content=new(),peers=new(),nearby=new();
    readonly TextBlock status=new(),pairingStatus=new();
    readonly Wpf.Ui.Controls.ToggleSwitch master=new(),automatic=new(),send=new(),receive=new(),apply=new();
    readonly ComboBox networks=new();
    readonly Button bluetooth,discover,cancel;
    readonly CancellationTokenSource close=new();
    CancellationTokenSource? operation;
    bool refreshing,closed,busy;
    public ClipboardSharingView(NativeClipboardSync sync)
    {
        this.sync=sync;
        SetResourceReference(BackgroundProperty,"FloatClip.Background");SetResourceReference(ForegroundProperty,"FloatClip.Text");
        FontFamily=new System.Windows.Media.FontFamily("Segoe UI Variable, Segoe UI, Microsoft YaHei UI");FontSize=14;
        content.Margin=new(18,6,18,20);Content=new ScrollViewer{Content=content,VerticalScrollBarVisibility=ScrollBarVisibility.Auto};
        var heading=new DockPanel{LastChildFill=true,Margin=new(0,0,0,8)};
        master.OnContent=master.OffContent="";master.VerticalAlignment=VerticalAlignment.Center;master.Opacity=1;master.ToolTip="共享剪贴板";System.Windows.Automation.AutomationProperties.SetName(master,"共享剪贴板");master.Click+=(_,_)=>Configure();DockPanel.SetDock(master,Dock.Right);heading.Children.Add(master);
        heading.Children.Add(new TextBlock{Text="我的设备",FontSize=23});content.Children.Add(heading);
        status.SetResourceReference(TextBlock.ForegroundProperty,"FloatClip.Muted");status.TextWrapping=TextWrapping.Wrap;status.Margin=new(0,0,0,8);content.Children.Add(status);
        void Option(Wpf.Ui.Controls.ToggleSwitch box,string label,Panel parent){box.OnContent=box.OffContent="";System.Windows.Automation.AutomationProperties.SetName(box,label);box.Click+=(_,_)=>Configure();var row=new DockPanel{Margin=new(0,6,0,6),LastChildFill=true};box.Margin=new(14,0,0,0);DockPanel.SetDock(box,Dock.Right);row.Children.Add(box);var text=new TextBlock{Text=label,TextWrapping=TextWrapping.Wrap,VerticalAlignment=VerticalAlignment.Center};text.SetResourceReference(TextBlock.ForegroundProperty,"FloatClip.Text");row.Children.Add(text);parent.Children.Add(row);}

        var pairing=new StackPanel();pairing.Children.Add(Hint("手机开启共享并允许添加设备，连接后核对两端识别码。"));
        bluetooth=Button("通过蓝牙添加（推荐）",()=>_ = RunConnection(async token=>{
            await sync.PairBluetoothAsync(code=>Confirm(code,"首次蓝牙配对"),token);
            if(!closed)pairingStatus.Text="手机已授权。开启自动连接后，复制新文字即可触发蓝牙同步。";
        },"正在查找系统已配对的蓝牙设备…"));
        System.Windows.Automation.AutomationProperties.SetAutomationId(bluetooth,"PairBluetooth");pairing.Children.Add(bluetooth);
        bluetooth.ToolTip="先在系统蓝牙设置中配对。蓝牙仅在有文字待发时建立短连接；系统蓝牙已连接不代表同步握手已完成。";
        pairing.Children.Add(Button("打开 Windows 蓝牙设置",()=>{try{Process.Start(new ProcessStartInfo("ms-settings:bluetooth"){UseShellExecute=true});}catch{pairingStatus.Text="请从 Windows 设置中打开蓝牙和设备。";}}));

        var lan=new Expander{Header="通过局域网添加",Margin=new(0,16,0,0)};lan.SetResourceReference(ForegroundProperty,"FloatClip.Text");var lanPanel=new StackPanel();
        lanPanel.Children.Add(Hint("两端连接同一 Wi-Fi 或手机热点，再查找设备。切换网络后可刷新列表。"));
        networks.SetResourceReference(ComboBox.BackgroundProperty,"FloatClip.Surface");networks.SetResourceReference(ComboBox.ForegroundProperty,"FloatClip.Text");networks.MinHeight=32;lanPanel.Children.Add(networks);
        lanPanel.Children.Add(Button("刷新网络",RefreshNetworks));
        discover=Button("查找手机",()=>_ = RunConnection(async token=>{
            if(networks.SelectedItem is not LanNetwork network)throw new IOException("请先连接 Wi-Fi 或热点，并选择对应的网络。");
            nearby.Children.Clear();var found=await Nearby.Discover(network,token,true);if(closed)return;
            foreach(var device in found)nearby.Children.Add(Button(device.Name,()=>_ = RunConnection(async pairToken=>{
                await sync.PairAsync(device,code=>Confirm(code,"首次局域网配对"),pairToken);
                if(!closed)pairingStatus.Text="手机已授权，连接状态见上方。";
            },"正在连接 "+device.Name+"，请核对两端识别码…")));
            pairingStatus.Text=found.Count==0?"未发现手机。请重新打开手机的添加设备窗口，并检查两端网络是否互通。":"选择手机并核对两端识别码。";
        },"正在查找手机…"));
        System.Windows.Automation.AutomationProperties.SetAutomationId(discover,"PairLan");lanPanel.Children.Add(discover);lanPanel.Children.Add(nearby);lan.Content=lanPanel;pairing.Children.Add(lan);
        pairingStatus.SetResourceReference(TextBlock.ForegroundProperty,"FloatClip.Muted");pairingStatus.TextWrapping=TextWrapping.Wrap;pairingStatus.Margin=new(0,12,0,0);pairing.Children.Add(pairingStatus);
        cancel=Button("取消添加",()=>operation?.Cancel());cancel.Visibility=Visibility.Collapsed;pairing.Children.Add(cancel);
        var peerHeading=new DockPanel{Margin=new(0,10,0,8)};var retry=Button("重新连接",()=>{try{sync.ReconnectNow();}catch(IOException e){pairingStatus.Text=e.Message;}});DockPanel.SetDock(retry,Dock.Right);peerHeading.Children.Add(retry);peerHeading.Children.Add(new TextBlock{Text="已配对设备",FontSize=15,VerticalAlignment=VerticalAlignment.Center});content.Children.Add(peerHeading);content.Children.Add(peers);
        var addDevice=new Expander{Header="添加设备",Content=pairing,IsExpanded=sync.Pairing.Keys().Length==0,Margin=new(0,4,0,12)};addDevice.SetResourceReference(ForegroundProperty,"FloatClip.Text");content.Children.Add(addDevice);

        var options=new StackPanel();Option(automatic,"自动连接已授权设备",options);Option(send,"发送新复制的文字",options);Option(receive,"接收文字到历史",options);Option(apply,"接收后写入系统剪贴板",options);
        options.Margin=new(14,6,14,8);var advanced=new Expander{Header="同步选项",Content=options,Margin=new(0,0,0,10)};advanced.SetResourceReference(ForegroundProperty,"FloatClip.Text");content.Children.Add(advanced);
        RefreshNetworks();sync.Changed+=Changed;
        Refresh();
    }
    public void Dispose(){if(closed)return;closed=true;close.Cancel();sync.Changed-=Changed;}
    bool Confirm(string code,string title)=>Dispatcher.Invoke(()=>!closed&&MessageBox.Show(Window.GetWindow(this),"请核对手机显示的识别码：\n\n"+code+"\n\n两端完全一致后才允许连接，并在手机上确认。",title,MessageBoxButton.OKCancel,MessageBoxImage.Question)==MessageBoxResult.OK);
    async Task RunConnection(Func<CancellationToken,Task> action,string message)
    {
        if(busy||closed)return;
        if(!sync.Snapshot().Enabled){pairingStatus.Text="请先开启上方的共享剪贴板。";return;}
        using var current=CancellationTokenSource.CreateLinkedTokenSource(close.Token);operation=current;busy=true;ConnectionControls();pairingStatus.Text=message;
        try{await action(current.Token);}
        catch(OperationCanceledException){if(!closed)pairingStatus.Text="已取消添加设备。";}
        catch(Exception e){if(!closed)pairingStatus.Text=e is IOException?e.Message:"连接未完成，请检查手机的添加设备窗口后重试。";}
        finally{operation=null;busy=false;if(!closed){ConnectionControls();Refresh();}}
    }
    void ConnectionControls(){bluetooth.IsEnabled=!busy;discover.IsEnabled=!busy;networks.IsEnabled=!busy;nearby.IsEnabled=!busy;cancel.Visibility=busy?Visibility.Visible:Visibility.Collapsed;}
    void RefreshNetworks()
    {
        if(busy)return;
        try{string? adapter=(networks.SelectedItem as LanNetwork)?.AdapterId;var found=Nearby.Networks();networks.ItemsSource=found;networks.SelectedItem=found.FirstOrDefault(n=>n.AdapterId==adapter)??found.FirstOrDefault();}
        catch{pairingStatus.Text="暂时无法读取网络，请连接 Wi-Fi 后刷新。";}
    }
    static TextBlock Hint(string label){var block=new TextBlock{Text=label,TextWrapping=TextWrapping.Wrap,Margin=new(0,4,0,8),FontSize=13};block.SetResourceReference(TextBlock.ForegroundProperty,"FloatClip.Muted");return block;}
    static Border Card(UIElement child){var card=new Border{Child=child,Padding=new(14),Margin=new(0,0,0,10),CornerRadius=new(7),BorderThickness=new(1)};card.SetResourceReference(Border.BackgroundProperty,"FloatClip.Surface");card.SetResourceReference(Border.BorderBrushProperty,"FloatClip.Line");return card;}
    static Button Button(string label,Action action){var b=new Wpf.Ui.Controls.Button{Content=label,MinHeight=32,Padding=new(12,5,12,5),Margin=new(0,6,0,0),HorizontalAlignment=HorizontalAlignment.Left};b.SetResourceReference(Control.ForegroundProperty,"FloatClip.Text");b.Click+=(_,_)=>action();return b;}
    void Configure()
    {
        if(refreshing)return;
        try{if(master.IsChecked!=true)operation?.Cancel();sync.Configure(master.IsChecked==true,send.IsChecked==true,receive.IsChecked==true,apply.IsChecked==true,automatic.IsChecked==true);}
        catch(Exception e){pairingStatus.Text=e is IOException?e.Message:"共享设置未保存。";}Refresh();
    }
    void Changed(){if(!closed)Dispatcher.BeginInvoke(new Action(()=>{if(!closed)Refresh();}));}
    void Refresh()
    {
        refreshing=true;
        try{
            var state=sync.Snapshot();master.IsChecked=state.Enabled;send.IsChecked=state.Send;receive.IsChecked=state.Receive;apply.IsChecked=state.Apply;automatic.IsChecked=sync.Automatic;status.Text=sync.Status;
            peers.Children.Clear();
            if(sync.Pairing.Corrupt){status.Text="设备授权文件无法读取，请保留数据，或明确重置设备授权。";peers.Children.Add(Button("重置设备授权",()=>{if(MessageBox.Show(Window.GetWindow(this),"仅清空设备配对。剪贴板历史会保留，设备需要重新核对识别码。","重置授权",MessageBoxButton.OKCancel)==MessageBoxResult.OK){try{sync.Pairing.Clear();}catch{pairingStatus.Text="重置设备授权未完成。";}Refresh();}}));}
            var keys=sync.Pairing.Keys();
            if(keys.Length==0)peers.Children.Add(Hint("尚未配对手机，从下方添加设备。"));
            foreach(string relation in keys){
                state.Peers.TryGetValue(relation,out var peer);var panel=new DockPanel{LastChildFill=true};var labels=new StackPanel();var trusted=sync.Pairing.Describe(relation);
                string label=peer?.Device?.Name??trusted?.Name??"未命名设备";
                var remove=Button("⋯",()=>{});remove.ToolTip="设备选项";remove.Margin=new(8,0,0,0);DockPanel.SetDock(remove,Dock.Right);panel.Children.Add(remove);var menu=new ContextMenu();var forget=new MenuItem{Header="移除配对"};forget.Click+=(_,_)=>{if(MessageBox.Show(Window.GetWindow(this),"移除后需要重新核对识别码。","移除设备",MessageBoxButton.OKCancel)==MessageBoxResult.OK){try{sync.Forget(relation);}catch{pairingStatus.Text="授权未移除，请检查存储。";}Refresh();}};menu.Items.Add(forget);remove.Click+=(_,_)=>{menu.PlacementTarget=remove;menu.IsOpen=true;};
                var option=new Wpf.Ui.Controls.ToggleSwitch{IsChecked=peer?.Enabled??true,OnContent="",OffContent="",IsEnabled=peer!=null,Opacity=1,VerticalAlignment=VerticalAlignment.Center,Margin=new(12,0,0,0)};System.Windows.Automation.AutomationProperties.SetName(option,"与 "+label+" 同步");option.Click+=(_,_)=>{try{sync.SetPeerEnabled(relation,option.IsChecked==true);}catch{pairingStatus.Text="设备开关未保存。";}Refresh();};DockPanel.SetDock(option,Dock.Right);panel.Children.Add(option);
                labels.Children.Add(new TextBlock{Text=label,FontSize=14,Margin=new(0,1,0,3)});
                string? transport=sync.ConnectionTransport(relation);
                string connection=!state.Enabled?"共享已关闭，配对已保留":peer?.Enabled==false?"此设备已暂停":transport=="lan"?"局域网已连接":transport=="bluetooth"?"蓝牙短连接中":!sync.Automatic?"已授权，自动连接已关闭":"已授权，等待自动连接";
                labels.Children.Add(new TextBlock{Text=connection,FontSize=12,Foreground=(System.Windows.Media.Brush)FindResource("FloatClip.Muted"),TextWrapping=TextWrapping.Wrap});
                if(state.Enabled&&peer?.Enabled!=false){
                    if(!state.Send||!state.Receive||!state.Apply)labels.Children.Add(Hint("部分同步选项已关闭"));
                    if(peer?.Pending!=null)labels.Children.Add(Hint("等待确认送达"));
                }
                panel.Children.Add(labels);peers.Children.Add(Card(panel));
            }
        }finally{refreshing=false;}
    }
}
