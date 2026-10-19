using System;
using System.IO;
using System.Net.Http;
using System.Text.Json;
using System.Threading.Tasks;

namespace FloatClip.Share;
public sealed record UpdateRelease(int Build,string Version,string Notes,string Url);
public static class ShareUpdates
{
    public const int Build=8;
    public const string Version="0.4.0",Feed="https://raw.githubusercontent.com/SteveBrilien/FloatClip/main/updates/share-preview.json";
    public static async Task<UpdateRelease> Check() {
        using var http=new HttpClient{Timeout=TimeSpan.FromSeconds(15)};
        http.DefaultRequestHeaders.UserAgent.ParseAdd("FloatClip-Share/"+Version);
        using var response=await http.GetAsync(Feed,HttpCompletionOption.ResponseHeadersRead); response.EnsureSuccessStatusCode();
        using var input=await response.Content.ReadAsStreamAsync(); using var data=new MemoryStream();
        byte[] buffer=new byte[8192]; int n;
        while((n=await input.ReadAsync(buffer))!=0) { if(data.Length+n>65536) throw new IOException("更新信息过大"); data.Write(buffer,0,n); }
        return Parse(data.ToArray());
    }
    public static UpdateRelease Parse(byte[] data) {
        using var document=JsonDocument.Parse(data); var json=document.RootElement;
        if(json.GetProperty("schema").GetInt32()!=1 || json.GetProperty("channel").GetString()!="share-preview") throw new IOException("更新源不匹配");
        var platform=json.GetProperty("windows"); string url=platform.GetProperty("url").GetString()!;
        if(!Uri.TryCreate(url,UriKind.Absolute,out var uri) || uri.Scheme!="https" || uri.UserInfo!="" || !uri.IsDefaultPort || uri.Host is not ("mcp.wmy-cloud.cn" or "github.com")) throw new IOException("下载地址不受支持");
        string version=json.GetProperty("version").GetString()!; int build=platform.GetProperty("build").GetInt32();
        if(build<1 || !System.Text.RegularExpressions.Regex.IsMatch(version,@"^[0-9]+\.[0-9]+\.[0-9]+$")) throw new IOException("版本信息无效");
        string notes=json.GetProperty("notes").GetString()??""; return new(build,version,notes[..Math.Min(notes.Length,1200)],url);
    }
}
