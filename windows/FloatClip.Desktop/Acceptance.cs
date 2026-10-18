using System;
using System.IO;
using System.Security.Cryptography;
using System.Text.Json;
using System.Threading;
using FloatClip.Share;

namespace FloatClip.Desktop;
public static class Acceptance
{
    const string AndroidText="FloatClip-Android\n中文 📋\n\tcode  \r\n";
    const string WindowsText="FloatClip-Windows\n中文 📋\n\tcode  \r\n";
    public static void Run(string invite,string output)=>RunWith(cache=>LanClient.Connect(invite,cache),output);
    public static void RunNearby(string address,string output,string qaRoot)
    {
        Directory.CreateDirectory(qaRoot); string code="";
        var network=Nearby.Networks().Find(n=>n.Contains(System.Net.IPAddress.Parse(address))) ?? throw new IOException("No selected physical network");
        var found=Nearby.Discover(network,CancellationToken.None).GetAwaiter().GetResult();
        var device=found.Find(d=>d.Address.ToString()==address) ?? throw new IOException("Exact QA device not discovered");
        var store=new PairStore(qaRoot); System.IO.File.WriteAllText(output+".connect.log", "");
        RunWith(cache=>Nearby.Connect(device,cache,store,value=>{ code=value; return true; },CancellationToken.None,value=>System.IO.File.AppendAllText(output+".connect.log",DateTime.UtcNow.ToString("O")+" "+value+"\n")),output);
        var details=System.Text.Json.Nodes.JsonNode.Parse(System.IO.File.ReadAllText(output))!;
        details["verificationCode"]=code; details["rememberedReconnect"]=code==""; details["network"]=network.Name;
        System.IO.File.WriteAllText(output,details.ToJsonString(new JsonSerializerOptions{WriteIndented=true}));
    }
    static void RunWith(Func<string,Session> connect,string output)
    {
        string temp=Path.Combine(Path.GetTempPath(),"FloatClip-QA-"+Guid.NewGuid()); Directory.CreateDirectory(temp);
        using var finished=new CountdownEvent(5); int texts=0,started=0; bool rejected=false,received=false,sent=false; string? error=null;
        var trail=new System.Collections.Concurrent.ConcurrentQueue<string>();
        void Mark(string value) { trail.Enqueue(value); }
        byte[] bytes=new byte[1048593]; for(int i=0;i<bytes.Length;i++) bytes[i]=(byte)(i*31+7);
        byte[] expected=SHA256.HashData(bytes); string source=Path.Combine(temp,"FloatClip-Windows.bin"),refused=Path.Combine(temp,"FloatClip-reject.bin");
        System.IO.File.WriteAllBytes(source,bytes); System.IO.File.WriteAllBytes(refused,Array.Empty<byte>());
        try {
            using var session=connect(Path.Combine(temp,"Cache")); Mark("connected");
            session.Text=(value,outgoing)=>{ Mark(outgoing ? "text-ack" : "text-received");
                if(value!=(outgoing ? WindowsText : AndroidText)) { error="Text mismatch"; session.Dispose(); return; }
                finished.Signal();
                if(Interlocked.Increment(ref texts)==2 && Interlocked.Exchange(ref started,1)==0) session.SendFile(refused);
            };
            session.Offered=offer=>{ Mark("file-offer");
                if(offer.Name!="FloatClip-Android.bin" || offer.Size!=bytes.Length || !CryptographicOperations.FixedTimeEquals(offer.Hash,expected)) { error="Unexpected generated file offer"; session.Dispose(); return; }
                session.Accept(offer.Id);
            };
            session.Notice=message=>{ Mark(message);
                if(message=="对方未接收文件" && !rejected) { rejected=true; finished.Signal(); session.SendFile(source); }
            };
            session.File=(offer,path,outgoing)=>{ Mark(outgoing ? "file-ack" : "file-received");
                if(!CryptographicOperations.FixedTimeEquals(SHA256.HashData(System.IO.File.ReadAllBytes(path)),expected)) { error="File mismatch"; session.Dispose(); return; }
                if(outgoing) sent=true; else received=true; finished.Signal();
            };
            session.Failure=message=>Mark("failure: "+message); session.Closed=()=>Mark("closed"); session.Start(); session.SendText(WindowsText); Mark("text-sent");
            bool pass=finished.Wait(60000) && error==null && rejected && received && sent;
            System.IO.File.WriteAllText(output,JsonSerializer.Serialize(new {utc=DateTime.UtcNow,result=pass ? "PASS" : "FAIL",textDirections=texts,fileBytesEach=bytes.Length,sha256=Convert.ToHexString(expected).ToLowerInvariant(),fileSent=sent,fileReceived=received,rejection=rejected,error,trail=trail.ToArray()},new JsonSerializerOptions{WriteIndented=true}));
            if(!pass) throw new IOException("Generated-data acceptance failed");
        } finally { try { Directory.Delete(temp,true); } catch { } }
    }
}
