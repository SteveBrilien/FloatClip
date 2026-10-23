using System;
using System.Net.Http;

namespace FloatClip.Share;

internal static class UpdateHttpClient
{
    // Use Windows' native HTTP stack and the user's Internet Options (including
    // PAC/WPAD), rather than an environment proxy inherited when the app started.
    internal static HttpMessageHandler CreateHandler()
    {
        if(OperatingSystem.IsWindows())return new WinHttpHandler{
            AutomaticRedirection=false,WindowsProxyUsePolicy=WindowsProxyUsePolicy.UseWinInetProxy,
            ReceiveHeadersTimeout=TimeSpan.FromSeconds(12),ReceiveDataTimeout=TimeSpan.FromSeconds(30),SendTimeout=TimeSpan.FromSeconds(12)};
        return new HttpClientHandler{AllowAutoRedirect=false};
    }
    internal static HttpClient Create(TimeSpan timeout)=>new(CreateHandler()){Timeout=timeout};
}
