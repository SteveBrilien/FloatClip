using System;
using System.IO;
using System.Linq;
using System.Windows;
using FloatClip.Desktop;

namespace FloatClip.Next;
static class Program
{
    [STAThread] static void Main(string[] args)
    {
        bool qa=args.Length==3&&args[0]=="--qa-web";
        try{
            using var instance=qa?null:new DesktopSingleInstance();
            if(instance!=null&&!instance.IsOwner){instance.Signal(1);return;}
            var app=new Application{ShutdownMode=ShutdownMode.OnExplicitShutdown};DesktopFluent.Initialize(app.Resources);
            using var host=new ClipboardHost(qa?Path.GetFullPath(args[1]):null,!qa);
            var window=new WebShellWindow(host,qa);host.UseExternalShell(window,window.Navigate);
            instance?.Listen(action=>app.Dispatcher.BeginInvoke(new Action(()=>{if(action==3)host.ShowDevices();else if(action!=0)host.ShowPopup();})));
            if(qa){WebAcceptance.Seed(host);window.Loaded+=async(_,_)=>{try{await WebAcceptance.Run(window,host,args[2]);}catch(Exception e){Environment.ExitCode=1;File.WriteAllText(args[2],System.Text.Json.JsonSerializer.Serialize(new{result="FAIL",error=e.ToString()}));}finally{app.Shutdown();}};}
            host.Start(args.Contains("--background"));app.Run();
        }catch(Exception e){Environment.ExitCode=1;if(qa)File.WriteAllText(args[2],System.Text.Json.JsonSerializer.Serialize(new{result="FAIL",error=e.ToString()}));else MessageBox.Show("FloatClip 0.8.0 无法启动："+e.Message,"FloatClip",MessageBoxButton.OK,MessageBoxImage.Error);}
    }
}
