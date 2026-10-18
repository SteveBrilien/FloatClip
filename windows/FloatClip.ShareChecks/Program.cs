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
Console.WriteLine("PASS Share C#: "+checks+" checks; Java/C# frame and file-offer bytes match");
