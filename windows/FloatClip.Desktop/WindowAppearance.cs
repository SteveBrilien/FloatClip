using System;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
namespace FloatClip.Desktop;
static class WindowAppearance
{
    [DllImport("dwmapi.dll")]static extern int DwmSetWindowAttribute(IntPtr hwnd,int attribute,ref int value,int size);
    public static void Apply(Window window)
    {
        if(!OperatingSystem.IsWindowsVersionAtLeast(10,0,22000))return;
        int round=2;
        _=DwmSetWindowAttribute(new WindowInteropHelper(window).Handle,33,ref round,sizeof(int));
    }
    public static void Theme(Window window,bool dark)
    {
        if(!OperatingSystem.IsWindowsVersionAtLeast(10,0,17763))return;
        IntPtr handle=new WindowInteropHelper(window).Handle;if(handle==IntPtr.Zero)return;int value=dark?1:0;
        _=DwmSetWindowAttribute(handle,OperatingSystem.IsWindowsVersionAtLeast(10,0,18985)?20:19,ref value,sizeof(int));
    }
}
