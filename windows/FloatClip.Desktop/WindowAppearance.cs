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
}
