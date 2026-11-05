using System;
using System.Windows;
using System.Windows.Media.Animation;
using FloatClip.Desktop.Design;

namespace FloatClip.Desktop;

public sealed partial class LocalClipboardWindow
{
    bool windowMotionRunning;

    internal void ShowWithMotion(bool activate)
    {
        bool fresh=!IsVisible;
        Show();
        if(fresh)AnimateWindowOpen();
        if(activate){Activate();FocusSearch();}
    }

    internal void HideWithMotion()
    {
        if(!IsVisible||windowMotionRunning)return;
        var duration=DesignTokens.Motion("Panel");
        if(duration==TimeSpan.Zero){Hide();return;}
        windowMotionRunning=true;
        double restingTop=Top;
        var surface=Content as FrameworkElement;
        var opacity=new DoubleAnimation(1,.88,new Duration(TimeSpan.FromMilliseconds(Math.Min(190,duration.TotalMilliseconds)))){FillBehavior=FillBehavior.Stop};
        var slide=new DoubleAnimation(restingTop,restingTop+18,new Duration(TimeSpan.FromMilliseconds(Math.Min(190,duration.TotalMilliseconds)))){FillBehavior=FillBehavior.Stop};
        slide.Completed+=(_,_)=>{
            BeginAnimation(TopProperty,null);
            if(surface!=null){surface.BeginAnimation(UIElement.OpacityProperty,null);surface.Opacity=1;}
            Top=restingTop;windowMotionRunning=false;Hide();
        };
        if(surface!=null)surface.BeginAnimation(UIElement.OpacityProperty,opacity,HandoffBehavior.SnapshotAndReplace);
        BeginAnimation(TopProperty,slide,HandoffBehavior.SnapshotAndReplace);
    }

    internal void AnimateWindowOpen()
    {
        var duration=DesignTokens.Motion("Panel");
        if(duration==TimeSpan.Zero)return;
        double restingTop=Top,from=restingTop+18;
        var surface=Content as FrameworkElement;
        if(surface!=null)surface.Opacity=.88;Top=from;
        var opacity=new DoubleAnimation(.88,1,new Duration(TimeSpan.FromMilliseconds(Math.Min(210,duration.TotalMilliseconds)))){FillBehavior=FillBehavior.Stop};
        var slide=new DoubleAnimation(from,restingTop,new Duration(TimeSpan.FromMilliseconds(Math.Min(210,duration.TotalMilliseconds)))){FillBehavior=FillBehavior.Stop};
        slide.Completed+=(_,_)=>{BeginAnimation(TopProperty,null);if(surface!=null){surface.BeginAnimation(UIElement.OpacityProperty,null);surface.Opacity=1;}Top=restingTop;};
        if(surface!=null)surface.BeginAnimation(UIElement.OpacityProperty,opacity,HandoffBehavior.SnapshotAndReplace);
        BeginAnimation(TopProperty,slide,HandoffBehavior.SnapshotAndReplace);
    }
}
