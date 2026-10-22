using System;
using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using FloatClip.Desktop.Services;
using FloatClip.Desktop.ViewModels;

namespace FloatClip.Desktop;

/** A modeless, stable view. Pairing and clipboard sessions remain owned by the host. */
public partial class ClipboardSharingView:UserControl,IDisposable
{
    readonly DevicePanelViewModel viewModel;
    public ClipboardSharingView(NativeClipboardSync sync):this(new ClipboardDeviceService(sync)){}
    internal ClipboardSharingView(IClipboardDeviceService service)
    {
        InitializeComponent();
        viewModel=new DevicePanelViewModel(service,action=>Dispatcher.BeginInvoke(action),(title,message)=>Dispatcher.Invoke(()=>MessageBox.Show(Window.GetWindow(this),message,title,MessageBoxButton.OKCancel,MessageBoxImage.Question)==MessageBoxResult.OK));
        DataContext=viewModel;
    }
    void PeerMenuClicked(object sender,RoutedEventArgs e)
    {
        if(sender is Button button&&button.ContextMenu is ContextMenu menu){menu.PlacementTarget=button;menu.IsOpen=true;}
    }
    void OpenBluetoothSettings(object sender,RoutedEventArgs e)
    {
        try{Process.Start(new ProcessStartInfo("ms-settings:bluetooth"){UseShellExecute=true});}
        catch{viewModel.ReportPlatformFailure("请从 Windows 设置中打开蓝牙和设备。");}
    }
    public void Dispose()=>viewModel.Dispose();
}
