using System;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Diagnostics;
using System.Collections.Concurrent;
using System.Threading;
using FloatClip.Share;

static class SocketChecks
{
    public static void Run()
    {
        const string key="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        string cache=Path.Combine(Path.GetTempPath(),"floatclip-socket-"+Guid.NewGuid());Directory.CreateDirectory(cache);
        var listener=new TcpListener(IPAddress.Loopback,0);listener.Start();Session? active=null;byte[]? saved=null;
        var received=new ConcurrentDictionary<Guid,SyncEntry>();var errors=new ConcurrentQueue<string>();
        var core=new ClipboardSyncEngine(new(Guid.Parse("00000000-0000-0000-0000-000000000002"),"C# desktop","windows"),()=>saved,b=>saved=b,(k,f)=>{if(active!=null)active.SendSync(f);},e=>received.TryAdd(e.Id,e),_=>{});
        core.Configure(true,true,true,true);
        var start=new ProcessStartInfo(".toolchains/jdk17/bin/java"){RedirectStandardOutput=true,RedirectStandardError=true};
        foreach(string arg in new[]{"-cp","analysis/share-p1/core-checks/classes","com.floatclip.share.ClipboardSyncSocketPeer",((IPEndPoint)listener.LocalEndpoint).Port.ToString(),cache})start.ArgumentList.Add(arg);
        using var java=Process.Start(start)??throw new IOException("Cannot start Java peer");
        void Until(Func<bool> condition){var timer=Stopwatch.StartNew();while(!condition()){if(timer.Elapsed.TotalSeconds>12)throw new IOException("Loopback sync timed out: "+string.Join(",",errors));Thread.Sleep(10);}}
        try{
            for(int round=1;round<=2;round++){
                var accepted=listener.AcceptTcpClientAsync();if(!accepted.Wait(12000))throw new IOException("No Java peer");var client=accepted.Result;client.NoDelay=true;
                active=new Session(client.GetStream(),client.Dispose,cache,true);active.Sync=f=>{try{core.Accept(key,f,DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());}catch(Exception e){errors.Enqueue(e.Message);}};
                active.Failure=e=>errors.Enqueue(e);active.Closed=()=>core.Disconnected(key);core.Connected(key);active.Start();
                Until(()=>core.Snapshot().Peers[key].Device!=null);
                if(round==1)core.Copied("C# first 中文",DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
                Until(()=>received.Count==round);Until(()=>core.Snapshot().Peers[key].Pending==null);
                if(!received.Values.Any(e=>e.Text=="Java reply "+round+" 📋"))throw new IOException("Cross-language reply mismatch");
                active.Dispose();active=null;core.Disconnected(key);
                if(round==1){core.Copied("offline old",DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());core.Copied("offline latest",DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());}
            }
            if(!java.WaitForExit(12000))throw new IOException("Java peer did not exit");
            string output=java.StandardOutput.ReadToEnd(),error=java.StandardError.ReadToEnd();if(java.ExitCode!=0||!output.Contains("PASS")||!errors.IsEmpty)throw new IOException(output+error+string.Join(",",errors));
            Console.WriteLine(output.Trim());Console.WriteLine("PASS C# ↔ Java loopback sessions: exact text, ACK, disconnect, restart and latest-only reconnect");
        }finally{active?.Dispose();listener.Stop();if(!java.HasExited)java.Kill(true);Directory.Delete(cache,true);}
    }
}
