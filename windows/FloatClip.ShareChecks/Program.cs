using System;
using System.IO;
using System.Text;
using FloatClip.Share;

if(args.Length==3 && args[0]=="--interop") { CoreInterop.Run(int.Parse(args[1]),args[2]); return; }
int checks=0;
void Yes(bool value) { if(!value) throw new Exception("Check failed "+checks); checks++; }
byte[] Bytes(Frame f) { using var stream=new MemoryStream(); Wire.Write(stream,f); return stream.ToArray(); }
void Reject(Action action) { bool rejected=false; try{action();}catch(IOException){rejected=true;}catch(OverflowException){rejected=true;} Yes(rejected); }
Guid id=Guid.Parse("00112233-4455-6677-8899-aabbccddeeff"); byte[] data=Encoding.UTF8.GetBytes("FloatClip Share\n 中文 📋\n\tcode  \r\n");
byte[] bytes=Bytes(new(Wire.Text,id,data)); Yes(bytes.AsSpan().SequenceEqual(File.ReadAllBytes(Path.Combine(args[0],"share-text.bin"))));
var offer=new Offer(id,"生成文件.bin",data.Length,Wire.Digest(data)); Yes(Bytes(new(Wire.FileOffer,id,Wire.EncodeOffer(offer))).AsSpan().SequenceEqual(File.ReadAllBytes(Path.Combine(args[0],"share-offer.bin"))));
for(int n=1;n<bytes.Length;n++) { byte[] prefix=bytes[..n]; Reject(()=>Wire.Read(new MemoryStream(prefix))); }
byte[] bad=(byte[])bytes.Clone(); bad[^1]^=1; Reject(()=>Wire.Read(new MemoryStream(bad)));
foreach(string name in new[]{"../x","a/b","C:\\a","CON.txt","a.","a ","..","a\0b"}) Reject(()=>Wire.SafeName(name));
var network=new LanNetwork("test",System.Net.IPAddress.Parse("192.168.246.159"),24,2,System.Net.IPAddress.Parse("192.168.246.144"));
Yes(network.Subnet=="192.168.246.0/24"); Yes(network.Broadcast.ToString()=="192.168.246.255");
Yes(network.Contains(System.Net.IPAddress.Parse("192.168.246.1"))); Yes(network.Contains(System.Net.IPAddress.Parse("192.168.246.250")));
Yes(!network.Contains(System.Net.IPAddress.Parse("192.168.245.1"))); Yes(!network.Contains(System.Net.IPAddress.Parse("198.18.0.1")));
byte[] clientNonce=new byte[32],serverNonce=new byte[32],identity=new byte[16];
for(int i=0;i<32;i++) { clientNonce[i]=(byte)i; serverNonce[i]=(byte)(i+32); if(i<16) identity[i]=(byte)i; }
Yes(Nearby.Fingerprint(new string('a',64),identity,clientNonce,serverNonce)=="8067 FAAD F0E2 7FA0");
byte[] Feed(string url)=>Encoding.UTF8.GetBytes(System.Text.Json.JsonSerializer.Serialize(new {schema=1,channel="share-preview",version="0.3.0",notes="test",windows=new {build=4,url}}));
Yes(ShareUpdates.Parse(Feed("https://mcp.wmy-cloud.cn/artifacts/test.zip")).Build==4);
foreach(string url in new[]{"http://mcp.wmy-cloud.cn/a.zip","https://evil.example/a.zip","https://github.com@evil.example/a","https://github.com:444/a"}) Reject(()=>ShareUpdates.Parse(Feed(url)));
using(var countWrites=new WriteCounter()) { Wire.Write(countWrites,new(Wire.Text,id,data)); Yes(countWrites.Calls==1); }
Console.WriteLine("PASS Share C#: "+checks+" checks; Java/C# frame and file-offer bytes match");

sealed class WriteCounter : Stream {
    public int Calls; public override bool CanRead=>false; public override bool CanSeek=>false; public override bool CanWrite=>true;
    public override long Length=>0; public override long Position { get=>0; set=>throw new NotSupportedException(); }
    public override void Flush() { } public override int Read(byte[] b,int o,int n)=>throw new NotSupportedException();
    public override long Seek(long o,SeekOrigin origin)=>throw new NotSupportedException(); public override void SetLength(long value)=>throw new NotSupportedException();
    public override void Write(byte[] b,int o,int n) { Calls++; }
}
