using System.Windows;
using System.Windows.Input;

namespace FloatClip.Desktop;

public sealed partial class LocalClipboardWindow
{
    internal bool IsPanelPresented=>IsVisible&&WindowState!=WindowState.Minimized;

    internal void ShowWithMotion(bool activate)
    {
        ShowActivated=activate;
        if(!IsVisible)Show();
        if(WindowState==WindowState.Minimized)
        {
            if(activate&&!designPreferences.ReduceMotion)
            {
                SystemCommands.RestoreWindow(this);
                Dispatcher.BeginInvoke(System.Windows.Threading.DispatcherPriority.Input,new System.Action(FocusPanel));
                return;
            }
            WindowState=WindowState.Normal;
        }
        if(activate)
        {
            Activate();
            FocusPanel();
        }
    }

    internal void HideWithMotion()
    {
        if(!IsPanelPresented)return;
        host.RememberPlacement();
        if(designPreferences.ReduceMotion)Hide();
        else SystemCommands.MinimizeWindow(this);
    }

    void FocusPanel()
    {
        shell.Focusable=true;
        KeyboardNavigation.SetIsTabStop(shell,false);
        Keyboard.Focus(shell);
    }
}
