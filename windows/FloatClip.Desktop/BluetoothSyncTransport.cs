using System;
using System.Collections.Concurrent;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Threading;
using System.Threading.Tasks;
using FloatClip.Share;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Devices.Enumeration;
using Windows.Networking.Sockets;

namespace FloatClip.Desktop;

/**
 * Bluetooth Classic burst transport. System Bluetooth is only used to locate and
 * open RFCOMM; FloatClip's durable relation token independently authenticates the
 * peer and encrypts ShareWire/FCP3 with TransportCrypto.
 */
public sealed class BluetoothSyncTransport:IDisposable
{
    static readonly Guid ServiceUuid=Guid.Parse("e06f95e4-fc4d-4e92-b67a-1eb7f9f0a56f");
    static readonly Guid PairServiceUuid=Guid.Parse("e06f95e4-fc4d-4e92-b67a-1eb7f9f0a570");
    static readonly RfcommServiceId ServiceId=RfcommServiceId.FromUuid(ServiceUuid);
    static readonly RfcommServiceId PairServiceId=RfcommServiceId.FromUuid(PairServiceUuid);
    readonly PairStore pairs;
    readonly string cache;
    readonly Func<string,bool> canAttach;
    readonly Action<string,Session,string> attach;
    readonly Action<string> status;
    readonly CancellationTokenSource lifetime=new();
    readonly SemaphoreSlim connectGate=new(1,1);
    readonly ConcurrentDictionary<StreamSocket,byte> sockets=new();
    RfcommServiceProvider? provider;
    StreamSocketListener? listener;
    int started,disposed;

    public BluetoothSyncTransport(PairStore pairs,string cache,Func<string,bool> canAttach,Action<string,Session,string> attach,Action<string> status)
    {
        this.pairs=pairs;this.cache=cache;this.canAttach=canAttach;this.attach=attach;this.status=status;
    }

    public void Start()
    {
        if(Interlocked.Exchange(ref started,1)!=0||disposed!=0)return;
        _=Task.Run(StartListener);
    }

    async Task StartListener()
    {
        while(disposed==0)
        {
            try
            {
                provider=await RfcommServiceProvider.CreateAsync(ServiceId);
                if(disposed!=0)return;
                var next=new StreamSocketListener();
                next.ConnectionReceived+=ConnectionReceived;
                await next.BindServiceNameAsync(provider.ServiceId.AsString(),SocketProtectionLevel.BluetoothEncryptionWithAuthentication);
                if(disposed!=0){next.Dispose();return;}
                listener=next;provider.StartAdvertising(next,false);return;
            }
            catch(Exception e)
            {
                try{provider?.StopAdvertising();}catch{}provider=null;
                listener?.Dispose();listener=null;
                if(disposed!=0)return;status("蓝牙短连接监听等待蓝牙可用 · "+Friendly(e));
                try{await Task.Delay(TimeSpan.FromSeconds(5),lifetime.Token);}catch(OperationCanceledException){return;}
            }
        }
    }

    async void ConnectionReceived(StreamSocketListener sender,StreamSocketListenerConnectionReceivedEventArgs args)
    {
        var socket=args.Socket;if(disposed!=0){socket.Dispose();return;}sockets.TryAdd(socket,0);
        bool handedOff=false;
        try
        {
            using var deadline=new CancellationTokenSource(TimeSpan.FromSeconds(15));
            using var abort=deadline.Token.Register(()=>socket.Dispose());
            var raw=new DuplexStream(socket.InputStream.AsStreamForRead(),socket.OutputStream.AsStreamForWrite(),socket);
            var secure=await Task.Run(()=>TransportCrypto.Accept(raw,key=>canAttach(key)?pairs.GetByKey(key):null),deadline.Token);
            if(!canAttach(secure.RelationKey))return;
            ulong? address=await AddressOf(socket);
            if(address is >0)pairs.RememberBluetooth(secure.RelationKey,address.Value);
            var session=new Session(secure.Stream,()=>socket.Dispose(),cache,true);
            attach(secure.RelationKey,session,"bluetooth");
            handedOff=true;sockets.TryRemove(socket,out _);
            _=CloseAfter(session);
        }
        catch(Exception e){if(disposed==0&&e is not OperationCanceledException)status("蓝牙连接未通过验证 · "+Friendly(e));}
        finally{if(!handedOff){sockets.TryRemove(socket,out _);socket.Dispose();}}
    }

