using System;
using System.IO;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text.Json;
using System.Threading;
using FloatClip.Share;

public static class CoreInterop
{
    public static void Run(int port,string output)
    {
        string temp=Path.Combine(Path.GetTempPath(),"FloatClip-core-"+Guid.NewGuid()); Directory.CreateDirectory(temp);
        using var done=new CountdownEvent(5); int texts=0; bool rejected=false; string? error=null;
        byte[] bytes=new byte[1048593]; for(int i=0;i<bytes.Length;i++) bytes[i]=(byte)(i*31+7); byte[] hash=SHA256.HashData(bytes);
        string source=Path.Combine(temp,"FloatClip-Windows.bin"),refuse=Path.Combine(temp,"FloatClip-reject.bin"); File.WriteAllBytes(source,bytes);File.WriteAllBytes(refuse,Array.Empty<byte>());
        try {
            using var tcp=new TcpClient("127.0.0.1",port); using var session=new Session(tcp.GetStream(),tcp.Close,Path.Combine(temp,"cache"));
            session.Text=(text,outgoing)=>{ if(text!="FloatClip-"+(outgoing?"Windows":"Android")+"\n中文 📋\n\tcode  \r\n") { error="text";session.Dispose();return; } done.Signal();if(Interlocked.Increment(ref texts)==2) session.SendFile(refuse); };
            session.Offered=offer=>{if(offer.Name!="FloatClip-Android.bin" || offer.Size!=bytes.Length || !CryptographicOperations.FixedTimeEquals(offer.Hash,hash)) {error="offer";session.Dispose();return;}session.Accept(offer.Id);};
            session.Notice=message=>{if(message=="对方未接收文件" && !rejected){rejected=true;done.Signal();session.SendFile(source);}};
            session.File=(offer,path,outgoing)=>{if(!CryptographicOperations.FixedTimeEquals(SHA256.HashData(File.ReadAllBytes(path)),hash)){error="file";session.Dispose();return;} done.Signal();};
            session.Start();session.SendText("FloatClip-Windows\n中文 📋\n\tcode  \r\n");
            bool pass=done.Wait(60000) && error==null && rejected;
            File.WriteAllText(output,JsonSerializer.Serialize(new {result=pass?"PASS":"FAIL",scope="Java/C# loopback protocol; no TLS or device UI",textDirections=texts,fileBytesEach=bytes.Length,sha256=Convert.ToHexString(hash).ToLowerInvariant(),rejected,error},new JsonSerializerOptions{WriteIndented=true}));
            if(!pass) throw new Exception("Interop failed"); Console.WriteLine("PASS C#/JAVA: two-way Unicode text; two-way 1048593-byte files; refusal");
        }finally{try{Directory.Delete(temp,true);}catch{}}
    }
}
