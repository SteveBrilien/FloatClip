using System;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Threading;
using System.Threading.Tasks;
using FloatClip.Share;
using Windows.Devices.Radios;
using Windows.Devices.WiFiDirect;
using Windows.Security.Credentials;

namespace FloatClip.Desktop;

/** Windows owns a temporary Wi-Fi Direct group. Android joins it as a local-only Wi-Fi client. */
internal sealed class WifiDirectTransferHost
{
    internal record Offer(string Ssid,string Password,int Port,string Address);
    int occupied;

    internal async Task<Session> Open(string cache,string peer,Func<byte[]?> credential,
        Action<Offer> announce,Action wifiOff,CancellationToken cancellation)
    {
        if(Interlocked.CompareExchange(ref occupied,1,0)!=0)throw new IOException("另一个 Wi-Fi 直连正在使用");
        WiFiDirectAdvertisementPublisher? publisher=null;
        TcpListener? listener=null;
        TcpClient? socket=null;
        bool ownedBySession=false;
        void Release()
        {
            socket?.Dispose();
            listener?.Stop();
            try{publisher?.Stop();}catch{ }
            Interlocked.Exchange(ref occupied,0);
        }
        try
        {
            cancellation.ThrowIfCancellationRequested();
            var radios=await Radio.GetRadiosAsync();
            var wifi=radios.Where(r=>r.Kind==RadioKind.WiFi).ToArray();
            if(wifi.Length>0&&wifi.All(r=>r.State!=RadioState.On))
            {
                wifiOff();throw new IOException("请在 Windows 上打开 Wi-Fi");
            }
            var before=IPv4Addresses();
            publisher=new WiFiDirectAdvertisementPublisher();
            var advertisement=publisher.Advertisement;
            advertisement.IsAutonomousGroupOwnerEnabled=true;
            advertisement.LegacySettings.IsEnabled=true;
            string ssid="DIRECT-FC-"+Convert.ToHexString(RandomNumberGenerator.GetBytes(4));
            string password=Convert.ToHexString(RandomNumberGenerator.GetBytes(16));
            advertisement.LegacySettings.Ssid=ssid;
            advertisement.LegacySettings.Passphrase=new PasswordCredential{Password=password};
            var started=new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            publisher.StatusChanged+=(_,status)=>
            {
                if(status.Status==WiFiDirectAdvertisementPublisherStatus.Started)started.TrySetResult();
                if(status.Status==WiFiDirectAdvertisementPublisherStatus.Aborted)
                    started.TrySetException(new IOException("Windows 无法创建 Wi-Fi 直连"));
            };
            publisher.Start();
            if(publisher.Status==WiFiDirectAdvertisementPublisherStatus.Started)started.TrySetResult();
            await started.Task.WaitAsync(TimeSpan.FromSeconds(8),cancellation);
            // The DHCP address can appear after publisher start. A joining client can also use its gateway.
            var address=IPv4Addresses().Except(before).FirstOrDefault(IsPrivate);
            listener=new TcpListener(address??IPAddress.Any,0);
            listener.Start(2);
            announce(new(ssid,password,((IPEndPoint)listener.LocalEndpoint).Port,address?.ToString()??""));
            while(true)
            {
                socket=await listener.AcceptTcpClientAsync(cancellation);
                socket.ReceiveTimeout=5000;socket.SendTimeout=5000;socket.NoDelay=true;
                using var abort=cancellation.Register(socket.Dispose);
                try
                {
                    var secure=await Task.Run(()=>TransportCrypto.Accept(socket.GetStream(),key=>key==peer?credential():null),cancellation);
                    cancellation.ThrowIfCancellationRequested();
                    socket.ReceiveTimeout=180000;socket.SendTimeout=30000;
                    listener.Stop();listener=null;
                    var session=new Session(secure.Stream,Release,cache,true,true);
                    session.EnableLargeFiles();ownedBySession=true;
                    return session;
                }
                catch(Exception e)when(e is IOException or SocketException or CryptographicException)
                {
                    socket.Dispose();socket=null;
                }
            }
        }
        finally{if(!ownedBySession)Release();}
    }

    static IPAddress[] IPv4Addresses()=>NetworkInterface.GetAllNetworkInterfaces()
        .SelectMany(adapter=>adapter.GetIPProperties().UnicastAddresses)
        .Select(address=>address.Address).Where(address=>address.AddressFamily==AddressFamily.InterNetwork).ToArray();
    static bool IsPrivate(IPAddress address)
    {
        byte[] b=address.GetAddressBytes();
        return b[0]==10||b[0]==172&&b[1] is >=16 and <=31||b[0]==192&&b[1]==168;
    }
}