    public async Task<string> PairAsync(Func<string,bool> verify,CancellationToken cancellation)
    {
        if(disposed!=0)throw new ObjectDisposedException(nameof(BluetoothSyncTransport));
        Start();
        using var linked=CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token,cancellation);
        var candidates=await PairingServices(linked.Token);
        try
        {
            foreach(var candidate in candidates)
            {
                linked.Token.ThrowIfCancellationRequested();
                var service=candidate.Service;var device=candidate.Device;string? relation=null;
                try
                {
                    var socket=new StreamSocket();sockets.TryAdd(socket,0);
                    try
                    {
                        using var deadline=CancellationTokenSource.CreateLinkedTokenSource(linked.Token);deadline.CancelAfter(TimeSpan.FromSeconds(75));
                        await socket.ConnectAsync(service.ConnectionHostName,service.ConnectionServiceName,SocketProtectionLevel.BluetoothEncryptionWithAuthentication).AsTask(deadline.Token);
                        using var abort=deadline.Token.Register(()=>socket.Dispose());
                        var raw=new DuplexStream(socket.InputStream.AsStreamForRead(),socket.OutputStream.AsStreamForWrite(),socket);
                        await Task.Run(()=>TransportPairing.Connect(raw,verify,token=>relation=pairs.PutBluetooth(token,device.BluetoothAddress),()=>{if(relation!=null)pairs.RemoveKey(relation);}),deadline.Token);
                        if(relation==null)throw new IOException("蓝牙授权未保存");
                        return device.Name+"|"+relation;
                    }
                    finally{sockets.TryRemove(socket,out _);socket.Dispose();}
                }
                catch(OperationCanceledException){throw;}
                catch{/* A stale SDP record or unavailable paired device is skipped. */}
            }
        }
        finally
        {
            foreach(var candidate in candidates){candidate.Service.Dispose();candidate.Device.Dispose();}
        }
        throw new IOException("未发现处于“添加设备”窗口的已配对 FloatClip 蓝牙设备");
    }

    sealed record PairCandidate(BluetoothDevice Device,RfcommDeviceService Service);

    async Task<PairCandidate[]> PairingServices(CancellationToken cancellation)
    {
        var direct=new System.Collections.Generic.List<PairCandidate>();
        string selector=RfcommDeviceService.GetDeviceSelector(PairServiceId);
        var found=await DeviceInformation.FindAllAsync(selector).AsTask(cancellation);
        foreach(var info in found)
        {
            RfcommDeviceService? service=null;BluetoothDevice? device=null;
            try
            {
                service=await RfcommDeviceService.FromIdAsync(info.Id);
                if(service==null)continue;
                device=await BluetoothDevice.FromHostNameAsync(service.ConnectionHostName);
                if(device==null||!device.DeviceInformation.Pairing.IsPaired)continue;
                direct.Add(new(device,service));device=null;service=null;
            }
            catch{}
            finally{device?.Dispose();service?.Dispose();}
        }
        if(direct.Count>0)return direct.ToArray();

        // Windows often omits Android's dynamically registered RFCOMM service from the
        // global service selector. Probe OS-paired devices concurrently instead of
        // serially waiting on headphones/controllers one by one.
        var devices=await PairedDevices();
        var probes=devices.Select(async device=>{
            try
            {
                using var deadline=CancellationTokenSource.CreateLinkedTokenSource(cancellation);
                deadline.CancelAfter(TimeSpan.FromSeconds(7));
                var result=await device.GetRfcommServicesForIdAsync(PairServiceId,BluetoothCacheMode.Uncached).AsTask(deadline.Token);
                if(result.Error!=BluetoothError.Success||result.Services.Count==0){device.Dispose();return null;}
                var service=result.Services[0];
                for(int i=1;i<result.Services.Count;i++)result.Services[i].Dispose();
                return new PairCandidate(device,service);
            }
            catch{device.Dispose();return null;}
        }).ToArray();
        var discovered=await Task.WhenAll(probes);
        return discovered.Where(x=>x!=null).Cast<PairCandidate>().ToArray();
    }

    public void Nudge()
    {
        if(disposed!=0)return;Start();_=Task.Run(ConnectBurst);
    }

    async Task ConnectBurst()
    {
        if(!await connectGate.WaitAsync(0,lifetime.Token))return;
        try
        {
            foreach(string key in pairs.Keys())
            {
                if(disposed!=0||!canAttach(key))continue;
                var trusted=pairs.Describe(key);if(trusted==null)continue;
                if(trusted.BluetoothAddress is ulong address && address!=0)
                {
                    if(await TryAddress(key,address))return;
                }
            }
            // Bootstrap only from OS-paired devices. No permanent inquiry loop is kept.
            foreach(var candidate in await PairedDevices())
            {
                try
                {
                    if(disposed!=0)break;
                    foreach(string key in pairs.Keys())
                    {
                        if(!canAttach(key)||pairs.Describe(key)?.BluetoothAddress!=null)continue;
                        if(await TryDevice(key,candidate))
                        {
                            pairs.RememberBluetooth(key,candidate.BluetoothAddress);
                            return;
                        }
                    }
                }
                finally{candidate.Dispose();}
            }
        }
        catch(OperationCanceledException){}
        catch(Exception e){if(disposed==0)status("蓝牙短连接暂不可用 · "+Friendly(e));}
        finally{connectGate.Release();}
    }

    async Task<bool> TryAddress(string key,ulong address)
    {
        BluetoothDevice? device=null;
        try
        {
            device=await BluetoothDevice.FromBluetoothAddressAsync(address);
            if(device==null)return false;
            return await TryDevice(key,device);
        }
        catch{return false;}
        finally{device?.Dispose();}
    }

    async Task<bool> TryDevice(string key,BluetoothDevice device)
    {
        byte[] token=pairs.GetByKey(key);if(token.Length!=32||!canAttach(key))return false;
        RfcommDeviceServicesResult result=await device.GetRfcommServicesForIdAsync(ServiceId,BluetoothCacheMode.Uncached);
        if(result.Error!=BluetoothError.Success)return false;
        foreach(var service in result.Services)
        {
            try
            {
                if(!canAttach(key))return false;
                var socket=new StreamSocket();sockets.TryAdd(socket,0);bool handedOff=false;
                try
                {
                    using var deadline=CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token);deadline.CancelAfter(TimeSpan.FromSeconds(15));
                    await socket.ConnectAsync(service.ConnectionHostName,service.ConnectionServiceName,SocketProtectionLevel.BluetoothEncryptionWithAuthentication).AsTask(deadline.Token);
                    using var abort=deadline.Token.Register(()=>socket.Dispose());
                    var raw=new DuplexStream(socket.InputStream.AsStreamForRead(),socket.OutputStream.AsStreamForWrite(),socket);
                    var secure=await Task.Run(()=>TransportCrypto.Connect(raw,token),deadline.Token);
                    if(secure.RelationKey!=key||!canAttach(key))continue;
                    pairs.RememberBluetooth(key,device.BluetoothAddress);
                    var session=new Session(secure.Stream,()=>socket.Dispose(),cache,true);
                    attach(key,session,"bluetooth");handedOff=true;sockets.TryRemove(socket,out _);
                    _=CloseAfter(session);return true;
                }
                finally{if(!handedOff){sockets.TryRemove(socket,out _);socket.Dispose();}}
            }
            catch{}
            finally{service.Dispose();}
        }
        return false;
    }

    static async Task<BluetoothDevice[]> PairedDevices()
    {
        var found=await DeviceInformation.FindAllAsync(BluetoothDevice.GetDeviceSelectorFromPairingState(true));
        var devices=await Task.WhenAll(found.Select(async info=>await BluetoothDevice.FromIdAsync(info.Id)));
        return devices.Where(d=>d!=null).Cast<BluetoothDevice>().ToArray();
    }

    static async Task<ulong?> AddressOf(StreamSocket socket)
    {
        BluetoothDevice? device=null;
        try
        {
            device=await BluetoothDevice.FromHostNameAsync(socket.Information.RemoteAddress);
            return device?.BluetoothAddress;
        }
        catch{return null;}
        finally{device?.Dispose();}
    }

    static async Task CloseAfter(Session session)
    {
        try{await Task.Delay(TimeSpan.FromSeconds(12));}catch{}finally{session.Dispose();}
    }

    static string Friendly(Exception e)=>e is IOException?e.Message:e.GetType().Name;

    sealed class DuplexStream:Stream
    {
        readonly Stream input,output;readonly IDisposable owner;bool closed;
        public DuplexStream(Stream input,Stream output,IDisposable owner){this.input=input;this.output=output;this.owner=owner;}
        public override bool CanRead=>!closed;public override bool CanWrite=>!closed;public override bool CanSeek=>false;
        public override long Length=>throw new NotSupportedException();public override long Position{get=>throw new NotSupportedException();set=>throw new NotSupportedException();}
        public override int Read(byte[] buffer,int offset,int count)=>input.Read(buffer,offset,count);
        public override void Write(byte[] buffer,int offset,int count)=>output.Write(buffer,offset,count);
        public override void Flush()=>output.Flush();
        protected override void Dispose(bool disposing){if(disposing&&!closed){closed=true;try{input.Dispose();}catch{}try{output.Dispose();}catch{}owner.Dispose();}base.Dispose(disposing);}
        public override long Seek(long offset,SeekOrigin origin)=>throw new NotSupportedException();
        public override void SetLength(long value)=>throw new NotSupportedException();
    }

    public void Dispose()
    {
        if(Interlocked.Exchange(ref disposed,1)!=0)return;
        lifetime.Cancel();
        try{provider?.StopAdvertising();}catch{}
        if(listener!=null)listener.ConnectionReceived-=ConnectionReceived;
        listener?.Dispose();provider=null;listener=null;
        foreach(var socket in sockets.Keys)socket.Dispose();sockets.Clear();
        connectGate.Dispose();lifetime.Dispose();
    }
}
