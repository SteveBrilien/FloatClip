using System;
using System.Collections.Concurrent;
using System.IO;
using System.Linq;
using System.Net.NetworkInformation;
using System.Security.Cryptography;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using FloatClip.Share;

namespace FloatClip.Desktop;

/** Owns trusted LAN sessions independently of any device/settings window. No clipboard polling. */
public sealed class NativeClipboardSync: IDisposable
{
    sealed class PersistenceFailure:IOException {public PersistenceFailure(Exception problem):base("本机共享数据未保存",problem){}}
    sealed class Options
    {
        public bool Automatic{get;set;}=true;
        public string? SelectedAdapter{get;set;}
    }
    readonly ClipboardVault vault;
    readonly string root,cache;
    readonly Action<SyncEntry,long> apply;
    readonly ClipboardSyncEngine engine;
    readonly PairStore pairs;
    readonly SemaphoreSlim wake=new(0,1);
    readonly ConcurrentDictionary<string,Link> links=new();
    readonly ConcurrentDictionary<string,byte> connecting=new(),blocked=new();
    readonly CancellationTokenSource lifetime=new();
    readonly object optionsGate=new(),operationGate=new();
    CancellationTokenSource operations=new();
    CancellationToken OperationToken{get{lock(operationGate)return operations.Token;}}
    void CancelOperations(){lock(operationGate){operations.Cancel();operations=new();}}
    Options options;
    volatile bool enabled,disposed;
    int started;
    public string Status{get;private set;}="共享已关闭";
    public event Action? Changed;
    public event Action? HistoryChanged;
    public event Action<Session>? SessionOpened;
    public int ConnectedCount=>links.Count;
    public PairStore Pairing=>pairs;
    public Session? FirstSession=>links.Values.FirstOrDefault()?.Session;

