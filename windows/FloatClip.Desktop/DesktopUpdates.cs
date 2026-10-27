using System;
using System.IO;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using FloatClip.Share;

namespace FloatClip.Desktop;

public enum UpdatePhase { Idle,Checking,Current,Ahead,Available,Downloading,Ready,Failed }
public sealed record DesktopUpdateState(UpdatePhase Phase,UpdateRelease? Release,string Message,double Progress=0,string? Package=null,bool Notice=false);

/** One update workflow shared by startup, the banner, and settings. It never owns clipboard state. */
public sealed class DesktopUpdates:IDisposable
{
    sealed class Preferences { public bool Automatic{get;set;}=true;public int SkippedBuild{get;set;} }
    readonly object gate=new();
    readonly string root,preferencesPath;
    readonly Func<CancellationToken,Task<UpdateRelease>> check;
    readonly Func<UpdateRelease,string,IProgress<double>?,CancellationToken,Task<string>> download;
    readonly CancellationTokenSource lifetime=new();
    Preferences preferences;
    Task? checking,downloading;
    bool disposed,pendingInstallFailure;
    DesktopUpdateState state=new(UpdatePhase.Idle,null,"尚未检查更新");
    public event Action? Changed;
    public bool Automatic{get{lock(gate)return preferences.Automatic;}}
    public DesktopUpdateState Snapshot{get{lock(gate)return state;}}
    public DesktopUpdates(string root,Func<CancellationToken,Task<UpdateRelease>>? check=null,Func<UpdateRelease,string,IProgress<double>?,CancellationToken,Task<string>>? download=null)
    {
        this.root=root;Directory.CreateDirectory(root);preferencesPath=Path.Combine(root,"preferences.json");
        this.check=check??ShareUpdates.Check;this.download=download??ShareUpdates.Download;
        try{preferences=JsonSerializer.Deserialize<Preferences>(File.ReadAllText(preferencesPath))??new();}catch{preferences=new();}
        string result=Path.Combine(root,"last-install.json");
        try{using var record=JsonDocument.Parse(File.ReadAllText(result));if(!record.RootElement.GetProperty("ok").GetBoolean()){pendingInstallFailure=true;state=new(UpdatePhase.Failed,null,"上次更新未完成，已保留原版本。"+record.RootElement.GetProperty("error").GetString(),Notice:true);}File.Delete(result);}catch{}
    }
    void Publish(DesktopUpdateState value){lock(gate){if(disposed)return;state=value;}Changed?.Invoke();}
    void Save(){string temporary=preferencesPath+".tmp";File.WriteAllText(temporary,JsonSerializer.Serialize(preferences));File.Move(temporary,preferencesPath,true);}
    public void SetAutomatic(bool value){lock(gate){bool old=preferences.Automatic;preferences.Automatic=value;try{Save();}catch{preferences.Automatic=old;throw;}}Changed?.Invoke();}
    public Task CheckAsync(bool manual=false)
    {
        lock(gate){if(disposed||!manual&&(!preferences.Automatic||pendingInstallFailure))return Task.CompletedTask;
            pendingInstallFailure=false;
            if(downloading?.IsCompleted==false)return downloading;
            if(checking?.IsCompleted==false)return checking;
            return checking=CheckCore(manual);}
    }
    async Task CheckCore(bool manual)
    {
        Publish(new(UpdatePhase.Checking,state.Release,"正在检查更新…"));
        try {
            var release=await check(lifetime.Token);int comparison=ShareUpdates.Compare(release);
            bool notice=manual||release.Build!=preferences.SkippedBuild;
            Publish(comparison>0?new(UpdatePhase.Available,release,"发现新版本 "+release.Version+" · build "+release.Build,Notice:notice):comparison<0?
                new(UpdatePhase.Ahead,release,"当前为预览版本；发布源为 "+release.Version+" · build "+release.Build):new(UpdatePhase.Current,release,"已是最新版本 "+ShareUpdates.Version+" · build "+ShareUpdates.Build));
        }catch(OperationCanceledException)when(lifetime.IsCancellationRequested){}
        catch(Exception e){Publish(new(UpdatePhase.Failed,null,e is IOException?e.Message:"暂时无法连接更新源，请稍后重试",Notice:manual));}
    }
    public void Postpone(){Publish(Snapshot with {Notice=false});}
    public void Skip()
    {
        lock(gate){if(state.Release==null)return;int old=preferences.SkippedBuild;preferences.SkippedBuild=state.Release.Build;try{Save();}catch{preferences.SkippedBuild=old;throw;}}
        Publish(Snapshot with {Notice=false});
    }
    public Task DownloadAsync()
    {
        lock(gate){if(disposed)return Task.CompletedTask;if(downloading?.IsCompleted==false)return downloading;
            var release=state.Release;if(release==null||ShareUpdates.Compare(release)<=0)return Task.CompletedTask;
            return downloading=DownloadCore(release);}
    }
    async Task DownloadCore(UpdateRelease release)
    {
        Publish(new(UpdatePhase.Downloading,release,"正在下载更新…",Notice:true));
        try{
            var progress=new Progress<double>(ReportProgress);
            string file=await download(release,Path.Combine(root,"Packages"),progress,lifetime.Token);
            Publish(new(UpdatePhase.Ready,release,"更新已下载并通过校验",100,file,true));
        }catch(OperationCanceledException)when(lifetime.IsCancellationRequested){}
        catch(Exception e){Publish(new(UpdatePhase.Failed,release,e is IOException?e.Message:"下载未完成，请重试",Notice:true));}
    }
    void ReportProgress(double value)
    {
        lock(gate){if(disposed||state.Phase!=UpdatePhase.Downloading||(int)value==(int)state.Progress)return;state=state with {Progress=value};}Changed?.Invoke();
    }
    public void Dispose(){lock(gate){if(disposed)return;disposed=true;}lifetime.Cancel();}
}
