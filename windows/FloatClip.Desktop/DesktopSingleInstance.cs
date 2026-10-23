using System;
using System.IO.Pipes;
using System.Security.Principal;
using System.Threading;
using System.Threading.Tasks;

namespace FloatClip.Desktop;

/** One per-user host owns clipboard events, sync state, tray and global hotkey. */
public sealed class DesktopSingleInstance:IDisposable
{
    readonly Mutex mutex;
    readonly string pipe;
    readonly CancellationTokenSource stop=new();
    readonly bool owner;
    public bool IsOwner=>owner;
    public DesktopSingleInstance()
    {
        string user=WindowsIdentity.GetCurrent().User!.Value;
        pipe="FloatClip.Desktop."+user;
        mutex=new Mutex(true,@"Local\FloatClip.Desktop."+user,out owner);
    }
    public void Signal(byte action)
    {
        try
        {
            using var client=new NamedPipeClientStream(".",pipe,PipeDirection.Out,PipeOptions.CurrentUserOnly);
            client.Connect(2500);client.WriteByte(action);client.Flush();
        }
        catch(TimeoutException){}catch(System.IO.IOException){}
    }
    public void Listen(Action<byte> action)
    {
        if(!owner)return;
        _=Task.Run(async()=>{
            while(!stop.IsCancellationRequested)
            {
                try
                {
                    using var server=new NamedPipeServerStream(pipe,PipeDirection.In,1,PipeTransmissionMode.Byte,
                        PipeOptions.Asynchronous|PipeOptions.CurrentUserOnly);
                    await server.WaitForConnectionAsync(stop.Token);
                    var buffer=new byte[1];
                    using var deadline=CancellationTokenSource.CreateLinkedTokenSource(stop.Token);
                    deadline.CancelAfter(TimeSpan.FromSeconds(2));
                    if(await server.ReadAsync(buffer,deadline.Token)==1)action(buffer[0]);
                }
                catch(OperationCanceledException){}catch(System.IO.IOException){}
            }
        });
    }
    public void Dispose()
    {
        stop.Cancel();
        if(owner)mutex.ReleaseMutex();
        mutex.Dispose();
        // The listener may still be observing the cancellation source.
    }
}
