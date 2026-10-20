using System;
using System.IO;
using System.Linq;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using System.Runtime.InteropServices;
using Microsoft.Win32;
using FloatClip.Share;

namespace FloatClip.Desktop;

/** Local clipboard first. Neither the vault nor the window depends on a web login. */
public sealed class LocalClipboardWindow:Window
{
    readonly ClipboardVault vault;
    NativeClipboardSync? sync;
    string? syncError;
    readonly string prefsRoot=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"FloatClip","Host");
    readonly StackPanel list=new(),header=new();readonly WrapPanel sources=new();readonly TextBox search=new();readonly TextBlock searchHint=new();
    readonly DockPanel root=new();readonly Border undo=new();
    SolidColorBrush surface=Brushes.White,text=Brushes.Black,muted=Brushes.Gray,line=Brushes.Gray,tint=Brushes.LightBlue,accent=Brushes.Blue;
    string mode="system",source="";uint lastSequence;bool dark,sourceColors=true,translucent,capture=true,rendering,exiting,hotkey;
    JsonObject? deleted;HwndSource? hwnd;System.Windows.Forms.NotifyIcon? tray;Action? closeSwipe;
    const int ClipboardMessage=0x031D,HotkeyMessage=0x0312,HotkeyId=703;
    [DllImport("user32.dll")]static extern bool AddClipboardFormatListener(IntPtr hwnd);
    [DllImport("user32.dll")]static extern bool RemoveClipboardFormatListener(IntPtr hwnd);
    [DllImport("user32.dll")]static extern bool RegisterHotKey(IntPtr hwnd,int id,uint modifiers,uint key);
    [DllImport("user32.dll")]static extern bool UnregisterHotKey(IntPtr hwnd,int id);
    [DllImport("user32.dll")]static extern uint GetClipboardSequenceNumber();
    static SolidColorBrush B(string c)=>new((Color)ColorConverter.ConvertFromString(c));
    public LocalClipboardWindow(ClipboardVault vault)
    {
        this.vault=vault;
        try{sync=new NativeClipboardSync(vault,IncomingClipboard);sync.HistoryChanged+=()=>Dispatcher.BeginInvoke(new Action(()=>Guard(()=>Refresh())));}
        catch{syncError="共享状态暂时无法读取，请保留数据并检查存储。";}
        Title="FloatClip";Width=680;Height=760;MinWidth=470;MinHeight=450;
        WindowStartupLocation=WindowStartupLocation.CenterScreen;FontFamily=new FontFamily("Segoe UI, Microsoft YaHei UI");FontSize=15;
        Directory.CreateDirectory(prefsRoot);
        try{mode=File.ReadAllText(Path.Combine(prefsRoot,"theme.txt")).Trim();}catch{}
        try{var p=JsonNode.Parse(File.ReadAllText(Path.Combine(prefsRoot,"clipboard-ui.json")))!;sourceColors=p["sourceColors"]?.GetValue<bool>()??true;translucent=p["translucent"]?.GetValue<bool>()??false;capture=p["capture"]?.GetValue<bool>()??true;}catch{}
        try{Icon=new BitmapImage(new Uri("pack://application:,,,/Assets/FloatClip.ico"));}catch{}
        root.Margin=new(20,8,20,18);Content=root;DockPanel.SetDock(header,Dock.Top);root.Children.Add(header);
        DockPanel.SetDock(undo,Dock.Bottom);root.Children.Add(undo);
        root.Children.Add(new ScrollViewer{Content=list,VerticalScrollBarVisibility=ScrollBarVisibility.Auto,PanningMode=PanningMode.VerticalOnly});
        search.TextChanged+=(_,_)=>{searchHint.Visibility=search.Text.Length==0?Visibility.Visible:Visibility.Hidden;if(!rendering)Guard(()=>Refresh(false));};
        Activated+=(_,_)=>Guard(()=>Refresh());
        SourceInitialized+=(_,_)=>{
            hwnd=HwndSource.FromHwnd(new WindowInteropHelper(this).Handle);hwnd?.AddHook(Hook);
            if(hwnd!=null){AddClipboardFormatListener(hwnd.Handle);hotkey=RegisterHotKey(hwnd.Handle,HotkeyId,0x400C,0x56);}
            try{
                using var stream=Application.GetResourceStream(new Uri("pack://application:,,,/Assets/FloatClip.ico"))!.Stream;
                tray=new System.Windows.Forms.NotifyIcon{Text="FloatClip",Icon=new System.Drawing.Icon(stream),Visible=true};
                var menu=new System.Windows.Forms.ContextMenuStrip();menu.Items.Add("打开剪贴板",null,(_,_)=>Open());menu.Items.Add("暂停 / 恢复收录",null,(_,_)=>{capture=!capture;SavePreferences();});menu.Items.Add("退出",null,(_,_)=>{exiting=true;Close();});tray.ContextMenuStrip=menu;tray.DoubleClick+=(_,_)=>Open();
            }catch{tray?.Dispose();tray=null;}
            ReadClipboard();sync?.Start();
        };
        Closing+=(_,e)=>{if(!exiting&&tray!=null){e.Cancel=true;Hide();}};
        Closed+=(_,_)=>{if(hwnd!=null){RemoveClipboardFormatListener(hwnd.Handle);UnregisterHotKey(hwnd.Handle,HotkeyId);hwnd.RemoveHook(Hook);}tray?.Dispose();sync?.Dispose();};
        PreviewMouseDown+=(_,e)=>{if(e.OriginalSource is DependencyObject target&&closeSwipe!=null&&!InsideClip(target))closeSwipe();};
        ApplyPalette();BuildHeader();Guard(()=>Refresh());
    }
    static bool InsideClip(DependencyObject target){for(var p=target;p!=null;p=p is Visual?VisualTreeHelper.GetParent(p):LogicalTreeHelper.GetParent(p))if(p is FrameworkElement f&&f.Tag as string=="clip")return true;return false;}
    void Open(){Show();WindowState=WindowState.Normal;Activate();}
    IntPtr Hook(IntPtr window,int message,IntPtr w,IntPtr l,ref bool handled){if(message==ClipboardMessage)ReadClipboard();if(message==HotkeyMessage&&w.ToInt32()==HotkeyId){Open();handled=true;}return IntPtr.Zero;}
    void ReadClipboard(int retry=0)
    {
        if(!capture&&sync?.Snapshot().Enabled!=true)return;
        try{uint sequence=GetClipboardSequenceNumber();if(sequence!=0&&sequence==lastSequence)return;if(!Clipboard.ContainsText()||Clipboard.ContainsData("ExcludeClipboardContentFromMonitorProcessing")||ClipboardWrites.IsOwnWrite()){lastSequence=sequence;return;}string value=Clipboard.GetText();if(string.IsNullOrWhiteSpace(value)){lastSequence=sequence;return;}if(capture)vault.AddText(value);lastSequence=sequence;sync?.Copied(value);Refresh();}
        catch(System.Runtime.InteropServices.COMException){if(retry<3){var timer=new DispatcherTimer{Interval=TimeSpan.FromMilliseconds(60)};timer.Tick+=(_,_)=>{timer.Stop();ReadClipboard(retry+1);};timer.Start();}}
        catch{ /* Keep the existing vault intact; report a persistent storage problem on the next open. */ }
    }
    void IncomingClipboard(SyncEntry entry,long grant)
    {
        Dispatcher.BeginInvoke(new Action(()=>{
            if(sync?.MayApply(entry,grant)!=true)return;
            uint current=GetClipboardSequenceNumber();
            if(current!=0&&current!=lastSequence){ReadClipboard();return;}
            if(sync.MayApply(entry,grant))try{ClipboardWrites.Write(entry.Text);lastSequence=GetClipboardSequenceNumber();}catch{/* History is durable; the OS may temporarily own the clipboard. */}
        }));
    }
    void Sharing(){if(sync==null){MessageBox.Show(this,syncError??"共享尚未准备好","FloatClip");return;}new ClipboardSharingWindow(sync,Background,surface,text,muted,tint,accent){Owner=this}.Show();}
    void Copy(string value)=>ClipboardWrites.Write(value);
    void ApplyPalette()
    {
        dark=mode=="dark"||mode=="system"&&(Registry.GetValue(@"HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize","AppsUseLightTheme",1) as int?)==0;
        Background=B(dark?"#151D2B":"#F4F7FC");surface=B(dark?"#223047":"#FFFFFF");text=B(dark?"#EDF3FF":"#1B2940");muted=B(dark?"#B3C1D6":"#5C6C83");line=B(dark?"#344158":"#E6ECF5");tint=B(dark?"#2D456B":"#E9F1FF");accent=B(dark?"#9BBEFF":"#2563EB");Foreground=text;
        search.Background=Brushes.Transparent;search.Foreground=text;search.BorderThickness=new(0);search.Padding=new(0);search.FontSize=16;search.MinHeight=48;search.VerticalContentAlignment=VerticalAlignment.Center;
    }
    FrameworkElement Glyph(string code,int size=24)=>new TextBlock{Text=code,FontFamily=new FontFamily("Segoe MDL2 Assets"),FontSize=size,VerticalAlignment=VerticalAlignment.Center,HorizontalAlignment=HorizontalAlignment.Center,Foreground=muted};
    Button IconButton(string code,string label,Action action)
    {
        var button=Button("",action);button.Content=Glyph(code);button.Width=48;button.Height=48;button.Padding=new(12);button.Margin=new(0);button.Background=Brushes.Transparent;button.ToolTip=label;
        System.Windows.Automation.AutomationProperties.SetName(button,label);return button;
    }
    Button Button(string label,Action action){var b=new Button{Content=label,Background=tint,Foreground=accent,BorderThickness=new(0),Padding=new(12,8,12,8),Margin=new(0,0,8,0),MinHeight=44};b.Click+=(_,_)=>Guard(action);return b;}
    void BuildHeader()
    {
        header.Children.Clear();var top=new DockPanel{LastChildFill=true,Margin=new(0,0,0,12)};
        var settings=IconButton("\uE713","设置",Settings);DockPanel.SetDock(settings,Dock.Right);top.Children.Add(settings);
        var devices=IconButton("\uE770","我的设备",Sharing);DockPanel.SetDock(devices,Dock.Right);top.Children.Add(devices);
        var brand=new StackPanel{Orientation=Orientation.Horizontal};try{brand.Children.Add(new Image{Source=Icon,Width=28,Height=28,Margin=new(0,0,10,0)});}catch{}
        brand.Children.Add(new TextBlock{Text="剪贴板",FontSize=22,FontWeight=FontWeights.Medium,VerticalAlignment=VerticalAlignment.Center});top.Children.Add(brand);header.Children.Add(top);
        var field=new DockPanel{LastChildFill=true};var clear=IconButton("\uE711","清空搜索",()=>{search.Clear();search.Focus();});DockPanel.SetDock(clear,Dock.Right);field.Children.Add(clear);
        var glass=new Border{Width=48,Child=Glyph("\uE721")};DockPanel.SetDock(glass,Dock.Left);field.Children.Add(glass);
        if(search.Parent is Panel old)old.Children.Remove(search);
        if(searchHint.Parent is Panel previous)previous.Children.Remove(searchHint);searchHint.Text="搜索剪贴板";searchHint.Foreground=muted;searchHint.VerticalAlignment=VerticalAlignment.Center;searchHint.IsHitTestVisible=false;searchHint.FontSize=16;var input=new Grid();input.Children.Add(search);input.Children.Add(searchHint);searchHint.Visibility=search.Text.Length==0?Visibility.Visible:Visibility.Hidden;
        field.Children.Add(input);header.Children.Add(new Border{Background=surface,CornerRadius=new(13),Child=field});
        sources.Margin=new(0,12,0,12);if(sources.Parent is Panel parent)parent.Children.Remove(sources);header.Children.Add(sources);
    }
    void StyleInput(Control control){control.Background=surface;control.Foreground=text;control.BorderBrush=line;control.Padding=new(10,8,10,8);control.MinHeight=44;}
    void Guard(Action action){try{action();}catch(Exception e){MessageBox.Show(this,e is IOException?e.Message:"本机操作未完成，请保留数据并重试。","FloatClip",MessageBoxButton.OK,MessageBoxImage.Information);}}
    void Refresh(bool reloadDevices=true)
    {
        var entries=vault.Load()["entries"]!.AsArray();string own=vault.Device()["id"]!.GetValue<string>();rendering=true;
        try{
            closeSwipe=null;
            if(reloadDevices){sources.Children.Clear();void Filter(string id,string name){var b=Button(name,()=>{source=id;Refresh();});b.Background=source==id?tint:Brushes.Transparent;b.Foreground=source==id?accent:muted;sources.Children.Add(b);}Filter("","全部");Filter(own,"本机");foreach(var g in entries.OfType<JsonObject>().Where(c=>c["source"]!["id"]!.GetValue<string>()!=own).GroupBy(c=>c["source"]!["id"]!.GetValue<string>()))Filter(g.Key,g.First()["source"]!["name"]!.GetValue<string>());}
            list.Children.Clear();string query=search.Text.Trim();var visible=entries.OfType<JsonObject>().Where(c=>(source==""||c["source"]!["id"]!.GetValue<string>()==source)&&new[]{c["text"]!.GetValue<string>(),c["category"]!.GetValue<string>(),c["source"]!["name"]!.GetValue<string>()}.Any(v=>v.Contains(query,StringComparison.CurrentCultureIgnoreCase))).OrderByDescending(c=>c["pinned"]!.GetValue<bool>()).ThenByDescending(c=>c["updatedAt"]!.GetValue<long>()).ToList();
            foreach(var clip in visible)list.Children.Add(Card(clip,own));
            if(visible.Count==0)list.Children.Add(new TextBlock{Text=query.Length==0?"这里还没有记录":"没有匹配的记录",Foreground=muted,Margin=new(0,30,0,0),HorizontalAlignment=HorizontalAlignment.Center});
            UndoBar();
        }finally{rendering=false;}
    }
    FrameworkElement Card(JsonObject clip,string own)
    {
        string id=clip["id"]!.GetValue<string>(),value=clip["text"]!.GetValue<string>(),platform=clip["source"]!["platform"]!.GetValue<string>();bool pinned=clip["pinned"]!.GetValue<bool>();
        var color=B(!sourceColors?(dark?"#9BBEFF":"#2563EB"):platform=="android"?(dark?"#83D9BE":"#14755F"):platform=="ipad"?(dark?"#C8ACEF":"#7650A3"):(dark?"#9BBEFF":"#2563EB"));
        Color mix(Color a,Color b,double f)=>Color.FromRgb((byte)(a.R*(1-f)+b.R*f),(byte)(a.G*(1-f)+b.G*f),(byte)(a.B*(1-f)+b.B*f));
        var row=new Grid{Tag="clip",Margin=new(0,0,0,10),ClipToBounds=true};
        var actionGrid=new Grid{Width=148,HorizontalAlignment=HorizontalAlignment.Right,Visibility=Visibility.Hidden};actionGrid.ColumnDefinitions.Add(new());actionGrid.ColumnDefinitions.Add(new());
        var pin=Button(pinned?"取消置顶":"置顶",()=>Edit(id,c=>c["pinned"]=!pinned));pin.Content=ActionLabel(pinned?"\uE77A":"\uE718",pinned?"取消置顶":"置顶");pin.Padding=new(2);pin.FontSize=13;pin.Margin=new(6,0,3,0);actionGrid.Children.Add(pin);
        var remove=Button("删除",()=>Delete(clip));remove.Content=ActionLabel("\uE74D","删除");remove.FontSize=13;remove.Padding=new(2);remove.Foreground=B(dark?"#FFB5C2":"#B62E48");Grid.SetColumn(remove,1);remove.Margin=new(3,0,0,0);actionGrid.Children.Add(remove);row.Children.Add(actionGrid);
        var scale=new ScaleTransform(1,1);var front=new Border{MinHeight=88,CornerRadius=new(15),Background=translucent?new SolidColorBrush(mix(surface.Color,color.Color,dark ? .12 : .075)):surface,RenderTransform=scale,RenderTransformOrigin=new(.5,.5)};
        var body=new Grid();front.Child=body;body.Children.Add(new TextBlock{Text=value,TextWrapping=TextWrapping.Wrap,TextTrimming=TextTrimming.CharacterEllipsis,MaxHeight=78,FontSize=16,LineHeight=25,Foreground=text,VerticalAlignment=VerticalAlignment.Center,Margin=new(16,19,38,19)});
        var markers=new StackPanel{VerticalAlignment=VerticalAlignment.Top,HorizontalAlignment=HorizontalAlignment.Right,Margin=new(0,11,10,0),Opacity=.68};
        void Mark(string code){var glyph=Glyph(code,14);((TextBlock)glyph).Foreground=color;glyph.Margin=new(0,0,0,6);markers.Children.Add(glyph);}
        if(pinned)Mark("\uE718");if(clip["source"]!["id"]!.GetValue<string>()!=own)Mark(platform=="android"?"\uE8EA":platform=="ipad"?"\uE70A":"\uE770");body.Children.Add(markers);
        var translate=new TranslateTransform();var slide=new Grid{RenderTransform=translate};slide.Children.Add(front);row.Children.Add(slide);
        bool opened=false,moved=false,held=false;double start=0;Point down=new();var hold=new DispatcherTimer{Interval=TimeSpan.FromMilliseconds(520)};
        void Press(bool on){double target=on&&SystemParameters.ClientAreaAnimation ? .98 : 1;var d=TimeSpan.FromMilliseconds(SystemParameters.ClientAreaAnimation?(on?90:160):0);scale.BeginAnimation(ScaleTransform.ScaleXProperty,new DoubleAnimation(target,d));scale.BeginAnimation(ScaleTransform.ScaleYProperty,new DoubleAnimation(target,d));}
        void Reveal(bool show){opened=show;actionGrid.Visibility=show?Visibility.Visible:Visibility.Hidden;translate.BeginAnimation(TranslateTransform.XProperty,null);translate.X=show?-148:0;closeSwipe=show?()=>Reveal(false):null;}
        var menu=new ContextMenu{Background=surface,Foreground=text};void Item(string label,Action action){var item=new MenuItem{Header=label,Foreground=text};item.Click+=(_,_)=>Guard(action);menu.Items.Add(item);}
        Item("查看全文",()=>FullText(value));Item(pinned?"取消置顶":"置顶",()=>Edit(id,c=>c["pinned"]=!pinned));Item("更改分类",()=>ChangeCategory(clip));Item("删除",()=>Delete(clip));front.ContextMenu=menu;
        hold.Tick+=(_,_)=>{hold.Stop();held=true;Press(false);front.ReleaseMouseCapture();menu.PlacementTarget=front;menu.IsOpen=true;};front.Unloaded+=(_,_)=>hold.Stop();
        front.MouseLeftButtonDown+=(_,e)=>{if(!opened)closeSwipe?.Invoke();down=e.GetPosition(row);start=translate.X;moved=false;held=false;front.CaptureMouse();Press(true);hold.Start();e.Handled=true;};
        front.MouseMove+=(_,e)=>{if(!front.IsMouseCaptured)return;var p=e.GetPosition(row);double dx=p.X-down.X,dy=p.Y-down.Y;if(Math.Max(Math.Abs(dx),Math.Abs(dy))>8){moved=true;hold.Stop();Press(false);}if(Math.Abs(dx)>Math.Abs(dy)*1.2&&Math.Abs(dx)>9){actionGrid.Visibility=Visibility.Visible;translate.X=Math.Clamp(start+dx,-148,0);}};
        front.MouseLeftButtonUp+=(_,e)=>{if(!front.IsMouseCaptured)return;hold.Stop();front.ReleaseMouseCapture();Press(false);if(held)return;if(moved){Reveal(translate.X<-52);return;}if(opened){Reveal(false);return;}Guard(()=>Copy(value));e.Handled=true;};
        front.LostMouseCapture+=(_,_)=>{hold.Stop();Press(false);if(moved&&!held)Reveal(translate.X<-52);};front.Focusable=true;
        System.Windows.Automation.AutomationProperties.SetName(front,value+"，来源："+clip["source"]!["name"]!.GetValue<string>());
        front.KeyDown+=(_,e)=>{if(e.Key==Key.Left){Reveal(true);pin.Focus();e.Handled=true;}else if(e.Key is Key.Right or Key.Escape){Reveal(false);front.Focus();e.Handled=true;}else if(e.Key is Key.Enter or Key.Space){Guard(()=>Copy(value));e.Handled=true;}};
        return row;
    }
    FrameworkElement ActionLabel(string code,string label){var p=new StackPanel{VerticalAlignment=VerticalAlignment.Center,HorizontalAlignment=HorizontalAlignment.Center};p.Children.Add(Glyph(code));p.Children.Add(new TextBlock{Text=label,FontSize=13,HorizontalAlignment=HorizontalAlignment.Center,Margin=new(0,7,0,0)});return p;}
    void Delete(JsonObject clip){deleted=(JsonObject)clip.DeepClone();Edit(clip["id"]!.GetValue<string>(),null);}
    void UndoBar(){undo.Child=null;undo.Visibility=deleted==null?Visibility.Collapsed:Visibility.Visible;if(deleted==null)return;var row=new DockPanel();var button=Button("撤销",()=>{var snap=vault.Load();var clips=snap["entries"]!.AsArray();if(!clips.OfType<JsonObject>().Any(c=>c["id"]!.GetValue<string>()==deleted!["id"]!.GetValue<string>()))clips.Add(deleted!.DeepClone());vault.Commit(snap["revision"]!.GetValue<string>(),clips);deleted=null;Refresh();});DockPanel.SetDock(button,Dock.Right);row.Children.Add(button);row.Children.Add(new TextBlock{Text="记录已删除",Foreground=muted,VerticalAlignment=VerticalAlignment.Center});undo.Child=row;}
    void Edit(string id,Action<JsonObject>? edit){var snap=vault.Load();var clips=snap["entries"]!.AsArray();var c=clips.OfType<JsonObject>().FirstOrDefault(n=>n["id"]!.GetValue<string>()==id);if(c==null)return;if(edit==null)clips.Remove(c);else{edit(c);c["updatedAt"]=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();}vault.Commit(snap["revision"]!.GetValue<string>(),clips);Refresh();}
    void SavePreferences(){File.WriteAllText(Path.Combine(prefsRoot,"theme.txt"),mode);File.WriteAllText(Path.Combine(prefsRoot,"clipboard-ui.json"),new JsonObject{["sourceColors"]=sourceColors,["translucent"]=translucent,["capture"]=capture}.ToJsonString());}
    void FullText(string value){var box=new TextBox{Text=value,IsReadOnly=true,AcceptsReturn=true,TextWrapping=TextWrapping.Wrap,VerticalScrollBarVisibility=ScrollBarVisibility.Auto};StyleInput(box);new Window{Owner=this,Title="剪贴板全文",Width=550,Height=400,Background=Background,Foreground=text,Content=box,WindowStartupLocation=WindowStartupLocation.CenterOwner}.ShowDialog();}
    void ChangeCategory(JsonObject clip){var window=new Window{Owner=this,Title="分类",Width=360,Height=180,Background=Background,WindowStartupLocation=WindowStartupLocation.CenterOwner};var panel=new StackPanel{Margin=new(20)};var field=new TextBox{Text=clip["category"]!.GetValue<string>(),MaxLength=80};StyleInput(field);panel.Children.Add(field);panel.Children.Add(Button("保存",()=>{Edit(clip["id"]!.GetValue<string>(),c=>c["category"]=string.IsNullOrWhiteSpace(field.Text)?"未分类":field.Text.Trim());window.Close();}));window.Content=panel;window.ShowDialog();}
    void ThemeTree(DependencyObject node){
        if(node is Control c){c.Foreground=text;if(c is TextBox or ComboBox){c.Background=surface;c.BorderBrush=line;}if(c is Button b){b.Background=tint;b.Foreground=accent;}}
        if(node is TextBlock t)t.Foreground=text;
        for(int i=0;i<VisualTreeHelper.GetChildrenCount(node);i++)ThemeTree(VisualTreeHelper.GetChild(node,i));
    }
    void Settings()
    {
        var window=new Window{Owner=this,Title="设置",Width=430,Height=510,MinWidth=350,MinHeight=400,Background=Background,Foreground=text,FontFamily=FontFamily,FontSize=15,WindowStartupLocation=WindowStartupLocation.CenterOwner};var panel=new StackPanel{Margin=new(24)};
        var title=new DockPanel();var back=IconButton("\uE76B","返回",()=>window.Close());DockPanel.SetDock(back,Dock.Left);title.Children.Add(back);title.Children.Add(new TextBlock{Text="设置",FontSize=21,VerticalAlignment=VerticalAlignment.Center});panel.Children.Add(title);
        var modes=new ComboBox{ItemsSource=new[]{"跟随系统","浅色","深色"},SelectedIndex=mode=="light"?1:mode=="dark"?2:0,Margin=new(0,14,0,10)};StyleInput(modes);modes.SelectionChanged+=(_,_)=>{mode=modes.SelectedIndex==1?"light":modes.SelectedIndex==2?"dark":"system";SavePreferences();ApplyPalette();BuildHeader();Refresh();window.Background=Background;window.Foreground=text;ThemeTree(window);};panel.Children.Add(modes);
        void Option(string label,bool selected,Action<bool> change){var check=new CheckBox{Content=label,IsChecked=selected,Foreground=text,FontSize=15,MinHeight=48,VerticalContentAlignment=VerticalAlignment.Center};check.Click+=(_,_)=>Guard(()=>{change(check.IsChecked==true);SavePreferences();Refresh();});panel.Children.Add(check);}
        Option("收录新复制的文字",capture,v=>capture=v);Option("设备特征色",sourceColors,v=>sourceColors=v);Option("半透明色块",translucent,v=>translucent=v);
        panel.Children.Add(Button("剪贴板共享",Sharing));
        panel.Children.Add(Button("iPad 与跨网设备",()=>new UnifiedWindow{Owner=this}.Show()));
        var actions=new WrapPanel{Margin=new(0,14,0,12)};actions.Children.Add(Button("合并导入",Import));actions.Children.Add(Button("导出备份",Export));panel.Children.Add(actions);
        var update=Button("检查更新",()=>{});update.Click+=async(_,_)=>{update.IsEnabled=false;try{var r=await ShareUpdates.Check();if(r.Build>ShareUpdates.Build&&MessageBox.Show(window,"发现 "+r.Version+"，打开下载地址？","FloatClip",MessageBoxButton.OKCancel)==MessageBoxResult.OK)System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(r.Url){UseShellExecute=true});else update.Content="当前版本 0.7.1";}catch{update.Content="检查失败，点击重试";}finally{update.IsEnabled=true;}};panel.Children.Add(update);
        panel.Children.Add(new TextBlock{Text=hotkey?"快捷键 Win + Shift + V":"可从系统托盘打开剪贴板",Foreground=muted,FontSize=14,Margin=new(0,16,0,0)});window.Content=new ScrollViewer{Content=panel,VerticalScrollBarVisibility=ScrollBarVisibility.Auto};window.ShowDialog();
    }
    void Export(){var dialog=new SaveFileDialog{FileName="FloatClip-clipboard-"+DateTime.Now.ToString("yyyy-MM-dd")+".json",Filter="剪贴板备份 (*.json)|*.json",OverwritePrompt=true};if(dialog.ShowDialog(this)!=true)return;var snapshot=vault.Load();File.WriteAllText(dialog.FileName,new JsonObject{["format"]="FloatClip.clipboard",["schema"]=1,["entries"]=snapshot["entries"]!.DeepClone()}.ToJsonString(new(){WriteIndented=true}));}
    void Import(){var dialog=new OpenFileDialog{Filter="剪贴板备份 (*.json)|*.json",CheckFileExists=true};if(dialog.ShowDialog(this)!=true)return;if(new FileInfo(dialog.FileName).Length>ClipboardVault.MaximumBytes)throw new IOException("备份最大 16 MiB");var doc=JsonNode.Parse(File.ReadAllText(dialog.FileName))!;if(doc["format"]?.GetValue<string>()!="FloatClip.clipboard"||doc["schema"]?.GetValue<int>()!=1)throw new IOException("请导入新版 FloatClip 剪贴板备份");var incoming=doc["entries"]!.AsArray();ClipboardVault.Validate(incoming);if(MessageBox.Show(this,"将备份记录合并到本机剪贴板，保留设备来源和分类。继续？","FloatClip",MessageBoxButton.OKCancel)!=MessageBoxResult.OK)return;
        var snapshot=vault.Load();var entries=snapshot["entries"]!.AsArray();foreach(var c in incoming.OfType<JsonObject>()){var old=entries.OfType<JsonObject>().FirstOrDefault(x=>x["id"]!.GetValue<string>()==c["id"]!.GetValue<string>());if(old!=null){if(old["source"]!["id"]!.GetValue<string>()!=c["source"]!["id"]!.GetValue<string>())throw new IOException("备份中存在来源冲突");if(c["updatedAt"]!.GetValue<long>()<=old["updatedAt"]!.GetValue<long>())continue;entries.Remove(old);}entries.Add(c.DeepClone());}vault.Commit(snapshot["revision"]!.GetValue<string>(),entries);Refresh();}
}
