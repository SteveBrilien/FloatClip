using System;
using System.IO;
using System.Text.Json;
using System.Linq;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Microsoft.Win32;

namespace FloatClip.Desktop.Design;
public static class DesignTokens
{
    public static SolidColorBrush Brush(string name)=>(SolidColorBrush)Application.Current.FindResource("FloatClip."+name);
    public static double Number(string name)=>(double)Application.Current.FindResource("FloatClip."+name);
    public static CornerRadius Radius(string name)=>(CornerRadius)Application.Current.FindResource("FloatClip.Radius."+name);
    public static TimeSpan Motion(string name)=>DesignPreferences.ReducedMotion||!SystemParameters.ClientAreaAnimation||SystemParameters.HighContrast?TimeSpan.Zero:((Duration)Application.Current.FindResource("FloatClip.Motion."+name)).TimeSpan;
    public static void Button(Control button,string variant="Default"){button.ClearValue(Control.BackgroundProperty);button.ClearValue(Control.ForegroundProperty);button.ClearValue(Control.BorderBrushProperty);button.Style=(Style)Application.Current.FindResource("FloatClip.Button."+variant);}
    public static void Hover(Border border){border.MouseEnter+=(_,_)=>border.SetResourceReference(Border.BackgroundProperty,"FloatClip.Hover");border.MouseLeave+=(_,_)=>border.SetResourceReference(Border.BackgroundProperty,"FloatClip.Surface");}
}
public static class DesignTheme
{
    public static bool Apply(string mode,DesignPreferences? preferences=null)
    {
        bool dark=mode is "dark" or "tokyo-night"||mode=="system"&&(int?)Registry.GetValue("HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize","AppsUseLightTheme",1)==0;
        var palette=new ResourceDictionary{Source=new Uri("pack://application:,,,/FloatClip;component/Design/"+(dark?"TokyoNight":"Light")+".xaml")};
        SolidColorBrush B(string name)=>(SolidColorBrush)palette["FloatClip."+name];
        static Color Mix(Color a,Color b,double amount)=>Color.FromRgb(
            (byte)Math.Clamp(a.R*(1-amount)+b.R*amount,0,255),
            (byte)Math.Clamp(a.G*(1-amount)+b.G*amount,0,255),
            (byte)Math.Clamp(a.B*(1-amount)+b.B*amount,0,255));
        if(preferences?.FollowSystemAccent==true)
        {
            Color system=SystemParameters.WindowGlassColor;
            if(system.A>24)
            {
                system=Color.FromRgb(system.R,system.G,system.B);
                palette["FloatClip.Accent"]=new SolidColorBrush(system);
                palette["FloatClip.AccentHover"]=new SolidColorBrush(Mix(system,dark?Colors.White:Colors.Black,.12));
                palette["FloatClip.AccentPressed"]=new SolidColorBrush(Mix(system,dark?Colors.White:Colors.Black,.22));
                palette["FloatClip.Selected"]=new SolidColorBrush(Mix(B("Background").Color,system,dark ? .24 : .12));
                palette["FloatClip.SelectedHover"]=new SolidColorBrush(Mix(B("Background").Color,system,dark ? .34 : .20));
            }
        }
        if(preferences?.WhiteBorders==true)
            palette["FloatClip.Border"]=new SolidColorBrush(Color.FromArgb((byte)(dark?118:210),255,255,255));
        DesktopFluent.Apply(dark,B("Background"),B("Surface"),B("TextPrimary"),B("TextSecondary"),B("Border"),B("Hover"),B("Accent"));
        var resources=Application.Current.Resources;foreach(object key in palette.Keys)resources[key]=palette[key];
        resources["FloatClip.Text"]=B("TextPrimary");resources["FloatClip.Muted"]=B("TextSecondary");resources["FloatClip.Line"]=B("Border");resources["FloatClip.Tint"]=B("Selected");return dark;
    }
}
public sealed class DesignPreferences
{
    public bool ReduceMotion{get;set;}
    public bool EnableMaterial{get;set;}
    public double WindowOpacity{get;set;}=1;
    public string ClipboardBackground{get;set;}="solid";
    public double BackgroundOpacity{get;set;}=.88;
    public bool FollowSystemAccent{get;set;}=true;
    public bool WhiteBorders{get;set;}
    public static bool ReducedMotion{get;private set;}
    public static DesignPreferences Load(string root){try{var p=JsonSerializer.Deserialize<DesignPreferences>(File.ReadAllText(Path.Combine(root,"design-preferences.json")))??new();p.WindowOpacity=Math.Clamp(p.WindowOpacity,.2,1);p.BackgroundOpacity=Math.Clamp(p.BackgroundOpacity,.2,1);if(p.ClipboardBackground is not ("solid" or "acrylic" or "transparent" or "window"))p.ClipboardBackground="solid";ReducedMotion=p.ReduceMotion;return p;}catch{ReducedMotion=false;return new();}}
    public void Save(string root){string path=Path.Combine(root,"design-preferences.json");File.WriteAllText(path+".tmp",JsonSerializer.Serialize(this));File.Move(path+".tmp",path,true);ReducedMotion=ReduceMotion;}
}
public sealed record MaterialCapability(bool Dwm,bool Transparency,bool Mica,string Fallback);
public static class MaterialPolicy
{
    [DllImport("dwmapi.dll")]static extern int DwmIsCompositionEnabled(out bool enabled);
    public static MaterialCapability Evaluate(bool floating)
    {
        bool dwm=false;try{_=DwmIsCompositionEnabled(out dwm);}catch{}
        bool transparency=(int?)Registry.GetValue("HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize","EnableTransparency",1)!=0;
        bool mica=OperatingSystem.IsWindowsVersionAtLeast(10,0,22621)&&dwm&&transparency&&!SystemParameters.HighContrast;
        return new(dwm,transparency,mica,!mica?"系统条件不满足，使用纯色":"系统具备材质条件；实际模式与回退由背景服务决定");
    }
}
public static class DesignStates
{
    public static readonly DependencyProperty IsSelectedProperty=DependencyProperty.RegisterAttached("IsSelected",typeof(bool),typeof(DesignStates),new PropertyMetadata(false));
    public static bool GetIsSelected(DependencyObject value)=>(bool)value.GetValue(IsSelectedProperty);public static void SetIsSelected(DependencyObject value,bool selected)=>value.SetValue(IsSelectedProperty,selected);
}
