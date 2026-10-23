using System;
using System.Windows;
using System.Windows.Media;
using Wpf.Ui.Appearance;
using Wpf.Ui.Markup;

namespace FloatClip.Desktop;

public static class DesktopFluent
{
    public static void Initialize(ResourceDictionary resources)
    {
        resources.MergedDictionaries.Add(new ThemesDictionary{Theme=ApplicationTheme.Light});
        resources.MergedDictionaries.Add(new ControlsDictionary());
        resources.MergedDictionaries.Add(new ResourceDictionary{Source=new Uri("pack://application:,,,/FloatClip;component/Design/Tokens.xaml")});
        Design.DesignTheme.Apply("system");
        resources.MergedDictionaries.Add(new ResourceDictionary{Source=new Uri("pack://application:,,,/FloatClip;component/DesktopControls.xaml")});
        resources.MergedDictionaries.Add(new ResourceDictionary{Source=new Uri("pack://application:,,,/FloatClip;component/Design/Components.xaml")});
    }
    public static void Apply(bool dark,Brush background,Brush surface,Brush text,Brush muted,Brush line,Brush tint,SolidColorBrush accent)
    {
        var theme=dark?ApplicationTheme.Dark:ApplicationTheme.Light;
        if(ApplicationThemeManager.GetAppTheme()!=theme)ApplicationThemeManager.Apply(theme,Wpf.Ui.Controls.WindowBackdropType.None,false);
        ApplicationAccentColorManager.Apply(accent.Color,theme,false,false);
        var resources=Application.Current.Resources;
        foreach(string key in new[]{"ApplicationBackgroundBrush","NavigationViewContentBackground"})resources[key]=background;
        foreach(string key in new[]{"CardBackgroundFillColorDefaultBrush","ControlFillColorDefaultBrush","ControlAltFillColorSecondaryBrush"})resources[key]=surface;
        resources["TextFillColorPrimaryBrush"]=text;resources["TextFillColorSecondaryBrush"]=muted;
        resources["ControlStrokeColorDefaultBrush"]=line;resources["ControlFillColorSecondaryBrush"]=tint;
        resources["AccentFillColorDefaultBrush"]=accent;resources["AccentTextFillColorPrimaryBrush"]=accent;
        foreach(Window window in Application.Current.Windows)WindowAppearance.Theme(window,dark);
    }
}
