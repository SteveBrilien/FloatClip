using System.Diagnostics;

namespace FloatClip.Desktop;

/** The optional iPad/Web portal has its own browser session; local history has one native owner. */
public static class CrossNetworkPortal
{
    public static void Open()=>Process.Start(new ProcessStartInfo("https://clip.wmy-cloud.cn/?screen=devices"){UseShellExecute=true});
}
