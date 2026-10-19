using System;
namespace FloatClip.Desktop;
public static class HostPolicy
{
    public static bool IsTrusted(string? url)=>Uri.TryCreate(url,UriKind.Absolute,out var uri)&&uri.Scheme=="https"&&uri.Host.Equals("clip.wmy-cloud.cn",StringComparison.OrdinalIgnoreCase)&&uri.IsDefaultPort&&uri.UserInfo.Length==0;
}
