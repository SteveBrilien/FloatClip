using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Shell;
using FloatClip.Desktop.Design;
using Wpf.Ui.Controls;
namespace FloatClip.Desktop.Services;

/** Application-wide backdrop policy. Only the window background has alpha; content remains opaque. */
public static class ClipboardBackgroundService
{
    public static void Configure(Window window,DesignPreferences preferences,bool caption=false)
    {
        if(preferences.ClipboardBackground=="transparent")
        {
            window.WindowStyle=WindowStyle.None;window.AllowsTransparency=true;
        }
        if(caption)
        {
            window.WindowStyle=WindowStyle.None;
            {
                var content=window.Content;window.Content=null;var root=new DockPanel();
                var bar=new DockPanel{Height=DesignTokens.Number("Hit.Title"),LastChildFill=true};
                var close=new Wpf.Ui.Controls.Button{Content=new SymbolIcon(SymbolRegular.Dismiss24),ToolTip="关闭",Width=DesignTokens.Number("Hit.Title")};DesignTokens.Button(close,"Toolbar");WindowChrome.SetIsHitTestVisibleInChrome(close,true);close.Click+=(_,_)=>window.Close();DockPanel.SetDock(close,Dock.Right);bar.Children.Add(close);
                var title=new System.Windows.Controls.TextBlock{Text=window.Title,VerticalAlignment=VerticalAlignment.Center,Margin=(Thickness)Application.Current.FindResource("FloatClip.Padding.Control"),TextTrimming=TextTrimming.CharacterEllipsis};title.SetResourceReference(System.Windows.Controls.TextBlock.ForegroundProperty,"FloatClip.TextPrimary");bar.Children.Add(title);
                bar.MouseLeftButtonDown+=(_,e)=>{if(e.Handled||close.IsMouseOver)return;if(e.ClickCount==1)window.DragMove();};DockPanel.SetDock(bar,Dock.Top);root.Children.Add(bar);
                root.Children.Add(new ContentPresenter{Content=content});window.Content=root;
            }
        }
        if(WindowChrome.GetWindowChrome(window)==null)WindowChrome.SetWindowChrome(window,new WindowChrome{CaptionHeight=caption?DesignTokens.Number("Hit.Title"):0,ResizeBorderThickness=new Thickness(6),GlassFrameThickness=new Thickness(0)});
        window.SourceInitialized+=(_,_)=>Apply(window,preferences);
        // WPF/WindowChrome finalizes HWND styles after SourceInitialized. Applying
        // native alpha earlier is undone by the first Show/initial layout.
        bool firstRender=true;
        window.ContentRendered+=(_,_)=>{
            if(!firstRender)return;firstRender=false;
            if(preferences.ClipboardBackground=="window")WindowOpacityService.Apply(window,preferences.WindowOpacity);
        };
    }
    public static void RefreshAll(DesignPreferences preferences)
    {
        foreach(Window window in Application.Current.Windows)if(window is LocalClipboardWindow||window.Owner!=null)Apply(window,preferences);
    }
    static SolidColorBrush AlphaBrush(string key,double alpha)
    {
        var source=DesignTokens.Brush(key);var c=source.Color;
        return new SolidColorBrush(Color.FromArgb((byte)Math.Round(255*Math.Clamp(alpha,0,1)),c.R,c.G,c.B));
    }
    static void ApplySurfacePolicy(string mode,double opacity,bool materialAvailable,bool transparentAvailable)
    {
        var resources=Application.Current.Resources;
        double baseOpacity=Math.Clamp(opacity,.2,1);
        bool translucent=mode=="transparent"&&transparentAvailable||mode=="acrylic"&&materialAvailable;
        if(!translucent)
        {
            foreach(string key in new[]{"Background","Surface","Hover","Pressed","Selected","SelectedHover","DisabledSurface"})
            {
                var brush=DesignTokens.Brush(key);var c=brush.Color;
                resources["FloatClip."+key]=new SolidColorBrush(Color.FromArgb(255,c.R,c.G,c.B));
            }
            return;
        }
        // Text/icon resources are deliberately untouched. Dense surfaces remain readable,
        // but no longer form an opaque sheet over the application backdrop.
        double surface=mode=="acrylic"?Math.Clamp(.34+baseOpacity*.48,.42,.90):Math.Clamp(.12+baseOpacity*.60,.24,.78);
        resources["FloatClip.Surface"]=AlphaBrush("Surface",surface);
        resources["FloatClip.Hover"]=AlphaBrush("Hover",Math.Min(1,surface+.08));
        resources["FloatClip.Pressed"]=AlphaBrush("Pressed",Math.Min(1,surface+.14));
        resources["FloatClip.Selected"]=AlphaBrush("Selected",Math.Min(1,surface+.06));
        resources["FloatClip.SelectedHover"]=AlphaBrush("SelectedHover",Math.Min(1,surface+.12));
        resources["FloatClip.DisabledSurface"]=AlphaBrush("DisabledSurface",Math.Min(1,surface+.04));
        resources["FloatClip.Background"]=AlphaBrush("Background",mode=="acrylic"?0:baseOpacity);
    }
    // Keep the old signature for callers compiled against build 25. The page no longer changes the mode.
    public static string Apply(Window window,DesignPreferences preferences,bool clipboard=true)
    {
        // External tools own the HWND alpha between explicit user changes.
        // Background/theme/page refreshes must never restore Window.Opacity.
        var baseBrush=DesignTokens.Brush("Background");var handle=new WindowInteropHelper(window).Handle;
        string mode=preferences.ClipboardBackground;
        double opacity=Math.Clamp(preferences.BackgroundOpacity,.2,1);
        if(handle==IntPtr.Zero){window.Background=baseBrush;return "";}
        var capability=MaterialPolicy.Evaluate(true);var chrome=WindowChrome.GetWindowChrome(window);
        bool useMaterial=mode=="acrylic"&&capability.Mica&&!window.AllowsTransparency;
        if(!window.AllowsTransparency) {
            if(chrome!=null)chrome.GlassFrameThickness=new Thickness(useMaterial?-1:0);
            bool applied=WindowBackdrop.ApplyBackdrop(handle,useMaterial?WindowBackdropType.Acrylic:WindowBackdropType.None);
            if(useMaterial&&!applied){useMaterial=false;if(chrome!=null)chrome.GlassFrameThickness=new Thickness(0);WindowBackdrop.ApplyBackdrop(handle,WindowBackdropType.None);}
            if(useMaterial)WindowBackdrop.RemoveBackground(window);
        }
        bool transparent=mode=="transparent"&&window.AllowsTransparency&&capability.Dwm&&capability.Transparency&&!SystemParameters.HighContrast;
        ApplySurfacePolicy(mode,opacity,useMaterial,transparent);
        if(useMaterial)window.Background=Brushes.Transparent;
        else if(transparent)window.Background=AlphaBrush("Background",opacity);
        else window.Background=DesignTokens.Brush("Background");
        if(mode=="transparent"&&!window.AllowsTransparency||mode!="transparent"&&window.AllowsTransparency)return "正在切换背景";
        if(mode=="acrylic"&&!useMaterial||mode=="transparent"&&!transparent)return "当前系统使用纯色背景";
        return "";
    }

}
