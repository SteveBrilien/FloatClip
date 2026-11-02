using System;
using System.Collections.Generic;
using System.Linq;

namespace FloatClip.Desktop.Services;

public sealed record DevicePeerState(string Key,string Name,bool Enabled,bool CanConfigure,string Status,IReadOnlyList<string>? RelatedKeys=null);
internal sealed record DeviceRelationState(string Key,Guid? DeviceId,string? Name,bool Enabled,bool CanConfigure,string? Transport,bool Pending);

/** Group only identities learned on authenticated sessions; names never establish identity. */
internal static class DevicePeerGrouping
{
    internal static bool UsefulName(string? value)=>!string.IsNullOrWhiteSpace(value)&&
        !value.Trim().Equals("unknown device",StringComparison.OrdinalIgnoreCase)&&
        !value.Trim().Equals("unknown",StringComparison.OrdinalIgnoreCase)&&value.Trim()!="未知设备"&&value.Trim()!="已授权设备";

    internal static DevicePeerState[] Group(IEnumerable<DeviceRelationState> relations,bool enabled,bool automatic)=>relations
        .GroupBy(r=>r.DeviceId is Guid id?"device:"+id:"relation:"+r.Key)
        .Select(group=>{
            var rows=group.ToArray();var connected=rows.Where(r=>r.Transport!=null).ToArray();
            string name=rows.Select(r=>r.Name).FirstOrDefault(UsefulName)?.Trim()??"已授权设备";
            bool on=rows.All(r=>r.Enabled);
            string status=!enabled?"共享已关闭":!on?"此设备已暂停":rows.Any(r=>r.Pending)?"等待确认送达":
                connected.Any(r=>r.Transport=="lan")?"局域网已连接":connected.Any(r=>r.Transport=="bluetooth")?"蓝牙短连接中":
                !automatic?"自动连接已关闭":"已授权，等待自动连接";
            if(enabled&&on&&connected.Any(r=>r.Transport=="lan")&&connected.Any(r=>r.Transport=="bluetooth"))status="蓝牙 + 局域网已连接";
            return new DevicePeerState(group.Key,name,on,rows.Any(r=>r.CanConfigure),status,rows.Select(r=>r.Key).ToArray());
        }).ToArray();
}
