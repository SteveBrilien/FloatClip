using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Xml;
using System.Xml.Linq;

namespace FloatClip.Desktop.Services;

public sealed record ArchivePreviewEntry(string Name,string Size,string Modified);
public sealed record DocumentPreview(string Description,string Text,IReadOnlyList<ArchivePreviewEntry>? Entries=null);
public static class DocumentPreviewService
{
    const int MaxText=24000,MaxXml=2*1024*1024,MaxEntries=1000;
    public static bool CanPreview(string path)=>Path.GetExtension(path).ToLowerInvariant() is
        ".pdf" or ".png" or ".jpg" or ".jpeg" or ".bmp" or ".gif" or ".webp" or
        ".zip" or ".docx" or ".xlsx" or ".pptx" or ".txt" or ".md" or ".csv" or ".json" or ".log" or ".xml" or ".yaml" or ".yml" or ".cs" or ".py" or ".js" or ".ts" or ".ini";
    public static Task<DocumentPreview> ReadAsync(string path,CancellationToken token)=>Task.Run(()=>Read(path,token),token);
    internal static DocumentPreview Read(string path,CancellationToken token)
    {
        token.ThrowIfCancellationRequested();string ext=Path.GetExtension(path).ToLowerInvariant();
        if(ext==".zip")
        {
            using var zip=ZipFile.OpenRead(path);
            var entries=zip.Entries.Take(MaxEntries).Select(e=>{token.ThrowIfCancellationRequested();return new ArchivePreviewEntry(e.FullName,e.FullName.EndsWith('/')?"文件夹":FileSizeFormatter.Format(e.Length),e.LastWriteTime.ToLocalTime().ToString("yyyy-MM-dd HH:mm"));}).ToArray();
            return new($"压缩包内容  |  {zip.Entries.Count} 项"+(zip.Entries.Count>MaxEntries?$"  |  显示前 {MaxEntries} 项":""),"",entries);
        }
        if(ext is ".docx" or ".xlsx" or ".pptx")
        {
            using var zip=ZipFile.OpenRead(path);var text=new StringBuilder();
            if(ext==".docx")
            {
                var xml=Xml(zip,"word/document.xml");
                foreach(var paragraph in xml.Descendants().Where(n=>n.Name.LocalName=="p"))
                {
                    token.ThrowIfCancellationRequested();
                    text.AppendLine(string.Concat(paragraph.Descendants().Select(n=>n.Name.LocalName switch{"t"=>n.Value,"tab"=>"\t","br" or "cr"=>"\n",_=>""})));
                    if(text.Length>=MaxText)break;
                }
                return TextResult("Word 文字预览",text.ToString());
            }
            if(ext==".pptx")
            {
                var slides=zip.Entries.Where(e=>System.Text.RegularExpressions.Regex.IsMatch(e.FullName,@"^ppt/slides/slide\d+\.xml$")).OrderBy(e=>e.FullName.Length).ThenBy(e=>e.FullName,StringComparer.Ordinal).Take(30);
                int page=0;foreach(var slide in slides){token.ThrowIfCancellationRequested();text.AppendLine($"第 {++page} 页");foreach(var p in Xml(zip,slide.FullName).Descendants().Where(n=>n.Name.LocalName=="p"))text.AppendLine(string.Concat(p.Descendants().Where(n=>n.Name.LocalName=="t").Select(n=>n.Value)));if(text.Length>=MaxText)break;}
                return TextResult("PowerPoint 文字预览",text.ToString());
            }
            var shared=zip.GetEntry("xl/sharedStrings.xml")!=null?Xml(zip,"xl/sharedStrings.xml").Descendants().Where(n=>n.Name.LocalName=="si").Take(12000).Select(n=>string.Concat(n.Descendants().Where(t=>t.Name.LocalName=="t").Select(t=>t.Value))).ToArray():Array.Empty<string>();
            var sheets=Xml(zip,"xl/workbook.xml").Descendants().Where(n=>n.Name.LocalName=="sheet").Take(8).ToArray();
            var relationships=Xml(zip,"xl/_rels/workbook.xml.rels").Descendants().Where(n=>n.Name.LocalName=="Relationship").Where(n=>(string?)n.Attribute("TargetMode")!="External").ToDictionary(n=>(string)n.Attribute("Id")!,n=>(string)n.Attribute("Target")!);
            foreach(var sheet in sheets)
            {
                token.ThrowIfCancellationRequested();string id=sheet.Attributes().FirstOrDefault(a=>a.Name.LocalName=="id")?.Value??"";
                if(!relationships.TryGetValue(id,out string? target))continue;
                string entry=target.StartsWith('/')?target.TrimStart('/'):"xl/"+target;
                if(entry.Contains(".."))continue;
                text.AppendLine((string?)sheet.Attribute("name")??"工作表");
                foreach(var row in Xml(zip,entry).Descendants().Where(n=>n.Name.LocalName=="row").Take(100))
                {
                    token.ThrowIfCancellationRequested();
                    text.AppendLine(string.Join("    ",row.Elements().Where(n=>n.Name.LocalName=="c").Take(40).Select(cell=>{
                        string value=cell.Elements().FirstOrDefault(n=>n.Name.LocalName=="v")?.Value??string.Concat(cell.Descendants().Where(n=>n.Name.LocalName=="t").Select(n=>n.Value));
                        if((string?)cell.Attribute("t")=="s"&&int.TryParse(value,out int index)&&index>=0&&index<shared.Length)value=shared[index];
                        return ((string?)cell.Attribute("r")??"")+": "+value;
                    })));
                    if(text.Length>=MaxText)break;
                }
                if(text.Length>=MaxText)break;
            }
            return TextResult("Excel 单元格预览",text.ToString());
        }
        using var reader=new StreamReader(path,Encoding.UTF8,true);var buffer=new char[MaxText+1];int length=0;
        while(length<buffer.Length){token.ThrowIfCancellationRequested();int read=reader.Read(buffer,length,buffer.Length-length);if(read==0)break;length+=read;}
        return TextResult("文字预览",new string(buffer,0,length));
    }
    static DocumentPreview TextResult(string kind,string text)=>new(kind+"  |  只读"+(text.Length>MaxText?"  |  已截取前 24000 字符":""),text[..Math.Min(MaxText,text.Length)]);
    static XDocument Xml(ZipArchive zip,string name)
    {
        var entry=zip.GetEntry(name)??throw new IOException("文档内容不完整");if(entry.Length>MaxXml)throw new IOException("文档内容较大，请用默认应用查看");
        using var stream=entry.Open();using var reader=XmlReader.Create(stream,new XmlReaderSettings{DtdProcessing=DtdProcessing.Prohibit,XmlResolver=null,MaxCharactersInDocument=MaxXml});return XDocument.Load(reader);
    }
}
