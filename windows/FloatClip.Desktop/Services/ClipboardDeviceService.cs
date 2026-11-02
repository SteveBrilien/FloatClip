using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using FloatClip.Share;

namespace FloatClip.Desktop.Services;
public sealed record DevicePanelState(bool Enabled,bool Send,bool Receive,bool Apply,bool Automatic,bool Images,string Status,bool Corrupt,IReadOnlyList<DevicePeerState> Peers);
public interface IClipboardDeviceService:IDisposable
{
    event Action? Changed;
    DevicePanelState Read();
    void Configure(bool enabled,bool send,bool receive,bool apply,bool automatic);
    void SetImageSharing(bool enabled);
    void SetPeerEnabled(string key,bool enabled);
    void Forget(string key);
    void ResetPairing();
    void Reconnect();
    IReadOnlyList<LanNetwork> Networks();
    Task PairBluetooth(Func<string,bool> verify,CancellationToken token);
    Task<IReadOnlyList<NearbyDevice>> Discover(LanNetwork network,CancellationToken token);
    Task PairLan(NearbyDevice device,Func<string,bool> verify,CancellationToken token);
}
public sealed class ClipboardDeviceService:IClipboardDeviceService
{
    readonly NativeClipboardSync sync;
    public event Action? Changed;
    public ClipboardDeviceService(NativeClipboardSync sync){this.sync=sync;sync.Changed+=Notify;}
    void Notify()=>Changed?.Invoke();
    public DevicePanelState Read()
    {
        var state=sync.Snapshot();
        var relations=sync.Pairing.Keys().Select(key=>{
            state.Peers.TryGetValue(key,out var peer);var trusted=sync.Pairing.Describe(key);
            string? transport=sync.ConnectionTransport(key);
            string? name=DevicePeerGrouping.UsefulName(trusted?.Name)?trusted!.Name:peer?.Device?.Name;
            return new DeviceRelationState(key,trusted?.DeviceId??peer?.Device?.Id,name,peer?.Enabled??true,peer!=null,transport,peer?.Pending!=null);
        });
        var peers=DevicePeerGrouping.Group(relations,state.Enabled,sync.Automatic);
        return new(state.Enabled,state.Send,state.Receive,state.Apply,sync.Automatic,sync.ImageSharing,sync.Status,sync.Pairing.Corrupt,peers);
    }
    public void Configure(bool enabled,bool send,bool receive,bool apply,bool automatic)=>sync.Configure(enabled,send,receive,apply,automatic);
    public void SetImageSharing(bool enabled)=>sync.SetImageSharing(enabled);
    public void SetPeerEnabled(string key,bool enabled)=>sync.SetPeerEnabled(key,enabled);
    public void Forget(string key){sync.Forget(key);Notify();}
    public void ResetPairing(){sync.Pairing.Clear();Notify();}
    public void Reconnect()=>sync.ReconnectNow();
    public IReadOnlyList<LanNetwork> Networks()=>Nearby.Networks();
    public Task PairBluetooth(Func<string,bool> verify,CancellationToken token)=>sync.PairBluetoothAsync(verify,token);
    public async Task<IReadOnlyList<NearbyDevice>> Discover(LanNetwork network,CancellationToken token)=>await Nearby.Discover(network,token,true);
    public Task PairLan(NearbyDevice device,Func<string,bool> verify,CancellationToken token)=>sync.PairAsync(device,verify,token);
    public void Dispose()=>sync.Changed-=Notify;
}
