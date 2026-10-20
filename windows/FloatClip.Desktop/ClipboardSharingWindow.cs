using System;
using System.IO;
using System.Linq;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using FloatClip.Share;

namespace FloatClip.Desktop;

/** Settings surface only. Closing this window never owns or ends a trusted session. */
public sealed class ClipboardSharingWindow:Window
{
    readonly NativeClipboardSync sync;
    readonly StackPanel content=new(),peers=new(),nearby=new();
    readonly TextBlock status=new();
    readonly CheckBox master=new(),automatic=new(),send=new(),receive=new(),apply=new();
    readonly ComboBox networks=new();
    readonly CancellationTokenSource close=new();
    readonly Brush text,surface,tint,accent;
    bool refreshing,closed;
    public ClipboardSharingWindow(NativeClipboardSync sync,Brush background,Brush surface,Brush text,Brush muted,Brush tint,Brush accent)
    {
        this.sync=sync;this.text=text;this.surface=surface;this.tint=tint;this.accent=accent;
        Title="剪贴板共享";Width=560;Height=680;MinWidth=420;MinHeight=430;Background=background;Foreground=text;
        FontFamily=new FontFamily("Segoe UI, Microsoft YaHei UI");FontSize=15;WindowStartupLocation=WindowStartupLocation.CenterOwner;
        content.Margin=new(24);Content=new ScrollViewer{Content=content,VerticalScrollBarVisibility=ScrollBarVisibility.Auto};
        content.Children.Add(new TextBlock{Text="剪贴板共享",FontSize=23,Margin=new(0,0,0,14)});
        status.Foreground=muted;status.TextWrapping=TextWrapping.Wrap;status.Margin=new(0,0,0,16);content.Children.Add(status);
        var options=new StackPanel();
        void Option(CheckBox box,string label){box.Content=label;box.Foreground=text;box.MinHeight=48;box.VerticalContentAlignment=VerticalAlignment.Center;box.Click+=(_,_)=>Configure();options.Children.Add(box);}
        Option(master,"开启剪贴板共享");Option(automatic,"自动连接已配对设备");Option(send,"共享本机新复制的文字");Option(receive,"接收其他设备的文字");Option(apply,"接收后写入系统剪贴板");
        content.Children.Add(Card(options));
        content.Children.Add(peers);
        var pairing=new StackPanel();pairing.Children.Add(new TextBlock{Text="添加设备",FontSize=18,Margin=new(0,0,0,10)});
        pairing.Children.Add(new TextBlock{Text="在手机开启共享并允许添加设备。推荐直接用系统已配对的蓝牙完成首次授权；同一局域网也可作为备用。",Foreground=muted,TextWrapping=TextWrapping.Wrap,Margin=new(0,0,0,12)});
        pairing.Children.Add(Button("通过蓝牙添加（推荐）",async()=>{
            if(!sync.Snapshot().Enabled){status.Text="请先开启剪贴板共享";return;}
            status.Text="正在查找已配对的蓝牙设备…";
            try{
                await sync.PairBluetoothAsync(code=>Dispatcher.Invoke(()=>!closed&&MessageBox.Show(this,"请核对手机显示的识别码：\n\n"+code+"\n\n两端完全一致才允许。","首次蓝牙配对",MessageBoxButton.OKCancel,MessageBoxImage.Question)==MessageBoxResult.OK),close.Token);
                if(!closed){status.Text="蓝牙设备已授权，后续无需热点";Refresh();}
            }catch(OperationCanceledException){}catch(Exception e){if(!closed)status.Text=e is IOException?e.Message:"蓝牙配对未完成，请重试";}
        }));
        networks.ItemsSource=Nearby.Networks();networks.SelectedIndex=networks.Items.Count>0?0:-1;networks.Background=surface;networks.Foreground=text;networks.MinHeight=44;pairing.Children.Add(networks);
        var discover=Button("通过局域网查找",async()=>{
            if(!sync.Snapshot().Enabled){status.Text="请先开启剪贴板共享";return;}
            if(networks.SelectedItem is not LanNetwork network){status.Text="请连接 Wi-Fi 或热点";return;}
            nearby.Children.Clear();status.Text="正在查找…";
            try{var found=await Nearby.Discover(network,close.Token,true);if(closed)return;
                foreach(var device in found)nearby.Children.Add(Button(device.Name,async()=>{
                    try{await sync.PairAsync(device,code=>Dispatcher.Invoke(()=>!closed&&MessageBox.Show(this,"请核对手机显示的识别码：\n\n"+code+"\n\n两端一致后，在手机上允许连接。","首次配对",MessageBoxButton.OKCancel)==MessageBoxResult.OK),close.Token);Refresh();}
                    catch(OperationCanceledException){}catch(Exception e){if(!closed)status.Text=e is IOException?e.Message:"配对未完成，请重试";}
                }));
                status.Text=found.Count==0?"未发现设备，请检查手机的添加设备开关和网络是否互通。":"选择设备并核对识别码";
            }catch(OperationCanceledException){}catch{if(!closed)status.Text="查找失败，请检查所选网络";}
        });pairing.Children.Add(discover);pairing.Children.Add(nearby);content.Children.Add(Card(pairing));
        sync.Changed+=Changed;Closing+=(_,_)=>{closed=true;close.Cancel();sync.Changed-=Changed;};Refresh();
    }
    Border Card(UIElement child)=>new(){Child=child,Padding=new(16),Margin=new(0,0,0,16),CornerRadius=new(16),Background=surface};
    Button Button(string label,Action action){var b=new Button{Content=label,Background=tint,Foreground=accent,BorderThickness=new(0),MinHeight=44,Padding=new(14,8,14,8),Margin=new(0,10,0,0)};b.Click+=(_,_)=>action();return b;}
    void Configure(){if(refreshing)return;try{sync.Configure(master.IsChecked==true,send.IsChecked==true,receive.IsChecked==true,apply.IsChecked==true,automatic.IsChecked==true);}catch(Exception e){status.Text=e is IOException?e.Message:"共享设置未保存";}Refresh();}
    void Changed(){if(!closed)Dispatcher.BeginInvoke(new Action(()=>{if(!closed)Refresh();}));}
    void Refresh()
    {
        refreshing=true;
        try{
            var state=sync.Snapshot();master.IsChecked=state.Enabled;send.IsChecked=state.Send;receive.IsChecked=state.Receive;apply.IsChecked=state.Apply;automatic.IsChecked=sync.Automatic;status.Text=sync.Status;
            peers.Children.Clear();
            if(sync.Pairing.Corrupt){status.Text="设备授权文件无法读取，请保留数据，或明确重置设备授权。";peers.Children.Add(Button("重置设备授权",()=>{if(MessageBox.Show(this,"仅清空设备配对。剪贴板历史会保留，设备需要重新核对识别码。","重置授权",MessageBoxButton.OKCancel)==MessageBoxResult.OK){try{sync.Pairing.Clear();}catch{status.Text="重置授权未完成";}Refresh();}}));}
            foreach(string relation in sync.Pairing.Keys()){
                state.Peers.TryGetValue(relation,out var peer);var panel=new StackPanel();
                var trusted=sync.Pairing.Describe(relation);
                string label=peer?.Device?.Name??trusted?.Name??("已配对设备 · "+relation[..8]);
                if(peer!=null){var option=new CheckBox{Content=label,IsChecked=peer.Enabled,Foreground=text,MinHeight=44,VerticalContentAlignment=VerticalAlignment.Center};
                    option.Click+=(_,_)=>{try{sync.SetPeerEnabled(relation,option.IsChecked==true);}catch{status.Text="设备开关未保存";}Refresh();};panel.Children.Add(option);}
                else panel.Children.Add(new TextBlock{Text=label,Foreground=text,Margin=new(0,6,0,6)});
                panel.Children.Add(Button("移除配对",()=>{if(MessageBox.Show(this,"移除后需要重新核对识别码。","移除设备",MessageBoxButton.OKCancel)==MessageBoxResult.OK){try{sync.Forget(relation);}catch{status.Text="授权未移除，请检查存储";}Refresh();}}));peers.Children.Add(Card(panel));
            }
        }finally{refreshing=false;}
    }
}
