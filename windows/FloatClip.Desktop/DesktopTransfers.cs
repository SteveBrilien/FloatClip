using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using FloatClip.Share;

namespace FloatClip.Desktop;

public enum TransferPhase{Offered,Preparing,Transferring,Completed,Declined,Canceled,Failed}
public sealed record DesktopTransfer(Guid Id,string Peer,string Name,long Total,long Done,bool Outgoing,TransferPhase Phase,string Message,string? Path=null,string? SourcePath=null)
{
    public long CreatedAtUtcTicks{get;init;}=DateTime.UtcNow.Ticks;
}
public sealed record FilePeer(string Key,string Name){public override string ToString()=>Name;}

/** UI adapter for the existing bounded, checksummed ShareWire file stream. No automatic acceptance. */
public sealed class DesktopTransfers
{
    sealed class Link(string key,Session session,string transport){public readonly string Key=key,Transport=transport;public readonly Session Session=session;public Guid? Active;public Offer? Offer;}
    readonly object gate=new();
    readonly Dictionary<Session,Link> links=new();
    readonly Dictionary<Guid,DesktopTransfer> rows=new();
    readonly Func<string,string> peerName;
    readonly Func<string,bool> allowed;
    public event Action? Changed;
    public DesktopTransfers(Func<string,string> peerName,Func<string,bool>? allowed=null){this.peerName=peerName;this.allowed=allowed??(_=>true);}
    static bool Active(TransferPhase phase)=>phase is TransferPhase.Offered or TransferPhase.Preparing or TransferPhase.Transferring;
    void Notify(){try{Changed?.Invoke();}catch{/* Views cannot invalidate a completed transfer. */}}
    public DesktopTransfer[] Snapshot(){lock(gate)return rows.Values.Reverse().ToArray();}
    public FilePeer[] Peers(){lock(gate)return links.Values.Where(l=>(l.Transport=="lan"||l.Session.CatalogEnabled)&&allowed(l.Key)).Select(l=>new FilePeer(l.Key,peerName(l.Key))).DistinctBy(p=>p.Key).ToArray();}
    public void Attach(string key,Session session,string transport)
    {
        var link=new Link(key,session,transport);lock(gate)links[session]=link;
        session.Offered=offer=>{
            if((transport!="lan"&&!session.CatalogEnabled)||!allowed(key)){try{session.Reject(offer.Id);}catch{session.Dispose();}return;}
            lock(gate){link.Active=offer.Id;link.Offer=offer;Trim();rows[offer.Id]=new(offer.Id,peerName(key),offer.Name,offer.Size,0,false,TransferPhase.Offered,"等待你接收");}Notify();
        };
        session.Progress+=(name,done,total,outgoing)=>{lock(gate){if(link.Active is not Guid id||!rows.TryGetValue(id,out var row)||!Active(row.Phase))return;rows[id]=row with{Name=name,Total=total,Done=done,Phase=done==0&&outgoing?TransferPhase.Preparing:TransferPhase.Transferring,Message=done==total?"正在校验并等待确认":done==0&&outgoing?"等待对方接收":"正在传输"};}Notify();};
        session.File+=(offer,path,outgoing)=>{lock(gate){if(link.Active is not Guid id||!rows.TryGetValue(id,out var row))return;rows[id]=row with{Done=offer.Size,Total=offer.Size,Phase=TransferPhase.Completed,Message=outgoing?"对方已接收并校验":"已接收并校验，可另存为",Path=outgoing?null:path};link.Active=null;link.Offer=null;}Notify();};
        session.FileFailed+=message=>{lock(gate){if(link.Active is not Guid id||!rows.TryGetValue(id,out var row)||!Active(row.Phase))return;rows[id]=row with{Phase=TransferPhase.Failed,Message=message};link.Active=null;link.Offer=null;}Notify();};
        session.Closed+=()=>{lock(gate){links.Remove(session);if(link.Active is Guid id&&rows.TryGetValue(id,out var row)&&Active(row.Phase))rows[id]=row with{Phase=TransferPhase.Failed,Message="连接中断，文件未完成；请重新发送"};link.Active=null;link.Offer=null;}Notify();};
        Notify();
    }
    void Trim(){foreach(var row in rows.Values.Where(r=>!Active(r.Phase)&&r.Path==null).Take(Math.Max(0,rows.Count-49)).ToArray())rows.Remove(row.Id);}
    public Guid Send(string key,string path)
    {
        if(!allowed(key))throw new IOException("此设备的共享已暂停");
        var info=new FileInfo(path);if(!info.Exists||info.Length>Wire.MaxFile||(info.Attributes&FileAttributes.ReparsePoint)!=0)throw new IOException("请选择 64 MiB 内的普通文件");Wire.SafeName(info.Name);
        Link link;Guid id=Guid.NewGuid();lock(gate){link=links.Values.Where(l=>l.Key==key&&(l.Transport=="lan"||l.Session.CatalogEnabled)).OrderByDescending(l=>l.Transport=="lan").FirstOrDefault()??throw new IOException("请先连接该设备的局域网或蓝牙文件通道");if(link.Active!=null)throw new IOException("请等待该设备的当前文件完成");Trim();rows[id]=new(id,peerName(key),info.Name,info.Length,0,true,TransferPhase.Preparing,"正在准备文件",null,path);link.Active=id;}
        try{link.Session.SendFile(path,id);}catch{lock(gate){rows[id]=rows[id] with{Phase=TransferPhase.Failed,Message="文件未发送，请检查连接后重试"};link.Active=null;}Notify();throw;}Notify();return id;
    }
    Link Find(Guid id){lock(gate)return links.Values.FirstOrDefault(l=>l.Active==id)??throw new IOException("文件请求已结束");}
    public void Accept(Guid id)
    {
        var link=Find(id);lock(gate){if(!rows.TryGetValue(id,out var row)||row.Phase!=TransferPhase.Offered)throw new IOException("接收请求已失效");}
        if(!allowed(link.Key))throw new IOException("此设备的共享已暂停");
        try{link.Session.Accept(id);lock(gate)if(rows.TryGetValue(id,out var row)&&row.Phase==TransferPhase.Offered)rows[id]=row with{Phase=TransferPhase.Transferring,Message="正在接收"};}catch{link.Session.Dispose();throw;}Notify();
    }
    public void Decline(Guid id)
    {
        var link=Find(id);link.Session.Reject(id);lock(gate){rows[id]=rows[id] with{Phase=TransferPhase.Declined,Message="已拒绝接收"};link.Active=null;link.Offer=null;}Notify();
    }
    public void Cancel(Guid id)
    {
        var link=Find(id);lock(gate){rows[id]=rows[id] with{Phase=TransferPhase.Canceled,Message="文件已停止；该设备的文字连接将按原设置重连"};link.Active=null;link.Offer=null;}link.Session.Dispose();Notify();
    }
    public void ForgetReceived(Guid id)
    {
        string? path;lock(gate){if(!rows.TryGetValue(id,out var row)||row.Phase!=TransferPhase.Completed||row.Outgoing)return;path=row.Path;}
        if(path!=null)System.IO.File.Delete(path);lock(gate)rows.Remove(id);Notify();
    }
}
