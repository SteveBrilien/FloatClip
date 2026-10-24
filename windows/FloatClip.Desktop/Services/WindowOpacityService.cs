using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Runtime.CompilerServices;
using System.Windows;
using System.Windows.Interop;

namespace FloatClip.Desktop.Services;

/// <summary>
/// Explicit whole-window alpha for ordinary WPF HWNDs. Never called by a theme,
/// page or background refresh, so PowerToys remains free to change the alpha.
/// Per-pixel WPF windows must be reopened in an ordinary mode first.
/// </summary>
public static class WindowOpacityService
{
    const int ExtendedStyle=-20,Layered=0x00080000;
    const uint Alpha=2;
    static readonly ConditionalWeakTable<Window,LayeredHook> hooks=new();
    [StructLayout(LayoutKind.Sequential)]
    struct StyleChange{public int OldStyle,NewStyle;}
    sealed class LayeredHook
    {
        readonly HwndSource source;
        public bool AllowRemoval;
        public LayeredHook(Window window,IntPtr handle)
        {
            source=HwndSource.FromHwnd(handle)!;
            source.AddHook(Filter);
            window.Closed+=(_,_)=>{source.RemoveHook(Filter);hooks.Remove(window);};
        }
        IntPtr Filter(IntPtr handle,int message,IntPtr wparam,IntPtr lparam,ref bool handled)
        {
            // HwndTarget's default WM_STYLECHANGING handler strips WS_EX_LAYERED
            // from ordinary WPF windows, explicitly blocking external alpha tools.
            // This opt-in whole-window mode owns a system-layered HWND. Preserve
            // the bit while allowing the caller to change every unrelated style.
            if(!AllowRemoval&&message==0x007C&&wparam.ToInt64()==ExtendedStyle)
            {
                var style=Marshal.PtrToStructure<StyleChange>(lparam);
                if(((style.OldStyle|style.NewStyle)&Layered)!=0)
                {
                    style.NewStyle|=Layered;Marshal.StructureToPtr(style,lparam,false);
                    handled=true;
                }
            }
            return IntPtr.Zero;
        }
    }
    [DllImport("user32.dll",EntryPoint="GetWindowLongW",SetLastError=true)]
    static extern int GetWindowLong(IntPtr window,int index);
    [DllImport("user32.dll",EntryPoint="SetWindowLongW",SetLastError=true)]
    static extern int SetWindowLong(IntPtr window,int index,int value);
    [DllImport("user32.dll",SetLastError=true)]
    [return:MarshalAs(UnmanagedType.Bool)]
    static extern bool SetLayeredWindowAttributes(IntPtr window,uint colorKey,byte alpha,uint flags);

    public static string Apply(Window window,double opacity)
    {
        // Calling SetLayeredWindowAttributes on an UpdateLayeredWindow surface
        // would break WPF rendering. Do not change that window's styles.
        if(window.AllowsTransparency)return "正在切换背景";
        var handle=new WindowInteropHelper(window).Handle;
        if(handle==IntPtr.Zero)return "";
        if(!double.IsFinite(opacity))return "整窗不透明度无效";
        byte alpha=(byte)Math.Round(255*Math.Clamp(opacity,.2,1));
        var hook=hooks.GetValue(window,w=>new LayeredHook(w,handle));
        int original=GetWindowLong(handle,ExtendedStyle);
        bool added=(original&Layered)==0;
        if(added)
        {
            Marshal.SetLastPInvokeError(0);
            if(SetWindowLong(handle,ExtendedStyle,original|Layered)==0&&Marshal.GetLastPInvokeError()!=0)
                return Failure();
        }
        if(SetLayeredWindowAttributes(handle,0,alpha,Alpha))return "";
        string error=Failure();
        // Restore only the bit this attempt added. Preserve unrelated styles.
        if(added){hook.AllowRemoval=true;try{SetWindowLong(handle,ExtendedStyle,GetWindowLong(handle,ExtendedStyle)&~Layered);}finally{hook.AllowRemoval=false;}}
        return error;
    }

    public static string ApplyAll(double opacity)
    {
        string status="";
        foreach(Window window in Application.Current.Windows)
        {
            if(window is not LocalClipboardWindow&&window.Owner==null)continue;
            string result=Apply(window,opacity);
            if(result.Length>0)status=result;
        }
        return status;
    }
    static string Failure()=>"整窗透明度未能调整："+new Win32Exception(Marshal.GetLastPInvokeError()).Message;
}
