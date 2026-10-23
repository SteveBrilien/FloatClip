using System;
using System.IO;
using System.Net.Http;
using System.Text.Json;
using System.Threading.Tasks;
using System.Threading;
using System.Security.Cryptography;
using System.Text.RegularExpressions;
using System.Collections.Generic;

namespace FloatClip.Share;
public sealed record UpdateRelease(int Build,string Version,string Notes,string Url,string Sha256="",long Bytes=0);
public static class ShareUpdates
{
    public const int Build=25;
    public const string Version="0.7.8",Feed="https://clip.wmy-cloud.cn/release.json";
    public const string FallbackFeed="https://raw.githubusercontent.com/SteveBrilien/FloatClip/main/updates/native-updates.json";
    sealed class SourceUnavailable(string message,Exception? inner=null):IOException(message,inner);
    public static async Task<UpdateRelease> Check(CancellationToken cancellation=default)
    {
        using var http=UpdateHttpClient.Create(Timeout.InfiniteTimeSpan);
        http.DefaultRequestHeaders.UserAgent.ParseAdd("FloatClip-Windows/"+Version);
        return await CheckWithClient(http,cancellation);
    }
    internal static async Task<UpdateRelease> CheckWithClient(HttpClient http,CancellationToken cancellation=default,Func<byte[],byte[]>? verify=null)
    {
        var failures=new List<string>();
        foreach(string source in new[]{Feed,FallbackFeed})
        {
            byte[] content;
            try{content=await Fetch(http,source,cancellation);}
            catch(SourceUnavailable error){failures.Add(error.Message);continue;}
            // Verification failures are never hidden by switching to another source.
            return Parse(verify==null?UpdateManifestVerifier.Verify(content):verify(content));
        }
        throw new IOException("所有更新源暂不可用："+string.Join("；",failures));
    }
    static async Task<byte[]> Fetch(HttpClient http,string source,CancellationToken cancellation)
    {
        string label=source==Feed?"FloatClip 公网更新源":"GitHub 备用更新源";
        using var timeout=CancellationTokenSource.CreateLinkedTokenSource(cancellation);timeout.CancelAfter(TimeSpan.FromSeconds(12));
        try
        {
            using var request=new HttpRequestMessage(HttpMethod.Get,source);
            request.Headers.CacheControl=new System.Net.Http.Headers.CacheControlHeaderValue{NoCache=true};
            using var response=await http.SendAsync(request,HttpCompletionOption.ResponseHeadersRead,timeout.Token);
            if(!response.IsSuccessStatusCode)throw new SourceUnavailable(label+"返回 HTTP "+(int)response.StatusCode);
            var expected=new Uri(source);var final=response.RequestMessage?.RequestUri;
            if(final==null||final.Scheme!="https"||final.UserInfo.Length!=0||!final.IsDefaultPort||final.Host!=expected.Host||final.AbsolutePath!=expected.AbsolutePath)throw new IOException("更新源跳转地址不受支持");
            using var input=await response.Content.ReadAsStreamAsync(timeout.Token);using var data=new MemoryStream();var buffer=new byte[8192];int length;
            while((length=await input.ReadAsync(buffer,timeout.Token))!=0){if(data.Length+length>131072)throw new IOException("更新信息过大");data.Write(buffer,0,length);}
            return data.ToArray();
        }
        catch(OperationCanceledException error)when(!cancellation.IsCancellationRequested){throw new SourceUnavailable(label+"连接超时",error);}
        catch(HttpRequestException error){throw new SourceUnavailable("无法连接"+label+"，请检查网络或证书连接",error);}
    }
    public static UpdateRelease Parse(byte[] data) {
        if(data.Length>65536)throw new IOException("更新信息过大");
        try {
        using var document=JsonDocument.Parse(data); var json=document.RootElement;
        if(json.GetProperty("schema").GetInt32()!=1 || json.GetProperty("channel").GetString()!="share-preview") throw new IOException("更新源不匹配");
        var platform=json.GetProperty("windows"); string url=platform.GetProperty("url").GetString()!;
        if(!AllowedDownload(url,false))throw new IOException("下载地址不受支持");
        if(platform.TryGetProperty("status",out var status)&&status.GetString()!="available")throw new IOException("Windows 更新包尚未发布");
        string version=platform.TryGetProperty("version",out var v)?v.GetString()!:json.GetProperty("version").GetString()!; int build=platform.GetProperty("build").GetInt32();
        if(build<1 || !System.Text.RegularExpressions.Regex.IsMatch(version,@"^[0-9]+\.[0-9]+\.[0-9]+$")) throw new IOException("版本信息无效");
        string notes=json.TryGetProperty("notes",out var note)?note.GetString()??"":"";
        string hash=platform.TryGetProperty("sha256",out var digest)?digest.GetString()??"":"";
        long size=platform.TryGetProperty("bytes",out var length)?length.GetInt64():0;
        if(hash.Length>0&&!Regex.IsMatch(hash,"^[a-fA-F0-9]{64}$")||size<0||size>400L*1024*1024)throw new IOException("更新包校验信息无效");
        return new(build,version,notes[..Math.Min(notes.Length,1200)],url,hash.ToLowerInvariant(),size);
        }catch(Exception e)when(e is JsonException or InvalidOperationException or KeyNotFoundException or FormatException){throw new IOException("更新信息格式无效",e);}
    }
    public static bool AllowedDownload(string url,bool redirect)=>Uri.TryCreate(url,UriKind.Absolute,out var uri)&&uri.Scheme=="https"&&uri.UserInfo.Length==0&&uri.IsDefaultPort&&(uri.Host is "github.com" or "mcp.wmy-cloud.cn"||redirect&&uri.Host=="release-assets.githubusercontent.com");
    // An unreleased local candidate must never be offered an older public package.
    public static int Compare(UpdateRelease release)
    {
        int version=System.Version.Parse(release.Version).CompareTo(System.Version.Parse(Version));
        if(version<0||release.Build<Build)return -1;
        return version>0||release.Build>Build?1:0;
    }
    public static async Task<string> Download(UpdateRelease release,string directory,IProgress<double>? progress,CancellationToken cancellation)
    {
        if(release.Sha256.Length!=64||release.Bytes<1)throw new IOException("更新源缺少校验信息，请稍后重试");
        if(!Regex.IsMatch(release.Version,@"^[0-9]+\.[0-9]+\.[0-9]+$")||release.Build<1||release.Bytes>400L*1024*1024)throw new IOException("更新包信息无效");
        string cached=Path.Combine(directory,"FloatClip-"+release.Version+"-"+release.Build+".zip");
        if(File.Exists(cached)&&new FileInfo(cached).Length==release.Bytes){await using var cache=File.OpenRead(cached);if(CryptographicOperations.FixedTimeEquals(await SHA256.HashDataAsync(cache,cancellation),Convert.FromHexString(release.Sha256)))return cached;}
        using var http=UpdateHttpClient.Create(TimeSpan.FromMinutes(10));
        http.DefaultRequestHeaders.UserAgent.ParseAdd("FloatClip-Windows/"+Version);
        {
            string url=release.Url;HttpResponseMessage? response=null;
            for(int hop=0;hop<6;hop++){
                if(!AllowedDownload(url,hop>0))throw new IOException("更新重定向地址不受支持");
                response=await http.GetAsync(url,HttpCompletionOption.ResponseHeadersRead,cancellation);
                int code=(int)response.StatusCode;
                if(code is not(301 or 302 or 303 or 307 or 308))break;
                var location=response.Headers.Location;response.Dispose();response=null;
                if(location==null)throw new IOException("更新下载地址无效");url=new Uri(new Uri(url),location).AbsoluteUri;
            }
            using var accepted=response??throw new IOException("更新重定向次数过多");accepted.EnsureSuccessStatusCode();
            if(accepted.Content.Headers.ContentLength is long count&&count!=release.Bytes)throw new IOException("更新包大小与发布信息不一致");
            using var input=await accepted.Content.ReadAsStreamAsync(cancellation);
            return await ReceivePackage(release,input,directory,progress,cancellation);
        }
    }
    public static async Task<string> ReceivePackage(UpdateRelease release,Stream input,string directory,IProgress<double>? progress,CancellationToken cancellation)
    {
        if(!Regex.IsMatch(release.Version,@"^[0-9]+\.[0-9]+\.[0-9]+$")||!Regex.IsMatch(release.Sha256,"^[a-fA-F0-9]{64}$")||release.Build<1||release.Bytes<1||release.Bytes>400L*1024*1024)throw new IOException("更新包信息无效");
        Directory.CreateDirectory(directory);string path=Path.Combine(directory,"FloatClip-"+release.Version+"-"+release.Build+".zip"),temporary=path+"."+Guid.NewGuid().ToString("N")+".partial";
        try {
            using(var file=new FileStream(temporary,FileMode.CreateNew,FileAccess.Write,FileShare.None,65536,true)){
                using var hash=IncrementalHash.CreateHash(HashAlgorithmName.SHA256);byte[] buffer=new byte[65536];long total=0;int read;
                while((read=await input.ReadAsync(buffer,cancellation))>0){total+=read;if(total>release.Bytes)throw new IOException("更新包超过发布大小");hash.AppendData(buffer,0,read);await file.WriteAsync(buffer.AsMemory(0,read),cancellation);progress?.Report(total*100.0/release.Bytes);}
                if(total!=release.Bytes||!CryptographicOperations.FixedTimeEquals(hash.GetHashAndReset(),Convert.FromHexString(release.Sha256)))throw new IOException("更新包校验失败，请重新下载");
                await file.FlushAsync(cancellation);file.Flush(true);
            }
            File.Move(temporary,path,true);return path;
        }finally{if(File.Exists(temporary))File.Delete(temporary);}
    }
}
