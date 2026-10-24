using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.NetworkInformation;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
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
        public bool Images{get;set;}=false;
        public string? SelectedAdapter{get;set;}
    }
    sealed class ImagePending
    {
        public Guid Id{get;set;}
        public string ImageRef{get;set;}="";
        public string Sha256{get;set;}="";
        public int Width{get;set;}
        public int Height{get;set;}
        public long CreatedAt{get;set;}
        public long QueuedAt{get;set;}
        public HashSet<string> Peers{get;set;}=new();
    }
    sealed class ImageState
    {
        public int Version{get;set;}=1;
        public ImagePending? Pending{get;set;}
    }
    readonly ClipboardVault vault;
    readonly string root,cache;
    readonly Action<SyncEntry,long> apply;
    readonly ClipboardSyncEngine engine;
    readonly PairStore pairs;
    BluetoothSyncTransport? bluetooth;
    readonly SemaphoreSlim wake=new(0,1);
    readonly ConcurrentDictionary<string,Link> links=new();
    readonly object linkGate=new();
    readonly ConcurrentDictionary<string,byte> connecting=new(),blocked=new();
    readonly CancellationTokenSource lifetime=new();
    readonly object optionsGate=new(),operationGate=new(),imageGate=new();
    readonly object traceGate=new();
    readonly ConcurrentDictionary<string,Guid> imageSending=new();
    ImageState imageState=new();
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
    public int ConnectedCount=>links.Values.Count(link=>link.Ready);
    public string? ConnectionTransport(string key)=>links.TryGetValue(key,out var link)&&link.Ready?link.Kind:null;
    public PairStore Pairing=>pairs;
    public DesktopTransfers Transfers{get;}
    public FileCatalog Files{get;}
    public Session? FirstSession=>links.Values.FirstOrDefault()?.Session;

    public NativeClipboardSync(ClipboardVault vault,Action<SyncEntry,long> apply):this(vault,apply,null){}
    internal NativeClipboardSync(ClipboardVault vault,Action<SyncEntry,long> apply,string? isolatedRoot)
    {
        this.vault=vault;this.apply=apply;
        root=isolatedRoot??Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"FloatClip","Host");cache=Path.Combine(root,"SyncCache");Directory.CreateDirectory(cache);
        string pairedRoot=isolatedRoot==null?Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"FloatClip","Share"):Path.Combine(isolatedRoot,"Share");
        pairs=new PairStore(pairedRoot);
        if(File.Exists(Path.Combine(root,"sync-options.json"))&&new FileInfo(Path.Combine(root,"sync-options.json")).Length>16384)throw new IOException("共享设置过大");
        options=File.Exists(Path.Combine(root,"sync-options.json"))?JsonSerializer.Deserialize<Options>(File.ReadAllText(Path.Combine(root,"sync-options.json")))??throw new IOException("共享设置损坏"):new();
        var device=vault.Device();var local=new SyncDevice(Guid.Parse(device["id"]!.GetValue<string>()),device["name"]!.GetValue<string>(),"windows");
        engine=new ClipboardSyncEngine(local,LoadState,SaveSyncState,Send,PersistSharedEntry,ApplyReceived);
        imageState=LoadImageState();
        string PeerName(string key)=>engine.Snapshot().Peers.TryGetValue(key,out var peer)?peer.Device?.Name??pairs.Describe(key)?.Name??"已配对设备":pairs.Describe(key)?.Name??"已配对设备";
        Transfers=new DesktopTransfers(PeerName,FileAllowed);
        Files=new FileCatalog(root,cache,FileAllowed,PeerName);
        Files.RequestConnection=RequestFileConnection;
        if(!pairs.Corrupt)
        {
            // v1 Windows keyed sync peers by the Android TLS pin. PairStore v2 derives the
            // relationship key from sha256(token), matching Android's existing identity.
            foreach(string oldKey in engine.Snapshot().Peers.Keys.ToArray())
            {
                string? relation=pairs.KeyForPin(oldKey);
                if(relation!=null&&relation!=oldKey)engine.MigratePeerKey(oldKey,relation);
            }
            foreach(string key in engine.Snapshot().Peers.Keys.ToArray())if(pairs.GetByKey(key).Length!=32)engine.Forget(key);
        }
        enabled=engine.Snapshot().Enabled;
        Status=enabled?"等待已授权设备自动连接":"共享已关闭";
        pairs.Changed+=AuthorizationChanged;
        NetworkChange.NetworkAddressChanged+=NetworkChanged;
    }
    void Trace(string operation,object? detail=null)
    {
        // Deliberately exclude clipboard contents, credentials, file names and device IDs.
        try{lock(traceGate){string path=Path.Combine(root,"sync-diagnostics.jsonl");if(File.Exists(path)&&new FileInfo(path).Length>65536)File.WriteAllText(path,"");File.AppendAllText(path,JsonSerializer.Serialize(new{utc=DateTime.UtcNow,operation,detail})+Environment.NewLine);}}catch{}
    }
    void SaveSyncState(byte[] bytes){try{SaveState(bytes);}catch(Exception e)when(e is IOException or UnauthorizedAccessException or CryptographicException){throw new PersistenceFailure(e);}}
    string ImageStatePath=>Path.Combine(root,"clipboard-image-sync.v1.bin");
    ImageState LoadImageState()
    {
        string path=ImageStatePath;if(!File.Exists(path))return new();
        if(new FileInfo(path).Length>131072)throw new IOException("图片同步状态过大");
        ImageState state;
        try{state=JsonSerializer.Deserialize<ImageState>(ProtectedData.Unprotect(File.ReadAllBytes(path),null,DataProtectionScope.CurrentUser))??throw new IOException("图片同步状态损坏");}
        catch(CryptographicException e){throw new IOException("图片同步状态无法解密",e);}
        if(state.Version!=1)throw new IOException("图片同步状态版本不支持");
        if(state.Pending is { } pending)
        {
            if(pending.Id==Guid.Empty||!System.Text.RegularExpressions.Regex.IsMatch(pending.ImageRef,"^[0-9a-fA-F]{32}$")||
               !System.Text.RegularExpressions.Regex.IsMatch(pending.Sha256,"^[0-9a-f]{64}$")||pending.Width<1||pending.Height<1||
               pending.Width>ClipboardImageTransfer.MaximumEdge||pending.Height>ClipboardImageTransfer.MaximumEdge||
               (long)pending.Width*pending.Height>ClipboardImageTransfer.MaximumPixels||pending.CreatedAt<0||pending.QueuedAt<0||pending.Peers.Count>8||
               pending.Peers.Any(k=>!System.Text.RegularExpressions.Regex.IsMatch(k,"^[a-f0-9]{64}$")))throw new IOException("图片同步状态无效");
        }
        return state;
    }
    void SaveImageStateLocked(ImagePending? pending)
    {
        var next=new ImageState{Version=1,Pending=pending};
        string path=ImageStatePath,temporary=path+".tmp";
        byte[] plain=JsonSerializer.SerializeToUtf8Bytes(next),cipher=ProtectedData.Protect(plain,null,DataProtectionScope.CurrentUser);
        try{using(var output=new FileStream(temporary,FileMode.Create,FileAccess.Write,FileShare.None)){output.Write(cipher);output.Flush(true);}File.Move(temporary,path,true);imageState=next;}
        finally{if(File.Exists(temporary))File.Delete(temporary);}
    }
    static ImagePending WithPeers(ImagePending pending,IEnumerable<string> peers)=>new()
    {
        Id=pending.Id,ImageRef=pending.ImageRef,Sha256=pending.Sha256,Width=pending.Width,Height=pending.Height,
        CreatedAt=pending.CreatedAt,QueuedAt=pending.QueuedAt,Peers=peers.ToHashSet()
    };
    void AbortLinks(IEnumerable<string> keys)
    {
        foreach(string key in keys.Distinct().ToArray())if(links.TryGetValue(key,out var link))Remove(key,link);
    }
    void ClearPendingImage(bool abortInFlight=true)
    {
        string[] inFlight=imageSending.Keys.ToArray();
        lock(imageGate)if(imageState.Pending!=null)SaveImageStateLocked(null);
        imageSending.Clear();
        if(abortInFlight&&inFlight.Length>0)AbortLinks(inFlight);
    }
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
    public bool ImageSharing{get{lock(optionsGate)return options.Images;}}
    public void SetImageSharing(bool value)
    {
        lock(optionsGate){if(options.Images==value)return;options.Images=value;SaveOptions();}
        if(!value)ClearPendingImage();else foreach(string key in links.Keys)TrySendImage(key);
        Changed?.Invoke();
    }
    public void RequestFileConnection(string? peer=null)
    {
        if(!enabled||disposed||!Automatic)return;
        if(peer!=null&&!FileAllowed(peer))return;
        EnsureBluetooth();bluetooth?.Nudge(peer==null?null:new[]{peer});Signal();
    }
    public void ReconnectNow()
    {
        if(!enabled)throw new IOException("请先开启共享剪贴板");
        if(!Automatic)throw new IOException("请先在同步选项中开启自动连接");
        blocked.Clear();EnsureBluetooth();bluetooth?.Nudge();Signal();SetStatus("正在重新连接已授权设备");
    }
    public bool MayApply(SyncEntry entry,long grant)=>enabled&&!disposed&&engine.MayApply(entry,grant);
    public void Configure(bool on,bool sending,bool receiving,bool applying,bool automatic)
    {
        if(disposed)throw new ObjectDisposedException(nameof(NativeClipboardSync));
        CancelOperations();
        if(!on){enabled=false;StopBluetooth();foreach(var row in links.ToArray())Remove(row.Key,row.Value);}
        try{engine.Configure(on,sending,receiving,applying);}
        catch{enabled=false;foreach(var row in links.ToArray())Remove(row.Key,row.Value);throw;}
        if(!on||!sending||!ImageSharing)ClearPendingImage();
        lock(optionsGate){options.Automatic=automatic;SaveOptions();}
        enabled=on;Start();if(on&&automatic)EnsureBluetooth();else StopBluetooth();Signal();SetStatus(on?"等待已授权设备自动连接":"共享已暂停");
    }
    bool FileAllowed(string key)=>enabled&&!disposed&&pairs.GetByKey(key).Length==32&&(!engine.Snapshot().Peers.TryGetValue(key,out var peer)||peer.Enabled);
    public void SetPeerEnabled(string relationKey,bool on){if(pairs.GetByKey(relationKey).Length!=32)throw new IOException("设备授权不存在");engine.SetPeerEnabled(relationKey,on);if(!on){DropImagePeer(relationKey);if(links.TryGetValue(relationKey,out var link))Remove(relationKey,link);}else TrySendImage(relationKey);Changed?.Invoke();}
    public void Forget(string relationKey){pairs.RemoveKey(relationKey);engine.Forget(relationKey);DropImagePeer(relationKey);}
    public void Start()
    {
        if(Interlocked.Exchange(ref started,1)!=0){if(enabled&&Automatic)EnsureBluetooth();return;}
        if(enabled&&Automatic)EnsureBluetooth();
        _=Task.Run(ReconnectLoop);
        _=Task.Run(RetryLoop);
    }
    void EnsureBluetooth(bool force=false)
    {
        if(disposed||!enabled||(!Automatic&&!force)||bluetooth!=null)return;
        var created=new BluetoothSyncTransport(pairs,cache,CanBluetoothAttach,AttachSession,message=>{if(enabled&&!disposed&&links.IsEmpty)SetStatus(message);});
        if(Interlocked.CompareExchange(ref bluetooth,created,null)!=null){created.Dispose();return;}
        created.Start();
    }
    void StopBluetooth(){Interlocked.Exchange(ref bluetooth,null)?.Dispose();}
    bool CanBluetoothAttach(string key)
    {
        if(disposed||!enabled||!Automatic||pairs.GetByKey(key).Length!=32||links.ContainsKey(key))return false;
        var snapshot=engine.Snapshot();
        return !snapshot.Peers.TryGetValue(key,out var peer)||peer.Enabled;
    }
    public void Copied(string value,bool excluded=false)
    {
        if(!enabled||disposed)return;
        Trace("copy-observed",new{excluded,characters=value.Length,connections=ConnectedCount});
        try
        {
            ClearPendingImage(abortInFlight:true);
            var entry=engine.Copied(value,DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),excluded);
            if(entry!=null&&Automatic){EnsureBluetooth();bluetooth?.Nudge();}
        }
        catch{SetStatus("该条目未同步，请检查长度或本机存储");}
    }
    public void CopiedImage(JsonObject entry)
    {
        if(!enabled||disposed||!ImageSharing||ClipboardVault.Kind(entry)!="image")return;
        try
        {
            long now=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            ClearPendingImage(abortInFlight:true);
            engine.Copied("",now,true);
            // MaySend rechecks queued text against the invalidated outbox. Keep established
            // sessions alive so manual connections and catalog transfers remain usable.
            string imageRef=entry["imageRef"]?.GetValue<string>()??throw new IOException("图片引用缺失");
            string sha=entry["imageSha256"]?.GetValue<string>()??throw new IOException("图片校验信息缺失");
            int width=checked((int)(entry["imageWidth"]?.GetValue<long>()??0)),height=checked((int)(entry["imageHeight"]?.GetValue<long>()??0));
            long created=entry["createdAt"]?.GetValue<long>()??now;
            Guid id=Guid.TryParse(entry["id"]?.GetValue<string>(),out var parsed)?parsed:Guid.NewGuid();
            var peers=engine.Snapshot().Peers.Where(row=>row.Value.Enabled&&row.Value.Device!=null&&row.Value.Receive&&pairs.GetByKey(row.Key).Length==32).Select(row=>row.Key).ToHashSet();
            lock(imageGate)SaveImageStateLocked(peers.Count==0?null:new ImagePending{Id=id,ImageRef=imageRef,Sha256=sha,Width=width,Height=height,CreatedAt=created,QueuedAt=now,Peers=peers});
            imageSending.Clear();
            if(peers.Count>0){foreach(string key in peers)TrySendImage(key);Signal();if(Automatic){EnsureBluetooth();bluetooth?.Nudge(peers.ToArray());}}
        }
        catch{SetStatus("图片已保存到本机，但跨设备同步尚未完成");}
    }
    string ImageTemp(Guid id,string key)=>Path.Combine(cache,"clipboard-"+id.ToString("N")+"-"+key[..Math.Min(12,key.Length)]+".png");
    void DropImagePeer(string key)
    {
        bool inFlight=imageSending.TryRemove(key,out _);
        lock(imageGate)
        {
            var pending=imageState.Pending;if(pending!=null&&pending.Peers.Contains(key))
            {
                var peers=pending.Peers.Where(p=>p!=key).ToArray();
                SaveImageStateLocked(peers.Length==0?null:WithPeers(pending,peers));
            }
        }
        if(inFlight)AbortLinks(new[]{key});
    }
    bool MayReceiveImage(string key,Offer offer,ClipboardImageMeta meta)
    {
        if(offer.Size<33||offer.Size>ClipboardImageTransfer.MaximumBytes)return false;
        var snapshot=engine.Snapshot();
        return enabled&&ImageSharing&&snapshot.Enabled&&snapshot.Receive&&snapshot.Peers.TryGetValue(key,out var peer)&&peer.Enabled&&peer.Send&&peer.Device!=null&&pairs.GetByKey(key).Length==32;
    }
    void TrySendImage(string key)
    {
        if(disposed||!enabled||!ImageSharing||!links.TryGetValue(key,out var link)||imageSending.ContainsKey(key))return;
        ImagePending? pending;
        lock(imageGate)
        {
            pending=imageState.Pending;
            if(pending==null||!pending.Peers.Contains(key))return;
            long age=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()-pending.QueuedAt;
            if(age<0||age>SyncProtocol.RetryTtl){SaveImageStateLocked(null);return;}
        }
        var snapshot=engine.Snapshot();
        if(!snapshot.Send||!snapshot.Peers.TryGetValue(key,out var peer)||!peer.Enabled||peer.Device==null||!peer.Receive){DropImagePeer(key);return;}
        string path=ImageTemp(pending.Id,key);
        try
        {
            var row=vault.FindImage(pending.ImageRef)??throw new IOException("待发送图片已删除");
            byte[] bytes=vault.ReadImage(row);
            if(!CryptographicOperations.FixedTimeEquals(SHA256.HashData(bytes),Convert.FromHexString(pending.Sha256)))throw new IOException("待发送图片已变化");
            ClipboardImageTransfer.ValidatePng(bytes,new(pending.Id,pending.CreatedAt,pending.Width,pending.Height));
            File.WriteAllBytes(path,bytes);
            if(!imageSending.TryAdd(key,pending.Id))return;
            link.Session.SendFile(path,ClipboardImageTransfer.Name(pending.Id,pending.CreatedAt,pending.Width,pending.Height));
        }
        catch
        {
            imageSending.TryRemove(key,out _);try{File.Delete(path);}catch{}
        }
    }
    void FinishImagePeer(string key,Guid id,bool delivered)
    {
        imageSending.TryRemove(key,out _);try{File.Delete(ImageTemp(id,key));}catch{}
        if(!delivered)return;
        lock(imageGate)
        {
            var pending=imageState.Pending;if(pending==null||pending.Id!=id||!pending.Peers.Contains(key))return;
            var peers=pending.Peers.Where(p=>p!=key).ToArray();
            SaveImageStateLocked(peers.Length==0?null:WithPeers(pending,peers));
        }
    }
    void ReceiveImage(string key,Offer offer,string path)
    {
        try
        {
            var meta=ClipboardImageTransfer.Parse(offer.Name);
            if(!MayReceiveImage(key,offer,meta))throw new IOException("未授权接收剪贴板图片");
            byte[] bytes=File.ReadAllBytes(path);
            if(bytes.LongLength!=offer.Size||!CryptographicOperations.FixedTimeEquals(SHA256.HashData(bytes),offer.Hash))throw new IOException("图片传输校验失败");
            ClipboardImageTransfer.ValidatePng(bytes,meta);
            var peer=engine.Snapshot().Peers[key].Device!;
            vault.ReceiveSharedImage(meta.Id,peer,bytes,meta.Width,meta.Height,meta.CreatedAt);
            try{HistoryChanged?.Invoke();}catch{}
            SetStatus("已收到 "+peer.Name+" 的剪贴板图片");
        }
        finally{try{File.Delete(path);}catch{}}
    }
    void Send(string key,Frame frame){if(enabled&&!disposed&&pairs.GetByKey(key).Length==32&&links.TryGetValue(key,out var link))link.Queue(frame);}
    void SetStatus(string status){Status=status;Changed?.Invoke();}
    void NetworkChanged(object? sender,EventArgs args)=>Signal();
    void Signal(){try{wake.Release();}catch(SemaphoreFullException){}catch(ObjectDisposedException){}}
    void AuthorizationChanged()
    {
        foreach(var key in engine.Snapshot().Peers.Keys.ToArray())if(pairs.GetByKey(key).Length!=32){if(links.TryGetValue(key,out var link))Remove(key,link);engine.Forget(key);DropImagePeer(key);}
        blocked.Clear();Signal();Changed?.Invoke();
    }
    public async Task PairBluetoothAsync(Func<string,bool> verify,CancellationToken cancellation)
    {
        if(!enabled)throw new IOException("请先开启剪贴板共享");
        EnsureBluetooth(true);var transport=bluetooth??throw new IOException("蓝牙短连接不可用");
        try
        {
            await transport.PairAsync(verify,cancellation);
            SetStatus("蓝牙设备已授权 · 正在建立首次同步");
            if(Automatic)transport.Nudge();
        }
        finally{if(!Automatic)StopBluetooth();}
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
        string pin=device.Pin.ToLowerInvariant();string? known=pairs.KeyForPin(pin);string attemptKey=known??("pin:"+pin);
        if(!connecting.TryAdd(attemptKey,0))return;
        string? key=known;Link? added=null;
        try
        {
            // An active text-only Bluetooth burst must not prevent a trusted LAN upgrade.
            if(key!=null&&links.TryGetValue(key,out var current)&&current.Kind=="lan")return;
            using var cancel=CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token,cancellation);
            var session=await Task.Run(()=>Nearby.Connect(device,cache,pairs,verify,cancel.Token,persistent:true),cancel.Token);
            key=pairs.KeyForPin(pin)??throw new IOException("设备授权未保存");
            if(!enabled||disposed||pairs.GetByKey(key).Length!=32){session.Dispose();return;}
            AttachSession(key,session,"lan");added=links.TryGetValue(key,out var attached)&&ReferenceEquals(attached.Session,session)?attached:null;
        }
        catch(PairingAuthorizationException){if(added!=null&&key!=null)Remove(key,added);if(key!=null)blocked[key]=0;SetStatus("设备授权已失效，需要重新核对");throw;}
        catch{if(added!=null&&key!=null)Remove(key,added);throw;}
        finally{connecting.TryRemove(attemptKey,out _);}
    }
    void AttachSession(string key,Session session,string kind)
    {
        Trace("transport-admitted",new{kind});
        int priority=kind=="lan"?2:1;
        Link link;
        lock(linkGate)
        {
            if(disposed||!enabled||pairs.GetByKey(key).Length!=32){session.Dispose();return;}
            if(links.TryGetValue(key,out var existing))
            {
                if(existing.Priority>=priority||existing.Session.HasActiveFile){session.Dispose();return;}
                if(((System.Collections.Generic.ICollection<System.Collections.Generic.KeyValuePair<string,Link>>)links).Remove(new(key,existing)))
                {engine.Disconnected(key);existing.Dispose();}
            }
            link=new Link(this,key,session,kind,priority);
            if(!links.TryAdd(key,link)){link.Dispose();return;}
        }
        session.Sync=frame=>
        {
            if(!enabled||!links.TryGetValue(key,out var active)||!ReferenceEquals(active,link)||pairs.GetByKey(key).Length!=32)return;
            Trace("sync-received",new{kind,type=frame.Type});
            try
            {
                engine.Accept(key,frame,DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
                if(frame.Type==SyncProtocol.Hello&&engine.Snapshot().Peers.TryGetValue(key,out var peer)&&peer.Device!=null){pairs.RememberDevice(key,peer.Device);link.Ready=true;if(session.CatalogEnabled)Files.Publish(key);TrySendImage(key);Trace("hello-ready",new{kind,send=peer.Send,receive=peer.Receive,catalog=session.CatalogEnabled});SetStatus(ConnectedCount+" 台设备同步就绪 · "+(kind=="bluetooth"?"蓝牙加密连接":"局域网加密共享"));}
            }
            catch(PersistenceFailure){Remove(key,link);SetStatus("本机保存失败，内容尚未确认送达；存储恢复后将重试连接");Signal();}
            catch(Exception e){Trace("sync-rejected",new{error=e.GetType().Name});blocked[key]=0;Remove(key,link);SetStatus("设备身份或同步校验失败，请重新核对授权");}
        };
        session.Closed=()=>{Remove(key,link);Signal();};
        Transfers.Attach(key,session,kind);
        if(session.CatalogEnabled)Files.Attach(key,session);
        var catalogOffer=session.Offered;var catalogRejected=session.Rejected;var catalogFile=session.File;var catalogFailure=session.FileFailed;
        bool Active()=>enabled&&!disposed&&links.TryGetValue(key,out var current)&&ReferenceEquals(current,link)&&pairs.GetByKey(key).Length==32;
        session.Offered=offer=>{
            if(!Active())return;
            if(ClipboardImageTransfer.TryParse(offer.Name,out var meta)&&meta!=null)
            {
                try{if(MayReceiveImage(key,offer,meta))session.Accept(offer.Id);else session.Reject(offer.Id);}catch{session.Dispose();}
                return;
            }
            catalogOffer?.Invoke(offer);
        };
        session.Rejected=offer=>{if(!Active())return;if(ClipboardImageTransfer.TryParse(offer.Name,out var meta)&&meta!=null){FinishImagePeer(key,meta.Id,false);DropImagePeer(key);}else catalogRejected?.Invoke(offer);};
        session.File=(offer,path,outgoing)=>{
            if(!Active()){if(!outgoing)try{File.Delete(path);}catch{}return;}
            if(ClipboardImageTransfer.TryParse(offer.Name,out var meta)&&meta!=null){if(outgoing)FinishImagePeer(key,meta.Id,true);else ReceiveImage(key,offer,path);return;}
            catalogFile?.Invoke(offer,path,outgoing);
        };
        session.FileFailed=message=>{if(!Active())return;imageSending.TryRemove(key,out _);catalogFailure?.Invoke(message);};
        session.Failure=error=>{imageSending.TryRemove(key,out _);SetStatus(kind=="bluetooth"?"蓝牙连接结束，等待下次同步":"连接中断，等待自动重连");};
        try
        {
            engine.Connected(key);
            try{SessionOpened?.Invoke(session);}catch{}
            SetStatus("正在完成同步握手…");
            session.Start();
            _=Task.Run(async()=>{try{await Task.Delay(TimeSpan.FromSeconds(8),lifetime.Token);if(!link.Ready&&links.TryGetValue(key,out var current)&&ReferenceEquals(current,link)){Trace("hello-timeout",new{kind});Remove(key,link);Signal();SetStatus("同步握手超时，等待重新连接");}}catch(OperationCanceledException){}});
        }
        catch{Remove(key,link);throw;}
    }
    void Remove(string key,Link expected)
    {
        if(((System.Collections.Generic.ICollection<System.Collections.Generic.KeyValuePair<string,Link>>)links).Remove(new(key,expected)))
        {imageSending.TryRemove(key,out _);engine.Disconnected(key);expected.Dispose();Changed?.Invoke();}
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
                    string[] pending=pairs.Pins().Where(pin=>{string? key=pairs.KeyForPin(pin);return key!=null&&(!links.TryGetValue(key,out var current)||current.Kind!="lan")&&!blocked.ContainsKey(key);}).ToArray();
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
        using var timer=new PeriodicTimer(TimeSpan.FromSeconds(5));
        try
        {
            while(await timer.WaitForNextTickAsync(lifetime.Token))if(enabled)
            {
                try
                {
                    long now=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                    foreach(string key in links.Keys){engine.Retry(key,now);TrySendImage(key);}
                    string[] imagePeers;lock(imageGate){var p=imageState.Pending;imagePeers=p==null||now-p.QueuedAt>SyncProtocol.RetryTtl?Array.Empty<string>():p.Peers.ToArray();}
                    var pending=engine.PendingPeers(now).Concat(imagePeers).Where(key=>!links.ContainsKey(key)).Distinct().ToArray();
                    if(Automatic&&pending.Length>0){EnsureBluetooth();bluetooth?.Nudge(pending);}
                }
                catch{SetStatus("共享状态未保存，请检查本机存储");}
            }
        }
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
        public volatile bool Ready;
        public readonly Session Session;public readonly string Kind;public readonly int Priority;
        public Link(NativeClipboardSync owner,string key,Session session,string kind,int priority){this.owner=owner;this.key=key;Session=session;Kind=kind;Priority=priority;_=Task.Run(WriteLoop);}
        public void Queue(Frame frame){if(dead!=0)return;pending[frame.Type]=frame;try{signal.Release();}catch(SemaphoreFullException){}}
        async Task WriteLoop()
        {
            try
            {
                while(!stop.IsCancellationRequested)
                {
                    await signal.WaitAsync(stop.Token);
                    foreach(int type in new[]{SyncProtocol.Hello,SyncProtocol.Ack,SyncProtocol.Clip})
                        if(pending.TryRemove(type,out var frame)&&owner.enabled&&owner.pairs.GetByKey(key).Length==32&&owner.engine.MaySend(key,frame)){Session.SendSync(frame);owner.Trace("sync-sent",new{kind=Kind,type});}
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
        StopBluetooth();lifetime.Cancel();CancelOperations();foreach(var row in links.ToArray())Remove(row.Key,row.Value);
    }
}
