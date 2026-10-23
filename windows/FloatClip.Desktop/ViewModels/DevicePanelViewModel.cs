using System;
using System.Collections.ObjectModel;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using FloatClip.Desktop.Services;
using FloatClip.Share;

namespace FloatClip.Desktop.ViewModels;
public sealed class DevicePeerViewModel:ObservableObject
{
    bool enabled,canConfigure,updating;string name="",status="";readonly Action<DevicePeerViewModel,bool> configure;
    public string Key{get;}
    public string Name{get=>name;private set=>SetProperty(ref name,value);}
    public string Status{get=>status;private set=>SetProperty(ref status,value);}
    public bool CanConfigure{get=>canConfigure;private set=>SetProperty(ref canConfigure,value);}
    public bool Enabled{get=>enabled;set{if(SetProperty(ref enabled,value)&&!updating)configure(this,value);}}
    public IRelayCommand ForgetCommand{get;}
    public DevicePeerViewModel(string key,Action<DevicePeerViewModel,bool> configure,Action<DevicePeerViewModel> forget){Key=key;this.configure=configure;ForgetCommand=new RelayCommand(()=>forget(this));}
    public void Update(DevicePeerState state){updating=true;try{Name=state.Name;Status=state.Status;CanConfigure=state.CanConfigure;Enabled=state.Enabled;}finally{updating=false;}}
}
public sealed class DevicePanelViewModel:ObservableObject,IDisposable
{
    readonly IClipboardDeviceService service;readonly Action<Action> dispatch;readonly Func<string,string,bool> confirm;readonly CancellationTokenSource lifetime=new();
    CancellationTokenSource? operation;bool enabled,send,receive,apply,automatic,images,updating,busy,corrupt,closed;int connectionMode,queued;string status="",pairingStatus="";
    LanNetwork? selectedNetwork;
    public ObservableCollection<DevicePeerViewModel> Peers{get;}=new();
    public ObservableCollection<LanNetwork> Networks{get;}=new();
    public ObservableCollection<NearbyDevice> NearbyDevices{get;}=new();
    public bool Enabled{get=>enabled;set{if(SetProperty(ref enabled,value))Configure();}}
    public bool Send{get=>send;set{if(SetProperty(ref send,value))Configure();}}
    public bool Receive{get=>receive;set{if(SetProperty(ref receive,value))Configure();}}
    public bool Apply{get=>apply;set{if(SetProperty(ref apply,value))Configure();}}
    public bool Automatic{get=>automatic;set{if(SetProperty(ref automatic,value))Configure();}}
    public bool Images{get=>images;set{if(SetProperty(ref images,value)&&!updating)Run(()=>service.SetImageSharing(value));}}
    public bool Busy{get=>busy;private set{if(SetProperty(ref busy,value)){OnPropertyChanged(nameof(CanConnect));CancelCommand.NotifyCanExecuteChanged();}}}
    public bool CanConnect=>!Busy;
    public bool Corrupt{get=>corrupt;private set=>SetProperty(ref corrupt,value);}
    public string Status{get=>status;private set=>SetProperty(ref status,value);}
    public string PairingStatus{get=>pairingStatus;private set=>SetProperty(ref pairingStatus,value);}
    public string[] ConnectionModes{get;}={"选择连接方式","蓝牙","局域网 / Wi-Fi"};
    public int ConnectionMode{get=>connectionMode;set{if(SetProperty(ref connectionMode,value)){OnPropertyChanged(nameof(IsBluetooth));OnPropertyChanged(nameof(IsLan));if(IsLan&&Networks.Count==0)RefreshNetworks();}}}
    public bool IsBluetooth=>ConnectionMode==1;
    public bool IsLan=>ConnectionMode==2;
    public LanNetwork? SelectedNetwork{get=>selectedNetwork;set=>SetProperty(ref selectedNetwork,value);}
    public IRelayCommand ReconnectCommand{get;}
    public IRelayCommand RefreshNetworksCommand{get;}
    public IRelayCommand CancelCommand{get;}
    public IRelayCommand ResetCommand{get;}
    public IAsyncRelayCommand PairBluetoothCommand{get;}
    public IAsyncRelayCommand DiscoverCommand{get;}
    public IAsyncRelayCommand<NearbyDevice> PairLanCommand{get;}
    public DevicePanelViewModel(IClipboardDeviceService service,Action<Action> dispatch,Func<string,string,bool> confirm)
    {
        this.service=service;this.dispatch=dispatch;this.confirm=confirm;
        ReconnectCommand=new RelayCommand(()=>Run(()=>service.Reconnect()));RefreshNetworksCommand=new RelayCommand(RefreshNetworks);
        CancelCommand=new RelayCommand(()=>operation?.Cancel(),()=>Busy);
        ResetCommand=new RelayCommand(()=>{if(confirm("重置设备授权","剪贴板历史会保留，设备需要重新核对识别码。"))Run(()=>service.ResetPairing());});
        PairBluetoothCommand=new AsyncRelayCommand(()=>Connect(token=>service.PairBluetooth(code=>confirm("首次蓝牙配对","核对手机显示的识别码：\n\n"+code+"\n\n两端一致后才允许连接，并在手机上确认。"),token),"正在查找系统已配对的蓝牙设备…"));
        DiscoverCommand=new AsyncRelayCommand(()=>Connect(async token=>{if(SelectedNetwork==null)throw new IOException("请先选择 Wi-Fi 或热点对应的网络。");NearbyDevices.Clear();foreach(var item in await service.Discover(SelectedNetwork,token))NearbyDevices.Add(item);PairingStatus=NearbyDevices.Count==0?"未发现手机，请打开手机的添加设备页面后重试。":"选择下方手机并核对两端识别码。";},"正在查找手机…",false));
        PairLanCommand=new AsyncRelayCommand<NearbyDevice>(device=>device==null?Task.CompletedTask:Connect(token=>service.PairLan(device,code=>confirm("首次局域网配对","核对两端识别码：\n\n"+code),token),"正在连接手机…"));
        service.Changed+=Changed;Refresh();
    }
    void Configure(){if(updating||closed)return;Run(()=>{if(!Enabled)operation?.Cancel();service.Configure(Enabled,Send,Receive,Apply,Automatic);});}
    public void ReportPlatformFailure(string message)=>PairingStatus=message;
    void Run(Action action){try{action();}catch(Exception error){PairingStatus=error is IOException?error.Message:"操作未完成，请检查本机配置。";}Refresh();}
    void Changed(){if(closed||Interlocked.Exchange(ref queued,1)!=0)return;dispatch(()=>{Interlocked.Exchange(ref queued,0);if(!closed)Refresh();});}
    internal void Refresh()
    {
        if(closed)return;updating=true;
        try
        {
            var state=service.Read();Enabled=state.Enabled;Send=state.Send;Receive=state.Receive;Apply=state.Apply;Automatic=state.Automatic;Images=state.Images;Corrupt=state.Corrupt;Status=state.Status;
            foreach(var old in Peers.Where(p=>!state.Peers.Any(s=>s.Key==p.Key)).ToArray())Peers.Remove(old);
            foreach(var peer in state.Peers)
            {
                var row=Peers.FirstOrDefault(p=>p.Key==peer.Key);
                if(row==null){row=new DevicePeerViewModel(peer.Key,(p,on)=>Run(()=>service.SetPeerEnabled(p.Key,on)),p=>{if(confirm("移除设备","移除“"+p.Name+"”的配对后，需要重新核对识别码。"))Run(()=>service.Forget(p.Key));});Peers.Add(row);}
                row.Update(peer);
            }
        }
        finally{updating=false;}
    }
    void RefreshNetworks(){if(Busy)return;try{var id=SelectedNetwork?.AdapterId;var found=service.Networks();Networks.Clear();foreach(var item in found)Networks.Add(item);SelectedNetwork=Networks.FirstOrDefault(n=>n.AdapterId==id)??Networks.FirstOrDefault();}catch{PairingStatus="暂时无法读取网络，请连接 Wi-Fi 后刷新。";}}
    async Task Connect(Func<CancellationToken,Task> action,string message,bool completed=true)
    {
        if(Busy||closed)return;if(!Enabled){PairingStatus="请先开启共享剪贴板。";return;}
        using var current=CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token);operation=current;Busy=true;PairingStatus=message;
        try{await action(current.Token);if(completed&&!closed)PairingStatus="手机已授权，连接状态见上方。";}
        catch(OperationCanceledException){if(!closed)PairingStatus="已取消添加设备。";}
        catch(Exception error){if(!closed)PairingStatus=error is IOException?error.Message:"连接未完成，请检查手机的添加设备页面后重试。";}
        finally{operation=null;Busy=false;if(!closed)Refresh();}
    }
    public void Dispose(){if(closed)return;closed=true;lifetime.Cancel();service.Changed-=Changed;service.Dispose();}
}
