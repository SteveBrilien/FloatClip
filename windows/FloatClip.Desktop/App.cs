using System;
using System.Collections.Generic;
using System.IO;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Markup;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Threading;
using System.Linq;
using System.Diagnostics;
using Microsoft.Win32;
using FloatClip.Share;

namespace FloatClip.Desktop;

public static class Program
{
    [STAThread] public static void Main(string[] args)
    {
        try {
            if(args.Length==2&&args[0]=="--apply-update"){Environment.ExitCode=DesktopUpdateInstaller.Apply(args[1]);return;}
            if(args.Length==4 && args[0]=="--qa-nearby") { Acceptance.RunNearby(args[1],args[2],args[3]); return; }
            if(args.Length==3 && args[0]=="--qa") { Acceptance.Run(args[1],args[2]); return; }
            if(args.Length==3 && args[0]=="--qa-ui") { Acceptance.RunDesktopUI(args[1],args[2]); return; }
            if(args.Length==0||args.All(a=>a is "--clipboard" or "--background" or "--main" or "--devices" or "--legacy-lan" or "--enable-autostart" or "--web"))
            {
                bool background=args.Contains("--background");
                bool devices=args.Contains("--devices")||args.Contains("--legacy-lan");
                if(args.Contains("--web"))CrossNetworkPortal.Open();
                using var instance=new DesktopSingleInstance();
                if(!instance.IsOwner){instance.Signal(background?(byte)0:devices?(byte)3:(byte)1);return;}
                var resident=new Application{ShutdownMode=ShutdownMode.OnExplicitShutdown};
                DesktopFluent.Initialize(resident.Resources);
                using var host=new ClipboardHost();
                instance.Listen(action=>resident.Dispatcher.BeginInvoke(new Action(()=>{
                    if(action==1){host.ShowPopup();_=host.Updates.CheckAsync();}else if(action==2)host.ShowLibrary();else if(action==3)host.ShowDevices();
                })));
                if(args.Contains("--enable-autostart"))WindowsStartup.SetEnabled(true);
                host.Start(background);
                if(args.Contains("--main"))host.ShowLibrary();
                if(devices)host.ShowDevices();
                resident.Run();return;
            }
            throw new ArgumentException("不支持的启动参数。请直接运行 FloatClip，或使用 --devices 打开设备页。");
        } catch(Exception e) {
            Environment.ExitCode=1;
            if(args.Length>0 && args[0].StartsWith("--qa")) { if(args.Length>2 && !System.IO.File.Exists(args[2])) try { System.IO.File.WriteAllText(args[2],System.Text.Json.JsonSerializer.Serialize(new {result="FAIL",error=e.Message})); } catch { } return; }
            try { string logs=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"FloatClip","Share"); Directory.CreateDirectory(logs); System.IO.File.WriteAllText(Path.Combine(logs,"startup-error.log"),e.ToString()); } catch { }
            MessageBox.Show("FloatClip 无法启动："+e.GetType().Name,"FloatClip",MessageBoxButton.OK,MessageBoxImage.Error);
        }
    }
}
