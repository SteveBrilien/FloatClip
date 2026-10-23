using System;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Shell;
using FloatClip.Desktop.Design;
using Wpf.Ui.Controls;
namespace FloatClip.Desktop.Services;

/** Owns background-only opacity; content/text opacity always stays at 1. */
public static class ClipboardBackgroundService
{
    public static string Apply(Window window,DesignPreferences preferences,bool clipboard)
    {
        window.Opacity=1;
        var baseBrush=DesignTokens.Brush("Background");var handle=new WindowInteropHelper(window).Handle;
        var chrome=WindowChrome.GetWindowChrome(window);var capability=MaterialPolicy.Evaluate(true);
        string mode=clipboard?preferences.ClipboardBackground:"solid";
        if(handle==IntPtr.Zero){window.Background=baseBrush;return "";}
        bool useMaterial=mode=="acrylic"&&capability.Mica&&!window.AllowsTransparency;
        if(!window.AllowsTransparency) {
            if(chrome!=null)chrome.GlassFrameThickness=new Thickness(useMaterial?-1:0);
            bool applied=WindowBackdrop.ApplyBackdrop(handle,useMaterial?WindowBackdropType.Acrylic:WindowBackdropType.None);
            if(useMaterial&&!applied){useMaterial=false;if(chrome!=null)chrome.GlassFrameThickness=new Thickness(0);WindowBackdrop.ApplyBackdrop(handle,WindowBackdropType.None);}
            if(useMaterial)WindowBackdrop.RemoveBackground(window);
        }
        bool transparent=mode=="transparent"&&window.AllowsTransparency&&capability.Dwm&&capability.Transparency&&!SystemParameters.HighContrast;
        window.Background=useMaterial||transparent?new SolidColorBrush(Color.FromArgb((byte)Math.Round(255*preferences.BackgroundOpacity),baseBrush.Color.R,baseBrush.Color.G,baseBrush.Color.B)):baseBrush;
        if(mode=="transparent"&&!window.AllowsTransparency||mode!="transparent"&&window.AllowsTransparency&&clipboard)return "背景类型将在重新打开应用后生效";
        if(mode=="acrylic"&&!useMaterial||mode=="transparent"&&!transparent)return "系统透明效果不可用，当前使用纯色背景";
        return "";
    }
}
