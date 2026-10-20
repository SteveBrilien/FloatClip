using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace FloatClip.Share;

public sealed record LanNetwork(string Name,IPAddress Address,int Prefix,int Index,IPAddress? Gateway,bool Wireless=false,string? AdapterId=null)
{
    public override string ToString()=>Name+" · "+Address;
    public string Subnet {
        get { var data=Address.GetAddressBytes(); for(int i=0;i<4;i++) data[i]&=(byte)(0xff<<Math.Clamp(8-(Prefix-i*8),0,8)); return new IPAddress(data)+"/"+Prefix; }
    }
    public bool Contains(IPAddress address) {
        var x=Address.GetAddressBytes(); var y=address.GetAddressBytes(); if(y.Length!=4 || !Nearby.Private(address)) return false;
        for(int i=0;i<4;i++) { int mask=0xff<<Math.Clamp(8-(Prefix-i*8),0,8); if((x[i]&mask)!=(y[i]&mask)) return false; } return true;
    }
    public IPAddress Broadcast { get { var data=Address.GetAddressBytes(); for(int i=0;i<4;i++) data[i]|=(byte)~(0xff<<Math.Clamp(8-(Prefix-i*8),0,8)); return new(data); } }
    public void Bind(Socket socket) {
        socket.Bind(new IPEndPoint(Address,0));
        if(OperatingSystem.IsWindows()) socket.SetSocketOption(SocketOptionLevel.IP,(SocketOptionName)31 /* IP_UNICAST_IF */,IPAddress.HostToNetworkOrder(Index));
    }
}

public sealed class PairingAuthorizationException: IOException
{
    public PairingAuthorizationException(string message):base(message){}
}

/** User-scoped encrypted credentials. The injectable codec is used by portable persistence checks. */
public sealed class PairStore
{
    public sealed record TrustedPeer(string Key,string? OutboundPin,Guid? DeviceId,string? Name,string? Platform,bool AcceptReady,ulong? BluetoothAddress);
    sealed class Entry
    {
        public Entry(){}
        public string Token{get;set;}="";
        public string? OutboundPin{get;set;}
        public Guid? DeviceId{get;set;}
        public string? Name{get;set;}
        public string? Platform{get;set;}
        public bool AcceptReady{get;set;}
        public ulong? BluetoothAddress{get;set;}
        public Entry Copy()=>new(){Token=Token,OutboundPin=OutboundPin,DeviceId=DeviceId,Name=Name,Platform=Platform,AcceptReady=AcceptReady,BluetoothAddress=BluetoothAddress};
    }
    sealed class PairFile
    {
        public PairFile(){}
        public int Version{get;set;}=2;
        public List<Entry> Peers{get;set;}=new();
    }

