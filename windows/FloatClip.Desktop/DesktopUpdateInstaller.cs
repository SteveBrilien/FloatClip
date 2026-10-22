using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Security.Cryptography;
using System.Text.Json;
using System.Threading.Tasks;
using FloatClip.Share;

namespace FloatClip.Desktop;

/** Runs only after the user chooses update and restart; no app data or pairing directories are replaced. */
public static class DesktopUpdateInstaller
{
    sealed record Plan(int ProcessId,string Target,string Staged,string Sha256,string ResultFile);
    public static async Task Prepare(UpdateRelease release,string package,string updateRoot)
    {
        await using var packageInput=File.OpenRead(package);
        if(release.Sha256.Length!=64||!CryptographicOperations.FixedTimeEquals(await SHA256.HashDataAsync(packageInput),Convert.FromHexString(release.Sha256)))throw new IOException("更新包校验失败，请重新下载");
        string current=Environment.ProcessPath??throw new IOException("无法定位当前应用");
        if(!Path.GetFileName(current).Equals("FloatClip.exe",StringComparison.OrdinalIgnoreCase))throw new IOException("请在安装版 FloatClip 中完成更新");
        string stage=Path.Combine(updateRoot,"Install-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(stage);
        using var archive=ZipFile.OpenRead(package);var entries=archive.Entries.Where(e=>e.Name.Equals("FloatClip.exe",StringComparison.OrdinalIgnoreCase)).ToList();
        if(entries.Count!=1||entries[0].Length is <100000 or >600L*1024*1024)throw new IOException("更新包不包含唯一的 FloatClip.exe");
        string staged=Path.Combine(stage,"FloatClip.new.exe");await using(var input=entries[0].Open())await using(var output=File.Create(staged)){byte[] buffer=new byte[65536];long total=0;int read;while((read=await input.ReadAsync(buffer))>0){total+=read;if(total>entries[0].Length)throw new IOException("更新程序超过声明大小");await output.WriteAsync(buffer.AsMemory(0,read));}}
        if(new FileInfo(staged).Length!=entries[0].Length)throw new IOException("更新解压未完成");
        var info=FileVersionInfo.GetVersionInfo(staged);if(info.ProductVersion?.Split('+')[0]!=release.Version&&info.FileVersion!=release.Version+".0")throw new IOException("更新程序版本与发布信息不一致");
        string directory=Path.GetDirectoryName(current)!,probe=Path.Combine(directory,".FloatClip-update-"+Guid.NewGuid().ToString("N")+".tmp");
        try{using(File.Create(probe)){}File.Delete(probe);}catch(UnauthorizedAccessException){throw new IOException("当前安装目录不可写，请将 FloatClip 放在个人可写目录后重试");}
        string helper=Path.Combine(stage,"FloatClip.Update.exe");File.Copy(current,helper);
        using var source=File.OpenRead(staged);string hash=Convert.ToHexString(await SHA256.HashDataAsync(source));
        var plan=new Plan(Environment.ProcessId,current,staged,hash,Path.Combine(updateRoot,"last-install.json"));
        string path=Path.Combine(stage,"plan.json");File.WriteAllText(path,JsonSerializer.Serialize(plan));
        var start=new ProcessStartInfo(helper){UseShellExecute=false,CreateNoWindow=true};start.ArgumentList.Add("--apply-update");start.ArgumentList.Add(path);
        _=Process.Start(start)??throw new IOException("无法启动更新助手");
    }
    public static int Apply(string path)=>ApplyInRoot(path,Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"FloatClip","Host","Updates"),true);
    internal static int ApplyInRoot(string path,string updateRoot,bool restart)
    {
        Plan? plan=null;bool replaced=false;
        try{
            string allowed=Path.GetFullPath(updateRoot)+Path.DirectorySeparatorChar;
            if(!Path.GetFullPath(path).StartsWith(allowed,StringComparison.OrdinalIgnoreCase))throw new IOException("更新计划路径无效");
            plan=JsonSerializer.Deserialize<Plan>(File.ReadAllText(path))??throw new IOException("更新计划无效");
            if(!Path.GetFullPath(plan.Staged).StartsWith(allowed,StringComparison.OrdinalIgnoreCase)||Path.GetFileName(plan.Target)!="FloatClip.exe")throw new IOException("更新目标无效");
            using(var file=File.OpenRead(plan.Staged))if(!CryptographicOperations.FixedTimeEquals(SHA256.HashData(file),Convert.FromHexString(plan.Sha256)))throw new IOException("更新程序校验失败");
            try{using var previous=Process.GetProcessById(plan.ProcessId);if(!previous.WaitForExit(30000))throw new IOException("请先退出正在运行的 FloatClip");}catch(ArgumentException){}
            string temporary=plan.Target+".new",backup=plan.Target+".previous";File.Copy(plan.Staged,temporary,true);
            File.Replace(temporary,plan.Target,backup,true);
            replaced=true;
            File.WriteAllText(plan.ResultFile,JsonSerializer.Serialize(new{ok=true,utc=DateTime.UtcNow}));
            if(restart)Process.Start(new ProcessStartInfo(plan.Target){UseShellExecute=true});return 0;
        }catch(Exception e){if(plan!=null)try{if(replaced&&File.Exists(plan.Target+".previous"))File.Copy(plan.Target+".previous",plan.Target,true);File.WriteAllText(plan.ResultFile,JsonSerializer.Serialize(new{ok=false,error=e.Message,utc=DateTime.UtcNow}));if(restart&&File.Exists(plan.Target))Process.Start(new ProcessStartInfo(plan.Target){UseShellExecute=true});}catch{}return 1;}
    }
}
