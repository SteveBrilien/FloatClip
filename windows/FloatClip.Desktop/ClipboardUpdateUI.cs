using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using FloatClip.Share;

namespace FloatClip.Desktop;

public sealed partial class LocalClipboardWindow
{
    Border? updateBanner;
    readonly List<WeakReference<StackPanel>> updateSections=new();
    void UpdateChanged(){if(!Dispatcher.HasShutdownStarted)Dispatcher.BeginInvoke(new Action(RenderUpdates));}
    void AttachUpdateBanner()
    {
        updateBanner=new Border{CornerRadius=new(10),Padding=new(12),Margin=new(0,0,0,12)};
        updateBanner.SetResourceReference(Border.BackgroundProperty,"FloatClip.Tint");header.Children.Add(updateBanner);RenderUpdates();
    }
    void RenderUpdates()
    {
        var state=host.Updates.Snapshot;
        if(updateBanner!=null){
            updateBanner.Visibility=state.Notice&&state.Phase is UpdatePhase.Available or UpdatePhase.Downloading or UpdatePhase.Ready or UpdatePhase.Failed?Visibility.Visible:Visibility.Collapsed;
            var row=new DockPanel();var view=Button(state.Phase==UpdatePhase.Ready?"安装":"查看",ShowUpdates);view.MinHeight=30;view.Padding=new(10,4,10,4);view.Margin=new(8,0,0,0);DockPanel.SetDock(view,Dock.Right);row.Children.Add(view);
            var label=new TextBlock{Text=state.Message,FontSize=13,TextWrapping=TextWrapping.Wrap,VerticalAlignment=VerticalAlignment.Center};label.SetResourceReference(TextBlock.ForegroundProperty,"FloatClip.Text");row.Children.Add(label);updateBanner.Child=row;
        }
        foreach(var reference in updateSections.ToArray())if(reference.TryGetTarget(out var section))FillUpdateSection(section,state);else updateSections.Remove(reference);
    }
    void ShowUpdates()=>Navigate(typeof(UpdatesPage));
    StackPanel BuildUpdateSection()
    {
        var section=new StackPanel{Margin=new(0,20,0,4)};updateSections.Add(new(section));FillUpdateSection(section,host.Updates.Snapshot);return section;
    }
    void FillUpdateSection(StackPanel section,DesktopUpdateState state)
    {
        section.Children.Clear();section.Children.Add(new TextBlock{Text="版本与更新",FontSize=20,FontWeight=FontWeights.SemiBold});
        section.Children.Add(MutedLabel("当前版本 "+ShareUpdates.Version+" · build "+ShareUpdates.Build,new(0,6,0,16)));
        var status=new TextBlock{Text=state.Message,TextWrapping=TextWrapping.Wrap,Margin=new(0,0,0,12)};status.SetResourceReference(TextBlock.ForegroundProperty,"FloatClip.Text");section.Children.Add(status);
        if(state.Release!=null&&ShareUpdates.Compare(state.Release)>0)section.Children.Add(MutedLabel(state.Release.Notes,new(0,0,0,16)));
        if(state.Phase==UpdatePhase.Downloading){var bar=new ProgressBar{Minimum=0,Maximum=100,Value=state.Progress,Height=4,Margin=new(0,0,0,10)};bar.SetResourceReference(Control.ForegroundProperty,"FloatClip.Accent");bar.SetResourceReference(Control.BackgroundProperty,"FloatClip.Tint");section.Children.Add(bar);section.Children.Add(MutedLabel($"{state.Progress:F0}%",new(0,0,0,12)));}
        var actions=new WrapPanel{Margin=new(0,0,0,12)};
        if(state.Phase==UpdatePhase.Ready&&state.Release!=null&&state.Package!=null){
            var install=Button("更新并重启",()=>{});install.Click+=async(_,_)=>{install.IsEnabled=false;try{await DesktopUpdateInstaller.Prepare(state.Release,state.Package,Path.Combine(host.PreferencesRoot,"Updates"));Application.Current.Shutdown();}catch(Exception e){status.Text=e is IOException?e.Message:"更新未能启动，请重试";install.IsEnabled=true;}};actions.Children.Add(install);
        }else if(state.Release!=null&&ShareUpdates.Compare(state.Release)>0&&state.Phase!=UpdatePhase.Checking){
            var download=Button(state.Phase==UpdatePhase.Failed?"重试下载":"下载更新",()=>_ =host.Updates.DownloadAsync());download.IsEnabled=state.Phase!=UpdatePhase.Downloading;actions.Children.Add(download);
        }else{var check=Button("检查更新",()=>_ =host.Updates.CheckAsync(true));check.IsEnabled=state.Phase!=UpdatePhase.Checking;actions.Children.Add(check);}
        if(state.Release!=null&&ShareUpdates.Compare(state.Release)>0){actions.Children.Add(Button("稍后",()=>{host.Updates.Postpone();Navigate(typeof(ClipboardPage));}));if(state.Phase==UpdatePhase.Available)actions.Children.Add(Button("跳过此版本",host.Updates.Skip));}
        section.Children.Add(actions);
        section.Children.Add(ToggleSetting("启动时检查更新","新版本可稍后处理",host.Updates.Automatic,host.Updates.SetAutomatic,"AutomaticUpdates"));
    }
}