    readonly object gate=new();
    readonly string path;
    readonly Func<byte[],byte[]> protect,unprotect;
    readonly bool supported;
    Dictionary<string,Entry> peers=new();
    bool corrupt;
    public event Action? Changed;
    public PairStore(string root,Func<byte[],byte[]>? protect=null,Func<byte[],byte[]>? unprotect=null)
    {
        Directory.CreateDirectory(root);path=Path.Combine(root,"paired-devices.dat");
        supported=OperatingSystem.IsWindows()||protect!=null&&unprotect!=null;
        this.protect=protect??(b=>ProtectedData.Protect(b,null,DataProtectionScope.CurrentUser));
        this.unprotect=unprotect??(b=>ProtectedData.Unprotect(b,null,DataProtectionScope.CurrentUser));
        if(!supported||!System.IO.File.Exists(path))return;
        try
        {
            if(new FileInfo(path).Length>32768)throw new IOException("授权文件过大");
            byte[] plain=this.unprotect(System.IO.File.ReadAllBytes(path));
            using var document=JsonDocument.Parse(plain);
            if(document.RootElement.TryGetProperty("Version",out _))
            {
                var saved=JsonSerializer.Deserialize<PairFile>(plain)??throw new IOException("授权文件无效");
                if(saved.Version!=2||saved.Peers.Count>8)throw new IOException("授权文件无效");
                foreach(var entry in saved.Peers)AddValidated(entry);
            }
            else
            {
                // v1 was pin -> token. The relation key is the token hash, which already
                // matches Android's peer-<sha256(token)> identity.
                var legacy=JsonSerializer.Deserialize<Dictionary<string,string>>(plain)??throw new IOException("授权文件无效");
                if(legacy.Count>8)throw new IOException("授权文件过大");
                foreach(var pair in legacy)
                {
                    string pin=pair.Key.ToLowerInvariant();if(!ValidHash(pin))throw new IOException("授权文件无效");
                    AddValidated(new Entry{Token=pair.Value,OutboundPin=pin});
                }
            }
        }
        catch{peers.Clear();corrupt=true;}
    }
    static bool ValidHash(string? value)=>value!=null&&System.Text.RegularExpressions.Regex.IsMatch(value,"^[a-f0-9]{64}$");
    static string KeyOf(byte[] token)=>Convert.ToHexString(SHA256.HashData(token)).ToLowerInvariant();
    static byte[] Token(Entry entry){try{var token=Convert.FromBase64String(entry.Token);if(token.Length!=32)throw new IOException();return token;}catch{throw new IOException("授权凭据无效");}}
    void AddValidated(Entry entry)
    {
        byte[] token=Token(entry);string key=KeyOf(token);
        if(entry.OutboundPin!=null){entry.OutboundPin=entry.OutboundPin.ToLowerInvariant();if(!ValidHash(entry.OutboundPin))throw new IOException("授权文件无效");}
        if(entry.DeviceId==Guid.Empty||entry.Name!=null&&(entry.Name.Length is <1 or >80||entry.Name.Any(char.IsControl))||
           entry.Platform!=null&&!new[]{"android","windows","ipad","web"}.Contains(entry.Platform))throw new IOException("授权文件无效");
        if(!peers.TryAdd(key,entry.Copy()))throw new IOException("授权文件含重复设备");
    }
    public int Count{get{lock(gate)return peers.Count;}}
    public bool Corrupt{get{lock(gate)return corrupt;}}
    public string[] Keys(){lock(gate)return peers.Keys.ToArray();}
    public string[] Pins(){lock(gate)return peers.Values.Select(p=>p.OutboundPin).Where(p=>p!=null).Cast<string>().Distinct().ToArray();}
    public string? KeyForPin(string pin){lock(gate){pin=pin.ToLowerInvariant();return peers.FirstOrDefault(p=>p.Value.OutboundPin==pin).Key;}}
    public byte[] Get(string pin){lock(gate){string? key=KeyForPin(pin);return key!=null&&peers.TryGetValue(key,out var entry)?Token(entry):Array.Empty<byte>();}}
    public byte[] GetByKey(string key){lock(gate)return peers.TryGetValue(key.ToLowerInvariant(),out var entry)?Token(entry):Array.Empty<byte>();}
    public TrustedPeer? Describe(string key)
    {
        lock(gate)
        {
            key=key.ToLowerInvariant();if(!peers.TryGetValue(key,out var p))return null;
            return new(key,p.OutboundPin,p.DeviceId,p.Name,p.Platform,p.AcceptReady,p.BluetoothAddress);
        }
    }
    public void Put(string pin,byte[] token)
    {
        if(!supported)return;
        pin=pin.ToLowerInvariant();if(!ValidHash(pin)||token.Length!=32)throw new IOException("授权凭据无效");
        string key=KeyOf(token);
        lock(gate)
        {
            if(corrupt)throw new IOException("授权文件损坏，请保留数据并明确重置授权后再配对");
            var next=peers.ToDictionary(p=>p.Key,p=>p.Value.Copy());
            if(next.TryGetValue(key,out var existing))
            {
                if(existing.OutboundPin!=null&&existing.OutboundPin!=pin)throw new IOException("设备证书身份已改变，需要重新核对");
                if(existing.OutboundPin==pin)return;existing.OutboundPin=pin;
            }
            else
            {
                if(next.Count>=8)throw new IOException("最多记住 8 台设备");
                next[key]=new Entry{Token=Convert.ToBase64String(token),OutboundPin=pin};
            }
            Save(next);
        }
        Changed?.Invoke();
    }
    public string PutBluetooth(byte[] token,ulong address)
    {
        if(!supported)throw new IOException("需要用户范围的加密存储");
        if(token.Length!=32||address==0||address>0x0000FFFFFFFFFFFFUL)throw new IOException("蓝牙授权凭据无效");
        string key=KeyOf(token);
        lock(gate)
        {
            if(corrupt)throw new IOException("授权文件损坏，请保留数据并明确重置授权后再配对");
            var next=peers.ToDictionary(p=>p.Key,p=>p.Value.Copy());
            if(next.TryGetValue(key,out var existing))
            {
                if(existing.BluetoothAddress==address)return key;
                existing.BluetoothAddress=address;
            }
            else
            {
                if(next.Count>=8)throw new IOException("最多记住 8 台设备");
                next[key]=new Entry{Token=Convert.ToBase64String(token),BluetoothAddress=address};
            }
            Save(next);
        }
        Changed?.Invoke();return key;
    }
    public void RememberDevice(string key,SyncDevice device)
    {
        SyncProtocol.ValidateDevice(device);key=key.ToLowerInvariant();
        lock(gate)
        {
            if(corrupt||!peers.TryGetValue(key,out var current))throw new IOException("设备授权不存在");
            if(current.DeviceId!=null&&current.DeviceId!=device.Id)throw new IOException("记住的设备身份已改变，需要重新授权");
            if(current.DeviceId==device.Id&&current.Name==device.Name&&current.Platform==device.Platform)return;
            var next=peers.ToDictionary(p=>p.Key,p=>p.Value.Copy());var row=next[key];
            row.DeviceId=device.Id;row.Name=device.Name;row.Platform=device.Platform;Save(next);
        }
        Changed?.Invoke();
    }
    public void RememberBluetooth(string key,ulong address)
    {
        key=key.ToLowerInvariant();if(address==0||address>0x0000FFFFFFFFFFFFUL)throw new IOException("蓝牙地址无效");
        lock(gate)
        {
            if(corrupt||!peers.ContainsKey(key))throw new IOException("设备授权不存在");
            if(peers[key].BluetoothAddress==address)return;
            var next=peers.ToDictionary(p=>p.Key,p=>p.Value.Copy());next[key].BluetoothAddress=address;Save(next);
        }
        Changed?.Invoke();
    }
    public void MarkAcceptReady(string key,bool ready)
    {
        key=key.ToLowerInvariant();
        lock(gate)
        {
            if(corrupt||!peers.ContainsKey(key))throw new IOException("设备授权不存在");
            if(peers[key].AcceptReady==ready)return;
            var next=peers.ToDictionary(p=>p.Key,p=>p.Value.Copy());next[key].AcceptReady=ready;Save(next);
        }
        Changed?.Invoke();
    }
    public void Remove(string pin)
    {
        string? key=KeyForPin(pin);if(key!=null)RemoveKey(key);
    }
    public void RemoveKey(string key)
    {
        lock(gate){if(corrupt)throw new IOException("授权文件损坏");var next=peers.ToDictionary(p=>p.Key,p=>p.Value.Copy());next.Remove(key.ToLowerInvariant());Save(next);}
        Changed?.Invoke();
    }
    public void Clear(){lock(gate){Save(new());corrupt=false;}Changed?.Invoke();}
    void Save(Dictionary<string,Entry> next)
    {
        if(!supported)throw new IOException("需要用户范围的加密存储");
        var file=new PairFile{Peers=next.Values.Select(p=>p.Copy()).ToList()};
        byte[] encrypted=protect(JsonSerializer.SerializeToUtf8Bytes(file));string temporary=path+".tmp";
        try{using(var f=new FileStream(temporary,FileMode.Create,FileAccess.Write,FileShare.None)){f.Write(encrypted);f.Flush(true);}System.IO.File.Move(temporary,path,true);peers=next;}
        finally{if(System.IO.File.Exists(temporary))System.IO.File.Delete(temporary);}
    }
}
public sealed record NearbyDevice(string Name,IPAddress Address,string Pin,string Id,LanNetwork Network)
{
    public override string ToString()=>Name+" · "+Address;
}
public static class Nearby
{
    public static bool Private(IPAddress ip) { var b=ip.GetAddressBytes(); return b.Length==4 && (b[0]==10 || (b[0]==172 && b[1]>=16 && b[1]<=31) || (b[0]==192 && b[1]==168)); }
    public static List<LanNetwork> Networks()=>NetworkInterface.GetAllNetworkInterfaces()
        .Where(n=>n.OperationalStatus==OperationalStatus.Up && (n.NetworkInterfaceType==NetworkInterfaceType.Wireless80211 || n.NetworkInterfaceType==NetworkInterfaceType.Ethernet) && !System.Text.RegularExpressions.Regex.IsMatch(n.Name+" "+n.Description,"tun|tap|virtual|vpn|hyper-v|wintun|wireguard",System.Text.RegularExpressions.RegexOptions.IgnoreCase))
        .OrderBy(n=>n.NetworkInterfaceType==NetworkInterfaceType.Wireless80211 ? 0 : 1)
        .SelectMany(n=>n.GetIPProperties().UnicastAddresses.Where(a=>Private(a.Address)).Select(a=>new LanNetwork(n.Name,a.Address,a.PrefixLength,n.GetIPProperties().GetIPv4Properties().Index,n.GetIPProperties().GatewayAddresses.Select(g=>g.Address).FirstOrDefault(Private),n.NetworkInterfaceType==NetworkInterfaceType.Wireless80211,n.Id))).ToList();
    public static string Diagnostics(LanNetwork? network) {
        bool tun=NetworkInterface.GetAllNetworkInterfaces().Any(n=>n.OperationalStatus==OperationalStatus.Up && System.Text.RegularExpressions.Regex.IsMatch(n.Name+" "+n.Description,"tun|meta|mihomo|clash|wireguard",System.Text.RegularExpressions.RegexOptions.IgnoreCase));
        return network==null ? "未找到可用的实体网卡，请连接 Wi-Fi 或热点。" : "当前网络："+network+"\n网段："+network.Subnet+"\n"+(tun ? "检测到 VPN / TUN 网卡。若连接失败，请在 Clash 的 TUN 流量排除中加入下方网段。" : "已选择实体网卡。设备仍需处于可互通的网络。")+"\n\ntun:\n  route-exclude-address:\n    - "+network.Subnet+"\n\n请合并到已有配置并保留原排除项。此程序不会修改代理、路由或防火墙。";
    }
    public static async Task<List<NearbyDevice>> Discover(LanNetwork network,CancellationToken cancel,bool persistent=false) {
        using var udp=new UdpClient(AddressFamily.InterNetwork); udp.EnableBroadcast=true; network.Bind(udp.Client);
        string prefix=persistent?"FLOATCLIP3":"FLOATCLIP2";
        string nonce=Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant(); byte[] query=Encoding.ASCII.GetBytes(prefix+"|"+nonce);
        using var deadline=CancellationTokenSource.CreateLinkedTokenSource(cancel); deadline.CancelAfter(3500);
        var found=new Dictionary<string,NearbyDevice>();
        var sender=Task.Run(async()=>{
            for(int i=0;i<3;i++) {
                await udp.SendAsync(query,new IPEndPoint(network.Broadcast,46240),deadline.Token);
                if(network.Gateway!=null && network.Contains(network.Gateway)) await udp.SendAsync(query,new IPEndPoint(network.Gateway,46240),deadline.Token);
                await Task.Delay(800,deadline.Token);
            }
        },deadline.Token);
        try {
            while(!deadline.IsCancellationRequested) {
                var answer=await udp.ReceiveAsync(deadline.Token);
                if(answer.Buffer.Length>512 || !network.Contains(answer.RemoteEndPoint.Address) || answer.RemoteEndPoint.Port!=46240) continue;
                string[] f=Encoding.ASCII.GetString(answer.Buffer).Split('|');
                if(f.Length!=6 || f[0]!=prefix || f[1]!=nonce || f[2]!="46239" || f[3].Length!=64 || f[4].Length!=32) continue;
                try {
                    Convert.FromHexString(f[3]); Convert.FromHexString(f[4]);
                    string name=Wire.Decode(Convert.FromBase64String(f[5])); if(name.Length is <1 or >40 || name.Any(char.IsControl)) continue;
                    var device=new NearbyDevice(name,answer.RemoteEndPoint.Address,f[3].ToLowerInvariant(),f[4],network); found[device.Address.ToString()]=device;
                } catch { }
            }
        } catch(OperationCanceledException) { }
        finally { deadline.Cancel(); try { await sender; } catch(OperationCanceledException) { } }
        cancel.ThrowIfCancellationRequested(); return found.Values.ToList();
    }
    public static string Fingerprint(string pin,byte[] id,byte[] client,byte[] server) {
        using var bytes=new MemoryStream();
        bytes.Write(Encoding.ASCII.GetBytes("FloatClip-Pair-v2")); bytes.Write(Encoding.ASCII.GetBytes(pin.ToLowerInvariant())); bytes.Write(id); bytes.Write(client); bytes.Write(server);
        string s=Convert.ToHexString(SHA256.HashData(bytes.ToArray()))[..16]; return s[..4]+" "+s[4..8]+" "+s[8..12]+" "+s[12..16];
    }
    public static Session Connect(NearbyDevice device,string cache,PairStore store,Func<string,bool> approve,CancellationToken cancel,Action<string>? trace=null,bool persistent=false) {
        if(!device.Network.Contains(device.Address)) throw new IOException("目标不在所选网络中");
        var tcp=new TcpClient(AddressFamily.InterNetwork){NoDelay=true};
        using var cancellation=cancel.Register(()=>tcp.Dispose());
        using var connection=CancellationTokenSource.CreateLinkedTokenSource(cancel); connection.CancelAfter(15000);
        try {
            device.Network.Bind(tcp.Client); tcp.ConnectAsync(device.Address,46239,connection.Token).AsTask().GetAwaiter().GetResult(); trace?.Invoke("tcp-connected");
            byte[] pin=Convert.FromHexString(device.Pin);
            var tls=new SslStream(tcp.GetStream(),false,(_,cert,_,_)=>cert!=null && CryptographicOperations.FixedTimeEquals(SHA256.HashData(cert.GetRawCertData()),pin));
            tls.ReadTimeout=10000; tls.WriteTimeout=10000; tls.AuthenticateAsClientAsync(new SslClientAuthenticationOptions{TargetHost="FloatClip LAN P0",EnabledSslProtocols=SslProtocols.Tls12,CertificateRevocationCheckMode=System.Security.Cryptography.X509Certificates.X509RevocationMode.NoCheck},connection.Token).GetAwaiter().GetResult(); connection.CancelAfter(Timeout.Infinite); trace?.Invoke("tls-authenticated");
            byte[] token=store.Get(device.Pin),client=RandomNumberGenerator.GetBytes(32),id=Convert.FromHexString(device.Id);
            using(var hello=new MemoryStream()) {
                Wire.WriteInt(hello,persistent?0x46435033:0x46435032,4); hello.Write(id); hello.WriteByte((byte)token.Length); hello.Write(token); hello.Write(client);
                byte[] packet=hello.ToArray(); tls.Write(packet,0,packet.Length); tls.Flush();
            }
            trace?.Invoke("hello-sent");
            int mode=tls.ReadByte(); trace?.Invoke("mode-"+mode);if(mode==3)throw new IOException("对端暂未允许连接，请在设备页开放连接");
            if(mode is not (1 or 2) || (mode==1 && token.Length!=32)) throw new PairingAuthorizationException("授权已失效，请移除记住的设备后重新配对");
            var server=Wire.ReadBytes(tls,32); trace?.Invoke("server-nonce-read"); tls.ReadTimeout=60000;
            bool accepted=mode==1 || approve(Fingerprint(device.Pin,id,client,server));
            cancel.ThrowIfCancellationRequested(); tls.WriteByte(accepted ? (byte)1 : (byte)0); tls.Flush(); trace?.Invoke("local-confirmed");
            if(!accepted || tls.ReadByte()!=1) throw new IOException("配对未确认，请在两端核对后重试");
            trace?.Invoke("remote-confirmed"); byte[] credential=Wire.ReadBytes(tls,32); trace?.Invoke("credential-read"); cancel.ThrowIfCancellationRequested(); store.Put(device.Pin,credential); trace?.Invoke("credential-saved");
            tls.ReadTimeout=180000;
            return new Session(tls,()=>tcp.Dispose(),cache,persistent);
        } catch(IOException) { tcp.Dispose(); throw; } catch { tcp.Dispose(); throw new IOException("连接失败，请检查所选网卡、手机发现状态和 TUN 流量排除"); }
    }
}
