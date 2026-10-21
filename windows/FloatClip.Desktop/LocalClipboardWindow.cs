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
    readonly ClipboardHost host;
    readonly bool floating;
    ClipboardVault vault=>host.Vault;
    NativeClipboardSync? sync=>host.Sync;
    string? syncError=>host.SyncError;
    readonly TextBlock syncStatus=new();
    bool capture{get=>host.Capture;set=>host.Capture=value;}
    readonly string prefsRoot=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"FloatClip","Host");
    readonly StackPanel list=new(),header=new();readonly WrapPanel sources=new();readonly TextBox search=new();readonly TextBlock searchHint=new();
    readonly DockPanel root=new();readonly Border undo=new();
    SolidColorBrush surface=Brushes.White,text=Brushes.Black,muted=Brushes.Gray,line=Brushes.Gray,tint=Brushes.LightBlue,accent=Brushes.Blue;
    string mode="system",source="";bool dark,sourceColors=true,translucent,rendering;
    JsonObject? deleted;Action? closeSwipe;Window? settingsWindow;
    static SolidColorBrush B(string c)=>new((Color)ColorConverter.ConvertFromString(c));
    public LocalClipboardWindow(ClipboardHost host,bool floating)
    {
        this.host=host;this.floating=floating;
        Title=floating?"FloatClip · 悬浮剪贴板":"FloatClip · 管理剪贴板";
        Width=floating?420:680;Height=floating?560:760;MinWidth=floating?340:470;MinHeight=360;
        ShowInTaskbar=!floating;
        if(floating){WindowStyle=WindowStyle.None;ResizeMode=ResizeMode.CanResize;ShowActivated=true;}
        UseLayoutRounding=true;SnapsToDevicePixels=true;
        WindowStartupLocation=WindowStartupLocation.CenterScreen;FontFamily=new FontFamily("Segoe UI, Microsoft YaHei UI");FontSize=15;
        Directory.CreateDirectory(prefsRoot);
        try{mode=File.ReadAllText(Path.Combine(prefsRoot,"theme.txt")).Trim();}catch{}
        try{var p=JsonNode.Parse(File.ReadAllText(Path.Combine(prefsRoot,"clipboard-ui.json")))!;sourceColors=p["sourceColors"]?.GetValue<bool>()??true;translucent=p["translucent"]?.GetValue<bool>()??false;capture=p["capture"]?.GetValue<bool>()??true;}catch{}
        try{var decoder=BitmapDecoder.Create(new Uri("pack://application:,,,/Assets/FloatClip.ico"),BitmapCreateOptions.PreservePixelFormat,BitmapCacheOption.OnLoad);Icon=decoder.Frames.OrderByDescending(frame=>frame.PixelWidth).First();}catch{}
        root.Margin=floating?new(14,10,14,12):new(20,8,20,18);
        if(floating) {
            var border=new Border{Child=root};
            border.SetBinding(Border.BackgroundProperty,new System.Windows.Data.Binding("Background"){Source=this});
            Content=border;
            System.Windows.Shell.WindowChrome.SetWindowChrome(this,new System.Windows.Shell.WindowChrome{
                CaptionHeight=0,ResizeBorderThickness=new(6),GlassFrameThickness=new(0),CornerRadius=new(0)});
            SourceInitialized+=(_,_)=>WindowAppearance.Apply(this);
        } else Content=root;DockPanel.SetDock(header,Dock.Top);root.Children.Add(header);
        syncStatus.FontSize=12;syncStatus.Margin=new(0,8,0,0);syncStatus.TextTrimming=TextTrimming.CharacterEllipsis;
        DockPanel.SetDock(syncStatus,Dock.Bottom);root.Children.Add(syncStatus);
        DockPanel.SetDock(undo,Dock.Bottom);root.Children.Add(undo);
        root.Children.Add(new ScrollViewer{Content=list,VerticalScrollBarVisibility=ScrollBarVisibility.Auto,PanningMode=PanningMode.VerticalOnly});
        search.TextChanged+=(_,_)=>{searchHint.Visibility=search.Text.Length==0?Visibility.Visible:Visibility.Hidden;if(!rendering)Guard(()=>Refresh(false));};
        Activated+=(_,_)=>Guard(()=>Refresh());
        host.Changed+=HostChanged;
        Closing+=(_,e)=>{if(!host.IsExiting){e.Cancel=true;host.RememberPlacement();Hide();}};
        Closed+=(_,_)=>host.Changed-=HostChanged;
        if(floating) {
            Deactivated+=(_,_)=>Dispatcher.BeginInvoke(new Action(()=>{
                if(!host.Pinned&&!IsActive&&!OwnedWindows.Cast<Window>().Any(w=>w.IsVisible))Hide();
            }),DispatcherPriority.Background);
            PreviewKeyDown+=(_,e)=>{if(e.Key==Key.Escape){Hide();e.Handled=true;}};
        }
        PreviewMouseDown+=(_,e)=>{if(e.OriginalSource is DependencyObject target&&closeSwipe!=null&&!InsideClip(target))closeSwipe();};
        ApplyPalette();BuildHeader();Guard(()=>Refresh());
    }
    static bool InsideClip(DependencyObject target){for(var p=target;p!=null;p=p is Visual?VisualTreeHelper.GetParent(p):LogicalTreeHelper.GetParent(p))if(p is FrameworkElement f&&f.Tag as string=="clip")return true;return false;}
    void HostChanged(){if(IsVisible)Guard(()=>Refresh());}
    public void FocusSearch(){search.Focus();}
    void Sharing(){if(sync==null){MessageBox.Show(this,syncError??"共享尚未准备好","FloatClip");return;}new ClipboardSharingWindow(sync,Background,surface,text,muted,tint,accent){Owner=this}.Show();}
    void Copy(string value){host.CopyByUser(value);if(floating&&!host.Pinned)Hide();}
    void ApplyPalette()
    {
        dark=mode=="dark"||mode=="system"&&(Registry.GetValue(@"HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize","AppsUseLightTheme",1) as int?)==0;
        Background=B(dark?"#1A1B26":"#F4F7FC");surface=B(dark?"#24283B":"#FFFFFF");text=B(dark?"#C0CAF5":"#19283F");muted=B(dark?"#9AA5CE":"#596C85");line=B(dark?"#414868":"#DFE7F2");tint=B(dark?"#283457":"#EBF2FF");accent=B(dark?"#7AA2F7":"#245DCE");Foreground=text;syncStatus.Foreground=muted;
        var resources=Application.Current.Resources;
        resources["FloatClip.Surface"]=surface;resources["FloatClip.Text"]=text;
        resources["FloatClip.Line"]=line;resources["FloatClip.Tint"]=tint;resources["FloatClip.Accent"]=accent;
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
        if(floating) {
            var dismiss=IconButton("\uE711","收起剪贴板",Hide);DockPanel.SetDock(dismiss,Dock.Right);top.Children.Add(dismiss);
            var pin=IconButton(host.Pinned?"\uE77A":"\uE718",host.Pinned?"取消固定面板":"固定面板",()=>{host.SetPinned(!host.Pinned);BuildHeader();});
            DockPanel.SetDock(pin,Dock.Right);top.Children.Add(pin);
        }
        var settings=IconButton("\uE713","设置",OpenSettings);DockPanel.SetDock(settings,Dock.Right);top.Children.Add(settings);
        var devices=IconButton("\uE770","我的设备",Sharing);DockPanel.SetDock(devices,Dock.Right);top.Children.Add(devices);
        var brand=new StackPanel{Orientation=Orientation.Horizontal};try{brand.Children.Add(new Image{Source=Icon,Width=28,Height=28,Margin=new(0,0,10,0)});}catch{}
        brand.Children.Add(new TextBlock{Text="剪贴板",FontSize=floating?19:22,FontWeight=FontWeights.Medium,VerticalAlignment=VerticalAlignment.Center});
        if(floating){brand.Cursor=Cursors.SizeAll;brand.MouseLeftButtonDown+=(_,e)=>{if(e.ClickCount==1){DragMove();host.RememberPlacement();e.Handled=true;}};}
        top.Children.Add(brand);header.Children.Add(top);
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
        syncStatus.Text=host.Status+(host.HotkeyError.Length>0?" · "+host.HotkeyError:"");
        var entries=vault.Load()["entries"]!.AsArray();string own=vault.Device()["id"]!.GetValue<string>();rendering=true;
        try{
            closeSwipe=null;
            if(reloadDevices){
                sources.Children.Clear();var shown=new System.Collections.Generic.HashSet<string>();
                void Filter(string id,string name){if(!shown.Add(id))return;var b=Button(name,()=>{source=id;Refresh();});b.Background=source==id?tint:Brushes.Transparent;b.Foreground=source==id?accent:muted;sources.Children.Add(b);}
                Filter("","全部");Filter(own,"本机");
                if(sync!=null)foreach(string key in sync.Pairing.Keys()){var peer=sync.Pairing.Describe(key);if(peer?.DeviceId!=null)Filter(peer.DeviceId.Value.ToString(),peer.Name??"已授权设备");}
                foreach(var g in entries.OfType<JsonObject>().Where(c=>c["source"]!["id"]!.GetValue<string>()!=own).GroupBy(c=>c["source"]!["id"]!.GetValue<string>()))Filter(g.Key,g.First()["source"]!["name"]!.GetValue<string>());
                if(source.Length>0&&!shown.Contains(source))source="";
            }
            list.Children.Clear();string query=search.Text.Trim();var visible=entries.OfType<JsonObject>().Where(c=>(source==""||c["source"]!["id"]!.GetValue<string>()==source)&&new[]{c["text"]!.GetValue<string>(),c["category"]!.GetValue<string>(),c["source"]!["name"]!.GetValue<string>()}.Any(v=>v.Contains(query,StringComparison.CurrentCultureIgnoreCase))).OrderByDescending(c=>c["pinned"]!.GetValue<bool>()).ThenByDescending(c=>c["updatedAt"]!.GetValue<long>()).ToList();
            foreach(var clip in visible)list.Children.Add(Card(clip,own));
            if(visible.Count==0)list.Children.Add(new TextBlock{Text=query.Length==0?"这里还没有记录":"没有匹配的记录",Foreground=muted,Margin=new(0,30,0,0),HorizontalAlignment=HorizontalAlignment.Center});
            UndoBar();
        }finally{rendering=false;}
    }
    FrameworkElement Card(JsonObject clip,string own)
    {
        string id=clip["id"]!.GetValue<string>(),value=clip["text"]!.GetValue<string>(),platform=clip["source"]!["platform"]!.GetValue<string>();bool pinned=clip["pinned"]!.GetValue<bool>();
        var color=B(!sourceColors?(dark?"#8DBBFF":"#245DCE"):platform=="android"?(dark?"#83D9BE":"#14755F"):platform=="ipad"?(dark?"#C8ACEF":"#7650A3"):(dark?"#8DBBFF":"#245DCE"));
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
    public void OpenSettings()
    {
        if(settingsWindow?.IsVisible==true){settingsWindow.Activate();return;}
        var window=new Window{Owner=this,Title="设置",Width=460,Height=660,MinWidth=350,MinHeight=400,Background=Background,Foreground=text,FontFamily=FontFamily,FontSize=15,WindowStartupLocation=WindowStartupLocation.CenterOwner};settingsWindow=window;window.Closed+=(_,_)=>settingsWindow=null;var panel=new StackPanel{Margin=new(24)};
        var title=new DockPanel();var back=IconButton("\uE76B","返回",()=>window.Close());DockPanel.SetDock(back,Dock.Left);title.Children.Add(back);title.Children.Add(new TextBlock{Text="设置",FontSize=21,VerticalAlignment=VerticalAlignment.Center});panel.Children.Add(title);
        var modes=new ComboBox{ItemsSource=new[]{"跟随系统","浅色","深色"},SelectedIndex=mode=="light"?1:mode=="dark"?2:0,Margin=new(0,14,0,10)};StyleInput(modes);modes.SelectionChanged+=(_,_)=>{mode=modes.SelectedIndex==1?"light":modes.SelectedIndex==2?"dark":"system";SavePreferences();ApplyPalette();BuildHeader();Refresh();window.Background=Background;window.Foreground=text;ThemeTree(window);};panel.Children.Add(modes);
        void Option(string label,bool selected,Action<bool> change){var check=new CheckBox{Content=label,IsChecked=selected,Foreground=text,FontSize=15,MinHeight=48,VerticalContentAlignment=VerticalAlignment.Center};check.Click+=(_,_)=>Guard(()=>{change(check.IsChecked==true);SavePreferences();Refresh();});panel.Children.Add(check);}
        Option("收录新复制的文字",capture,v=>capture=v);Option("设备特征色",sourceColors,v=>sourceColors=v);Option("半透明色块",translucent,v=>translucent=v);
        Option("登录 Windows 时启动 FloatClip",WindowsStartup.Enabled,WindowsStartup.SetEnabled);
        panel.Children.Add(new TextBlock{Text="调出悬浮剪贴板的快捷键",Foreground=muted,Margin=new(0,12,0,4)});
        bool recording=false;uint pendingModifiers=0,pendingKey=0;
        var shortcut=new TextBox{Text=host.HotkeyLabel,IsReadOnly=true,Focusable=false,ToolTip="点击更改后按下组合键，再保存"};StyleInput(shortcut);panel.Children.Add(shortcut);
        var shortcutStatus=new TextBlock{Foreground=muted,TextWrapping=TextWrapping.Wrap,FontSize=12,Margin=new(0,4,0,12),Text=host.HotkeyError};panel.Children.Add(shortcutStatus);
        var shortcutActions=new WrapPanel{Margin=new(0,0,0,12)};
        var saveShortcut=Button("保存",()=>{host.SetHotkey(pendingModifiers,pendingKey);shortcut.Text=host.HotkeyLabel;shortcutStatus.Text="已注册并保存";recording=false;shortcut.Focusable=false;});
        saveShortcut.IsEnabled=false;
        shortcutActions.Children.Add(Button("更改",()=>{recording=true;pendingKey=0;saveShortcut.IsEnabled=false;shortcut.Focusable=true;shortcut.Text="请按下组合键";shortcutStatus.Text="推荐 Ctrl + Alt + 字母；Esc 取消";shortcut.Focus();}));
        shortcutActions.Children.Add(saveShortcut);
        shortcutActions.Children.Add(Button("恢复默认",()=>{host.SetHotkey(3,0x56);recording=false;shortcut.Focusable=false;shortcut.Text=host.HotkeyLabel;shortcutStatus.Text="已注册并保存";saveShortcut.IsEnabled=false;}));
        panel.Children.Add(shortcutActions);
        shortcut.PreviewKeyDown+=(_,e)=>{
            if(!recording)return;
            e.Handled=true;var key=e.Key==Key.System?e.SystemKey:e.Key;
            if(key is Key.LeftCtrl or Key.RightCtrl or Key.LeftAlt or Key.RightAlt or Key.LeftShift or Key.RightShift or Key.LWin or Key.RWin)return;
            if(key==Key.Escape){recording=false;shortcut.Focusable=false;shortcut.Text=host.HotkeyLabel;shortcutStatus.Text=host.HotkeyError;saveShortcut.IsEnabled=false;return;}
            var mods=Keyboard.Modifiers;uint value=0;
            if(mods.HasFlag(ModifierKeys.Alt))value|=1;if(mods.HasFlag(ModifierKeys.Control))value|=2;
            if(mods.HasFlag(ModifierKeys.Shift))value|=4;if(mods.HasFlag(ModifierKeys.Windows)||Keyboard.IsKeyDown(Key.LWin)||Keyboard.IsKeyDown(Key.RWin))value|=8;
            pendingModifiers=value;pendingKey=(uint)KeyInterop.VirtualKeyFromKey(key);
            saveShortcut.IsEnabled=DesktopShellPreferences.ValidHotkey(value,pendingKey);
            shortcut.Text=((value&2)!=0?"Ctrl + ":"")+((value&1)!=0?"Alt + ":"")+((value&4)!=0?"Shift + ":"")+((value&8)!=0?"Win + ":"")+key;
            shortcutStatus.Text=saveShortcut.IsEnabled?"点击保存后生效":"请使用包含 Ctrl 或 Alt 的组合键";
        };
        panel.Children.Add(Button("剪贴板共享",Sharing));
        panel.Children.Add(Button("跨网设备（iPad / Web）",()=>new UnifiedWindow{Owner=this}.Show()));
        var actions=new WrapPanel{Margin=new(0,14,0,12)};actions.Children.Add(Button("合并导入",Import));actions.Children.Add(Button("导出备份",Export));panel.Children.Add(actions);
        var update=Button("检查更新",()=>{});update.Click+=async(_,_)=>{update.IsEnabled=false;try{var r=await ShareUpdates.Check();if(r.Build>ShareUpdates.Build&&MessageBox.Show(window,"发现 "+r.Version+"，打开下载地址？","FloatClip",MessageBoxButton.OKCancel)==MessageBoxResult.OK)System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(r.Url){UseShellExecute=true});else update.Content="当前版本 "+ShareUpdates.Version;}catch{update.Content="检查失败，点击重试";}finally{update.IsEnabled=true;}};panel.Children.Add(update);
        panel.Children.Add(new TextBlock{Text="FloatClip "+ShareUpdates.Version+" · build "+ShareUpdates.Build,Foreground=muted,FontSize=14,Margin=new(0,16,0,0)});window.Content=new ScrollViewer{Content=panel,VerticalScrollBarVisibility=ScrollBarVisibility.Auto};window.ShowDialog();
    }
    void Export(){var dialog=new SaveFileDialog{FileName="FloatClip-clipboard-"+DateTime.Now.ToString("yyyy-MM-dd")+".json",Filter="剪贴板备份 (*.json)|*.json",OverwritePrompt=true};if(dialog.ShowDialog(this)!=true)return;var snapshot=vault.Load();File.WriteAllText(dialog.FileName,new JsonObject{["format"]="FloatClip.clipboard",["schema"]=1,["entries"]=snapshot["entries"]!.DeepClone()}.ToJsonString(new(){WriteIndented=true}));}
    void Import(){var dialog=new OpenFileDialog{Filter="剪贴板备份 (*.json)|*.json",CheckFileExists=true};if(dialog.ShowDialog(this)!=true)return;if(new FileInfo(dialog.FileName).Length>ClipboardVault.MaximumBytes)throw new IOException("备份最大 16 MiB");var doc=JsonNode.Parse(File.ReadAllText(dialog.FileName))!;if(doc["format"]?.GetValue<string>()!="FloatClip.clipboard"||doc["schema"]?.GetValue<int>()!=1)throw new IOException("请导入新版 FloatClip 剪贴板备份");var incoming=doc["entries"]!.AsArray();ClipboardVault.Validate(incoming);if(MessageBox.Show(this,"将备份记录合并到本机剪贴板，保留设备来源和分类。继续？","FloatClip",MessageBoxButton.OKCancel)!=MessageBoxResult.OK)return;
        var snapshot=vault.Load();var entries=snapshot["entries"]!.AsArray();foreach(var c in incoming.OfType<JsonObject>()){var old=entries.OfType<JsonObject>().FirstOrDefault(x=>x["id"]!.GetValue<string>()==c["id"]!.GetValue<string>());if(old!=null){if(old["source"]!["id"]!.GetValue<string>()!=c["source"]!["id"]!.GetValue<string>())throw new IOException("备份中存在来源冲突");if(c["updatedAt"]!.GetValue<long>()<=old["updatedAt"]!.GetValue<long>())continue;entries.Remove(old);}entries.Add(c.DeepClone());}vault.Commit(snapshot["revision"]!.GetValue<string>(),entries);Refresh();}
}
