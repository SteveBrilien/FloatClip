using System;
using System.Drawing;
using System.Runtime.InteropServices;

namespace FloatClip.Desktop;

/// <summary>Captures the external edit focus before FloatClip takes foreground ownership.</summary>
internal sealed class ClipboardPasteTarget
{
    readonly IntPtr window, focus;
    readonly uint thread;
    public Point CaretTop { get; }
    public Point CaretBottom { get; }

    ClipboardPasteTarget(IntPtr window, IntPtr focus, uint thread, Point caretTop, Point caretBottom)
    {
        this.window=window;
        this.focus=focus;
        this.thread=thread;
        CaretTop=caretTop;
        CaretBottom=caretBottom;
    }

    internal static ClipboardPasteTarget? Capture()
    {
        var window=GetForegroundWindow();
        if(window==IntPtr.Zero)return null;
        uint thread=GetWindowThreadProcessId(window,out uint process);
        if(thread==0||process==(uint)Environment.ProcessId)return null;
        var info=ReadThread(thread);
        if(info is not { } state||state.Focus==IntPtr.Zero||state.Caret==IntPtr.Zero)return null;
        var top=new NativePoint{X=state.CaretBounds.Left,Y=state.CaretBounds.Top};
        var bottom=new NativePoint{X=state.CaretBounds.Left,Y=state.CaretBounds.Bottom};
        if(!ClientToScreen(state.Caret,ref top)||!ClientToScreen(state.Caret,ref bottom))return null;
        return new(window,state.Focus,thread,new(top.X,top.Y),new(bottom.X,bottom.Y));
    }

    internal bool RestoreFocus()=>IsWindow(window)&&SetForegroundWindow(window);

    internal bool PasteIfStillFocused()
    {
        if(GetForegroundWindow()!=window||ReadThread(thread)?.Focus!=focus)return false;
        // Avoid turning a still-held Shift, Alt or Ctrl into an unintended shortcut.
        if((GetAsyncKeyState(0x10)&0x8000)!=0||(GetAsyncKeyState(0x11)&0x8000)!=0||
           (GetAsyncKeyState(0x12)&0x8000)!=0||(GetAsyncKeyState(0x5B)&0x8000)!=0||
           (GetAsyncKeyState(0x5C)&0x8000)!=0)return false;
        Input[] keys=[Keyboard(0x11),Keyboard(0x56),Keyboard(0x56,true),Keyboard(0x11,true)];
        return SendInput((uint)keys.Length,keys,Marshal.SizeOf<Input>())==keys.Length;
    }

    static GuiThreadInfo? ReadThread(uint thread)
    {
        var info=new GuiThreadInfo{Size=(uint)Marshal.SizeOf<GuiThreadInfo>()};
        return GetGUIThreadInfo(thread,ref info)?info:null;
    }
    static Input Keyboard(ushort key,bool release=false)=>new(){Type=1,Data=new(){Keyboard=new(){Key=key,Flags=release?2u:0u}}};

    [StructLayout(LayoutKind.Sequential)] struct GuiThreadInfo
    {
        public uint Size,Flags;
        public IntPtr Active,Focus,Capture,MenuOwner,MoveSize,Caret;
        public NativeRect CaretBounds;
    }
    [StructLayout(LayoutKind.Sequential)] struct NativeRect { public int Left,Top,Right,Bottom; }
    [StructLayout(LayoutKind.Sequential)] struct NativePoint { public int X,Y; }
    [StructLayout(LayoutKind.Sequential)] struct Input { public uint Type; public InputData Data; }
    [StructLayout(LayoutKind.Explicit)] struct InputData
    {
        [FieldOffset(0)] public KeyInput Keyboard;
        [FieldOffset(0)] public MouseInput Mouse;
    }
    [StructLayout(LayoutKind.Sequential)] struct KeyInput
    {
        public ushort Key,Scan;
        public uint Flags,Time;
        public UIntPtr Extra;
    }
    [StructLayout(LayoutKind.Sequential)] struct MouseInput
    {
        public int X,Y;
        public uint Data,Flags,Time;
        public UIntPtr Extra;
    }

    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr window,out uint process);
    [DllImport("user32.dll")] static extern bool GetGUIThreadInfo(uint thread,ref GuiThreadInfo info);
    [DllImport("user32.dll")] static extern bool ClientToScreen(IntPtr window,ref NativePoint point);
    [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr window);
    [DllImport("user32.dll")] static extern bool IsWindow(IntPtr window);
    [DllImport("user32.dll")] static extern short GetAsyncKeyState(int key);
    [DllImport("user32.dll")] static extern uint SendInput(uint count,[In] Input[] input,int size);
}