    public NativeClipboardSync(ClipboardVault vault,Action<SyncEntry,long> apply)
    {
        this.vault=vault;this.apply=apply;
        root=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"FloatClip","Host");cache=Path.Combine(root,"SyncCache");Directory.CreateDirectory(cache);
        string pairedRoot=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"FloatClip","Share");
        pairs=new PairStore(pairedRoot);
        if(File.Exists(Path.Combine(root,"sync-options.json"))&&new FileInfo(Path.Combine(root,"sync-options.json")).Length>16384)throw new IOException("共享设置过大");
        options=File.Exists(Path.Combine(root,"sync-options.json"))?JsonSerializer.Deserialize<Options>(File.ReadAllText(Path.Combine(root,"sync-options.json")))??throw new IOException("共享设置损坏"):new();
        var device=vault.Device();var local=new SyncDevice(Guid.Parse(device["id"]!.GetValue<string>()),device["name"]!.GetValue<string>(),"windows");
        engine=new ClipboardSyncEngine(local,LoadState,SaveSyncState,Send,PersistSharedEntry,ApplyReceived);
        if(!pairs.Corrupt)foreach(string key in engine.Snapshot().Peers.Keys.ToArray())if(pairs.Get(key).Length!=32)engine.Forget(key);
        enabled=engine.Snapshot().Enabled;
        pairs.Changed+=AuthorizationChanged;
        NetworkChange.NetworkAddressChanged+=NetworkChanged;
    }
    void SaveSyncState(byte[] bytes){try{SaveState(bytes);}catch(Exception e)when(e is IOException or UnauthorizedAccessException or CryptographicException){throw new PersistenceFailure(e);}}
    void PersistSharedEntry(SyncEntry entry){try{vault.ReceiveShared(entry);}catch(Exception e)when(e is IOException or UnauthorizedAccessException or CryptographicException){throw new PersistenceFailure(e);}try{HistoryChanged?.Invoke();}catch{/* A closed view cannot invalidate durable history. */}}
    void ApplyReceived(SyncEntry entry) {apply(entry,engine.ApplicationGrant);}
    byte[]? LoadState()
    {
        string path=Path.Combine(root,"clipboard-sync.v1.bin");if(!File.Exists(path))return null;
        if(new FileInfo(path).Length>1000000)throw new IOException("同步状态过大");
        return ProtectedData.Unprotect(File.ReadAllBytes(path),null,DataProtectionScope.CurrentUser);
    }
    void SaveState(byte[] plain)
    {
        string path=Path.Combine(root,"clipboard-sync.v1.bin"),temporary=path+".tmp";
        byte[] cipher=ProtectedData.Protect(plain,null,DataProtectionScope.CurrentUser);
        try{using(var output=new FileStream(temporary,FileMode.Create,FileAccess.Write,FileShare.None)){output.Write(cipher);output.Flush(true);}File.Move(temporary,path,true);}
        finally{if(File.Exists(temporary))File.Delete(temporary);}
    }
    void SaveOptions()
    {
        string path=Path.Combine(root,"sync-options.json"),temporary=path+".tmp";
        File.WriteAllText(temporary,JsonSerializer.Serialize(options));File.Move(temporary,path,true);
    }
    public ClipboardSyncEngine.State Snapshot(){var s=engine.Snapshot();s.Enabled=enabled;return s;}
    public bool Automatic{get{lock(optionsGate)return options.Automatic;}}
    public bool MayApply(SyncEntry entry,long grant)=>enabled&&!disposed&&engine.MayApply(entry,grant);
    public void Configure(bool on,bool sending,bool receiving,bool applying,bool automatic)
    {
        if(disposed)throw new ObjectDisposedException(nameof(NativeClipboardSync));
        CancelOperations();
        if(!on){enabled=false;foreach(var row in links.ToArray())Remove(row.Key,row.Value);}
        try{engine.Configure(on,sending,receiving,applying);}
        catch{enabled=false;foreach(var row in links.ToArray())Remove(row.Key,row.Value);throw;}
        lock(optionsGate){options.Automatic=automatic;SaveOptions();}
        enabled=on;Start();Signal();SetStatus(on?"等待已授权设备自动连接":"共享已暂停");
    }
    public void SetPeerEnabled(string pin,bool on){engine.SetPeerEnabled(pin,on);Changed?.Invoke();}
    public void Forget(string pin){pairs.Remove(pin);engine.Forget(pin);}
    public void Start()
    {
        if(Interlocked.Exchange(ref started,1)!=0)return;
        _=Task.Run(ReconnectLoop);
        _=Task.Run(RetryLoop);
    }
    public void Copied(string value)
    {
        if(!enabled||disposed)return;
        try{engine.Copied(value,DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());}
        catch{SetStatus("该条目未同步，请检查长度或本机存储");}
    }
    void Send(string key,Frame frame){if(enabled&&!disposed&&pairs.Get(key).Length==32&&links.TryGetValue(key,out var link))link.Queue(frame);}
    void SetStatus(string status){Status=status;Changed?.Invoke();}
    void NetworkChanged(object? sender,EventArgs args)=>Signal();
    void Signal(){try{wake.Release();}catch(SemaphoreFullException){}catch(ObjectDisposedException){}}
    void AuthorizationChanged()
    {
        foreach(var key in engine.Snapshot().Peers.Keys.ToArray())if(pairs.Get(key).Length!=32){if(links.TryGetValue(key,out var link))Remove(key,link);engine.Forget(key);}
        blocked.Clear();Signal();Changed?.Invoke();
    }
    public async Task PairAsync(NearbyDevice device,Func<string,bool> verify,CancellationToken cancellation)
    {
        if(!enabled)throw new IOException("请先开启剪贴板共享");
        lock(optionsGate){options.SelectedAdapter=device.Network.AdapterId;SaveOptions();}
        using var cancel=CancellationTokenSource.CreateLinkedTokenSource(cancellation,OperationToken);
        await Connect(device,verify,cancel.Token);
    }
    async Task Connect(NearbyDevice device,Func<string,bool> verify,CancellationToken cancellation)
    {
        string key=device.Pin.ToLowerInvariant();if(!connecting.TryAdd(key,0))return;
        Link? added=null;
        try
        {
            if(links.ContainsKey(key))return;
            using var cancel=CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token,cancellation);
            var session=await Task.Run(()=>Nearby.Connect(device,cache,pairs,verify,cancel.Token,persistent:true),cancel.Token);
            if(!enabled||disposed||pairs.Get(key).Length!=32){session.Dispose();return;}
            var link=new Link(this,key,session);
            if(!links.TryAdd(key,link)){link.Dispose();return;}added=link;
            session.Sync=frame=>
            {
                if(!enabled||!links.TryGetValue(key,out var active)||!ReferenceEquals(active,link)||pairs.Get(key).Length!=32)return;
                try{engine.Accept(key,frame,DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());}
                catch(PersistenceFailure){Remove(key,link);SetStatus("本机保存失败，内容尚未确认送达；存储恢复后将重试连接");Signal();}
                catch{blocked[key]=0;Remove(key,link);SetStatus("设备身份或同步校验失败，请重新核对授权");}
            };
            session.Closed=()=>{Remove(key,link);Signal();};
            session.Offered=offer=>{try{session.Reject(offer.Id);}catch{session.Dispose();}};
            session.Failure=_=>SetStatus("连接中断，等待自动重连");
            engine.Connected(key);try{SessionOpened?.Invoke(session);}catch{}session.Start();SetStatus(links.Count+" 台设备已连接 · 加密共享");
        }
        catch(PairingAuthorizationException){if(added!=null)Remove(key,added);blocked[key]=0;SetStatus("设备授权已失效，需要重新核对");throw;}
        catch{if(added!=null)Remove(key,added);throw;}
        finally{connecting.TryRemove(key,out _);}
    }
    void Remove(string key,Link expected)
    {
        if(((System.Collections.Generic.ICollection<System.Collections.Generic.KeyValuePair<string,Link>>)links).Remove(new(key,expected)))
        {engine.Disconnected(key);expected.Dispose();Changed?.Invoke();}
    }
    async Task ReconnectLoop()
    {
        int failures=0;
        try
        {
            while(!lifetime.IsCancellationRequested)
            {
                int delay=300000;
                if(enabled&&Automatic)
                {
                    using var attempt=CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token,OperationToken);
                    string[] pending=pairs.Pins().Where(pin=>!links.ContainsKey(pin)&&!blocked.ContainsKey(pin)).ToArray();
                    if(pending.Length>0)
                    {
                        bool found=false;string? selected;lock(optionsGate)selected=options.SelectedAdapter;
                        // Automatic discovery is limited to Wi-Fi or the physical adapter explicitly chosen during pairing.
                        foreach(var network in Nearby.Networks().Where(n=>n.Wireless||selected!=null&&n.AdapterId==selected))
                        {
                            if(!enabled||!Automatic)break;
                            try
                            {
                                foreach(var device in await Nearby.Discover(network,attempt.Token,true))
                                {
                                    if(!enabled||!Automatic)break;
                                    if(!pending.Contains(device.Pin)||pairs.Get(device.Pin).Length!=32)continue;
                                    try{await Connect(device,_=>false,attempt.Token);found=true;}catch(IOException){}
                                }
                            }
                            catch(OperationCanceledException)when(lifetime.IsCancellationRequested){throw;}
                            catch{/* Physical network may have disappeared. */}
                        }
                        failures=found?0:Math.Min(failures+1,5);
                        delay=Math.Min(30000,1000*(1<<failures))+Random.Shared.Next(0,800);
                    }
                }
                await wake.WaitAsync(TimeSpan.FromMilliseconds(delay),lifetime.Token);
            }
        }
        catch(OperationCanceledException){}
    }
    async Task RetryLoop()
    {
        using var timer=new PeriodicTimer(TimeSpan.FromSeconds(10));
        try{while(await timer.WaitForNextTickAsync(lifetime.Token))if(enabled)foreach(string key in links.Keys)try{engine.Retry(key,DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());}catch{SetStatus("共享状态未保存，请检查本机存储");}}
        catch(OperationCanceledException){}
    }
    sealed class Link: IDisposable
    {
        readonly NativeClipboardSync owner;
        readonly string key;
        readonly ConcurrentDictionary<int,Frame> pending=new();
        readonly SemaphoreSlim signal=new(0,1);
        readonly CancellationTokenSource stop=new();
        int dead;
        public readonly Session Session;
        public Link(NativeClipboardSync owner,string key,Session session){this.owner=owner;this.key=key;Session=session;_=Task.Run(WriteLoop);}
        public void Queue(Frame frame){if(dead!=0)return;pending[frame.Type]=frame;try{signal.Release();}catch(SemaphoreFullException){}}
        async Task WriteLoop()
        {
            try
            {
                while(!stop.IsCancellationRequested)
                {
                    await signal.WaitAsync(stop.Token);
                    foreach(int type in new[]{SyncProtocol.Hello,SyncProtocol.Ack,SyncProtocol.Clip})
                        if(pending.TryRemove(type,out var frame)&&owner.enabled&&owner.pairs.Get(key).Length==32&&owner.engine.MaySend(key,frame))Session.SendSync(frame);
                }
            }
            catch(OperationCanceledException){}
            catch{owner.Remove(key,this);owner.Signal();}
        }
        public void Dispose(){if(Interlocked.Exchange(ref dead,1)!=0)return;pending.Clear();stop.Cancel();Session.Dispose();}
    }
    public void Dispose()
    {
        if(disposed)return;disposed=true;enabled=false;pairs.Changed-=AuthorizationChanged;NetworkChange.NetworkAddressChanged-=NetworkChanged;
        lifetime.Cancel();CancelOperations();foreach(var row in links.ToArray())Remove(row.Key,row.Value);
    }
}
