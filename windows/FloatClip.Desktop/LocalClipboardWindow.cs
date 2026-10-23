using System;
using System.IO;
using System.Linq;
using System.Collections.Generic;
using System.Globalization;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Markup;
using System.Windows.Threading;
using System.Runtime.InteropServices;
using Microsoft.Win32;
using FloatClip.Share;
using FloatClip.Desktop.Design;
using FloatClip.Desktop.Services;
using FloatClip.Desktop.ViewModels;
using FloatClip.Desktop.Views;

namespace FloatClip.Desktop;

/** Local clipboard first. Neither the vault nor the window depends on a web login. */
public sealed partial class LocalClipboardWindow:Window
{
    readonly ClipboardHost host;
    readonly bool floating;
    ClipboardVault vault=>host.Vault;
    NativeClipboardSync? sync=>host.Sync;
    string? syncError=>host.SyncError;
    readonly TextBlock syncStatus=new();
    bool capture{get=>host.Capture;set=>host.Capture=value;}
    string prefsRoot=>host.PreferencesRoot;
    readonly StackPanel list=new(),header=new();readonly WrapPanel sources=new();readonly TextBox search;readonly TextBlock searchHint;
    readonly ClipboardSearchView searchView;readonly ClipboardPanelViewModel viewModel;readonly DesignPreferences designPreferences;
    readonly CategoryService categories;
    Dictionary<string,string> categoryColors=new(),categoryIcons=new();
    readonly Dictionary<string,TextBlock> copyBadges=new();
    readonly DispatcherTimer copyFeedbackTimer=new();
    bool pendingCopyRefresh;
    readonly DockPanel root=new();readonly Border undo=new();
    readonly Dictionary<string,Window> auxiliaryWindows=new();
    readonly DispatcherTimer searchTimer=new(){Interval=TimeSpan.FromMilliseconds(120)};
    SolidColorBrush surface=Brushes.White,text=Brushes.Black,muted=Brushes.Gray,line=Brushes.Gray,tint=Brushes.LightBlue,accent=Brushes.Blue;
    string mode="system",source="",kindFilter="";bool dark,sourceColors=true,translucent,rendering;
    List<JsonObject>? deleted;Action? closeSwipe;
    public LocalClipboardWindow(ClipboardHost host,bool floating)
    {
        this.host=host;this.floating=floating;
        designPreferences=DesignPreferences.Load(prefsRoot);
        categories=new CategoryService(host.Vault,prefsRoot);
        viewModel=new ClipboardPanelViewModel(new ClipboardLibraryService(host.Vault));
        viewModel.OperationFailed+=error=>{host.ReportUiFailure(error);if(host.IsIsolated)System.Runtime.ExceptionServices.ExceptionDispatchInfo.Capture(error).Throw();};
        searchView=new ClipboardSearchView{DataContext=viewModel};search=searchView.InputControl;searchHint=searchView.Hint;
        viewModel.HistoryChanged+=()=>{deleted=null;Refresh();};
        copyFeedbackTimer.Interval=((Duration)Application.Current.FindResource("FloatClip.Motion.CopyFeedback")).TimeSpan;
        copyFeedbackTimer.Tick+=(_,_)=>{copyFeedbackTimer.Stop();viewModel.ClearCopy();foreach(var badge in copyBadges.Values)badge.Visibility=Visibility.Collapsed;if(pendingCopyRefresh){pendingCopyRefresh=false;Guard(()=>Refresh());}DismissAfterCopy();};
        Opacity=1;
        Title=floating?"FloatClip · 悬浮剪贴板":"FloatClip · 管理剪贴板";
        Width=floating?520:760;Height=floating?650:760;MinWidth=420;MinHeight=420;
        ShowInTaskbar=!floating;
        if(floating){WindowStyle=WindowStyle.None;ResizeMode=ResizeMode.CanResize;ShowActivated=true;AllowsTransparency=designPreferences.ClipboardBackground=="transparent";}
        ClipboardBackgroundService.Configure(this,designPreferences);
        UseLayoutRounding=true;SnapsToDevicePixels=true;
        WindowStartupLocation=WindowStartupLocation.CenterScreen;SetResourceReference(FontFamilyProperty,"FloatClip.FontFamily");SetResourceReference(FontSizeProperty,"FloatClip.Font.Body");
        Directory.CreateDirectory(prefsRoot);
        try{mode=File.ReadAllText(Path.Combine(prefsRoot,"theme.txt")).Trim();}catch{}
        try{var p=JsonNode.Parse(File.ReadAllText(Path.Combine(prefsRoot,"clipboard-ui.json")))!;sourceColors=p["sourceColors"]?.GetValue<bool>()??true;translucent=p["translucent"]?.GetValue<bool>()??false;capture=p["capture"]?.GetValue<bool>()??true;clipFontSize=Math.Clamp(p["clipFontSize"]?.GetValue<double>()??14,12,18);cardSpacing=Math.Clamp(p["cardSpacing"]?.GetValue<double>()??8,4,18);panelWidth=Math.Clamp(p["panelWidth"]?.GetValue<double>()??520,420,900);expandedNavigation=p["expandedNavigation"]?.GetValue<bool>()??false;}catch{}
        Width=panelWidth;
        try{var decoder=BitmapDecoder.Create(new Uri("pack://application:,,,/FloatClip;component/Assets/FloatClip.ico"),BitmapCreateOptions.PreservePixelFormat,BitmapCacheOption.OnLoad);Icon=decoder.Frames.OrderByDescending(frame=>frame.PixelWidth).First();}catch{}
        root.Margin=(Thickness)Application.Current.FindResource("FloatClip.Padding.Page");
        if(floating) {
            var border=new Border{Child=root};
            border.Background=Brushes.Transparent;
            Content=border;
            System.Windows.Shell.WindowChrome.SetWindowChrome(this,new System.Windows.Shell.WindowChrome{
                CaptionHeight=0,ResizeBorderThickness=new(6),GlassFrameThickness=new(0),CornerRadius=new(0)});
            SourceInitialized+=(_,_)=>{WindowAppearance.Apply(this);WindowAppearance.Theme(this,dark);ApplyClipboardBackground();};
        } else Content=root;DockPanel.SetDock(header,Dock.Top);root.Children.Add(header);
        syncStatus.FontSize=12;syncStatus.Margin=new(0,8,0,0);syncStatus.TextTrimming=TextTrimming.CharacterEllipsis;
        DockPanel.SetDock(syncStatus,Dock.Bottom);root.Children.Add(syncStatus);
        DockPanel.SetDock(undo,Dock.Bottom);root.Children.Add(undo);
        root.Children.Add(new ScrollViewer{Content=list,VerticalScrollBarVisibility=ScrollBarVisibility.Auto,PanningMode=PanningMode.VerticalOnly});
        System.Windows.Automation.AutomationProperties.SetName(search,"搜索剪贴板");
        System.Windows.Automation.AutomationProperties.SetAutomationId(search,"ClipboardSearch");
        System.Windows.Automation.AutomationProperties.SetAutomationId(list,"ClipboardList");
        searchTimer.Tick+=(_,_)=>{searchTimer.Stop();Guard(()=>Refresh(false));};
        search.TextChanged+=(_,_)=>{searchHint.Visibility=search.Text.Length==0?Visibility.Visible:Visibility.Hidden;if(!rendering){searchTimer.Stop();searchTimer.Start();}};
        Activated+=(_,_)=>Guard(()=>{UpdateSystemTheme();Refresh();});
        SystemEvents.UserPreferenceChanged+=SystemThemeChanged;
        host.Changed+=HostChanged;
        host.Updates.Changed+=UpdateChanged;
        Closing+=(_,e)=>{if(!host.IsExiting){e.Cancel=true;host.RememberPlacement();Hide();}};
        Closed+=(_,_)=>{host.Changed-=HostChanged;host.Updates.Changed-=UpdateChanged;SystemEvents.UserPreferenceChanged-=SystemThemeChanged;searchTimer.Stop();copyFeedbackTimer.Stop();};
        IsVisibleChanged+=(_,_)=>{if(!IsVisible){copyFeedbackTimer.Stop();viewModel.ClearCopy();pendingCopyRefresh=false;}};
        if(floating) {
            Deactivated+=(_,_)=>Dispatcher.BeginInvoke(new Action(()=>{
                if(currentPage==typeof(ClipboardPage)&&!host.Pinned&&!IsActive&&!OwnedWindows.Cast<Window>().Any(w=>w.IsVisible))Hide();
            }),DispatcherPriority.Background);
            KeyDown+=(_,e)=>{if(e.Key==Key.Escape&&!e.Handled){if(currentPage!=typeof(ClipboardPage))Navigate(typeof(ClipboardPage));else if(closeSwipe!=null)closeSwipe();else if(search.Text.Length>0)search.Clear();else Hide();e.Handled=true;}};
        }
        PreviewMouseDown+=(_,e)=>{if(e.OriginalSource is not DependencyObject target)return;if(closeSwipe!=null&&!InsideClip(target))closeSwipe();if(search.IsKeyboardFocusWithin&&!InsideSearch(target)){Keyboard.ClearFocus();FocusManager.SetFocusedElement(this,null);}};
        ApplyPalette();BuildHeader();BuildShell();Guard(()=>Refresh());
    }
    static bool InsideClip(DependencyObject target){for(var p=target;p!=null;p=p is Visual?VisualTreeHelper.GetParent(p):LogicalTreeHelper.GetParent(p))if(p is FrameworkElement f&&f.Tag as string=="clip")return true;return false;}
    bool InsideSearch(DependencyObject target){for(var p=target;p!=null;p=p is Visual?VisualTreeHelper.GetParent(p):LogicalTreeHelper.GetParent(p))if(ReferenceEquals(p,search))return true;return false;}
    void HostChanged(){if(IsVisible){if(copyFeedbackTimer.IsEnabled)pendingCopyRefresh=true;else Guard(()=>Refresh());}RenderUpdates();}
    public void FocusSearch(){Navigate(typeof(ClipboardPage));search.Focus();}
    public void OpenDevices()=>Sharing();
    bool ActivateAuxiliary(string key)
    {
        if(!auxiliaryWindows.TryGetValue(key,out var window))return false;
        if(window.WindowState==WindowState.Minimized)window.WindowState=WindowState.Normal;
        window.Show();window.Activate();return true;
    }
    void ShowAuxiliary(string key,Window window)
    {
        ClipboardBackgroundService.Configure(window,designPreferences,caption:true);
        window.Owner=this;window.ShowInTaskbar=false;window.FontFamily=FontFamily;window.FontSize=FontSize;
        window.WindowStartupLocation=WindowStartupLocation.CenterOwner;window.UseLayoutRounding=true;
        window.SetResourceReference(ForegroundProperty,"FloatClip.Text");
        auxiliaryWindows[key]=window;
        window.Closed+=(_,_)=>{if(auxiliaryWindows.TryGetValue(key,out var current)&&ReferenceEquals(current,window))auxiliaryWindows.Remove(key);};
        window.SourceInitialized+=(_,_)=>{WindowAppearance.Apply(window);WindowAppearance.Theme(window,dark);ClipboardBackgroundService.Apply(window,designPreferences);};window.Show();window.Activate();
    }
    void Sharing()=>Navigate(typeof(DevicesPage));
    void DismissAfterCopy(){if(floating&&!host.Pinned&&!OwnedWindows.Cast<Window>().Any(w=>w.IsVisible))Hide();}
    void Copy(string value)=>CopyEntry(value,"");
    void CopyEntry(string value,string id){host.CopyByUser(value);CopiedFeedback(id);}
    void CopiedFeedback(string id){viewModel.Copied(id);foreach(var pair in copyBadges)pair.Value.Visibility=pair.Key==id?Visibility.Visible:Visibility.Collapsed;syncStatus.Text="已复制";copyFeedbackTimer.Stop();copyFeedbackTimer.Start();}
    bool SystemIsDark()=> (Registry.GetValue(@"HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize","AppsUseLightTheme",1) as int?)==0;
    void SystemThemeChanged(object sender,UserPreferenceChangedEventArgs e)
    {
        if(!Dispatcher.HasShutdownStarted)Dispatcher.BeginInvoke(new Action(()=>Guard(()=>{UpdateSystemTheme();ApplyClipboardBackground();})));
    }
    void UpdateSystemTheme(){if(mode=="system"&&dark!=SystemIsDark()){ApplyPalette();BuildHeader();Refresh();}}
    void ApplyPalette()
    {
        dark=DesignTheme.Apply(mode);Background=DesignTokens.Brush("Background");surface=DesignTokens.Brush("Surface");text=DesignTokens.Brush("TextPrimary");muted=DesignTokens.Brush("TextSecondary");line=DesignTokens.Brush("Border");tint=DesignTokens.Brush("Selected");accent=DesignTokens.Brush("Accent");Foreground=text;syncStatus.Foreground=muted;
        WindowAppearance.Theme(this,dark);foreach(var window in auxiliaryWindows.Values)WindowAppearance.Theme(window,dark);
        ApplyClipboardBackground();
    }
    string ApplyClipboardBackground(){ClipboardBackgroundService.RefreshAll(designPreferences);return ClipboardBackgroundService.Apply(this,designPreferences);}
    FrameworkElement Glyph(string code,int size=18){var glyph=new TextBlock{Text=code,Tag="glyph",FontFamily=new FontFamily("Segoe Fluent Icons, Segoe MDL2 Assets"),FontSize=size,VerticalAlignment=VerticalAlignment.Center,HorizontalAlignment=HorizontalAlignment.Center};glyph.SetResourceReference(TextBlock.ForegroundProperty,"FloatClip.Muted");return glyph;}
    Button IconButton(string code,string label,Action action)
    {
        var button=Button("",action);((Wpf.Ui.Controls.Button)button).Appearance=Wpf.Ui.Controls.ControlAppearance.Transparent;button.Content=Glyph(code,16);button.Tag="icon";button.Width=30;button.Height=30;button.MinHeight=30;button.Padding=new(6);button.Margin=new(0);DesignTokens.Button(button,"Toolbar");button.BorderThickness=new(0);button.ToolTip=label;button.Width=DesignTokens.Number("Hit.Control");button.Height=DesignTokens.Number("Hit.Control");button.MinHeight=DesignTokens.Number("Hit.Control");
        System.Windows.Automation.AutomationProperties.SetName(button,label);return button;
    }
    Button Button(string label,Action action){var b=new Wpf.Ui.Controls.Button{Content=label,Margin=new(0,0,DesignTokens.Number("Space.8"),0),HorizontalAlignment=HorizontalAlignment.Left};DesignTokens.Button(b);b.Click+=(_,_)=>Guard(action);return b;}
    void BuildHeader()
    {
        header.Children.Clear();
        var heading=new TextBlock{Text="剪贴板",FontSize=DesignTokens.Number("Font.Title"),FontWeight=FontWeights.Medium,Margin=new(0,0,0,12)};header.Children.Add(heading);
        if(searchView.Parent is Panel oldParent)oldParent.Children.Remove(searchView);header.Children.Add(searchView);
        var filterBar=new DockPanel{LastChildFill=true,Margin=new(0,10,0,10)};
        var clearAll=Button("全部清除",ClearUnprotectedHistory);System.Windows.Automation.AutomationProperties.SetAutomationId(clearAll,"ClearUnprotected");clearAll.Margin=new(DesignTokens.Number("Space.4"),0,0,0);DesignTokens.Button(clearAll,"Toolbar");clearAll.ToolTip="清除普通记录；固定和置顶记录会保留";DockPanel.SetDock(clearAll,Dock.Right);filterBar.Children.Add(clearAll);
        sources.Margin=new(0);if(sources.Parent is Panel parent)parent.Children.Remove(sources);filterBar.Children.Add(sources);header.Children.Add(filterBar);AttachUpdateBanner();
    }
    FrameworkElement BrandMark()
    {
        return new Image{Source=Icon,Width=DesignTokens.Number("Space.32"),Height=DesignTokens.Number("Space.32"),VerticalAlignment=VerticalAlignment.Center};
    }
    void StyleInput(Control control){control.SetResourceReference(Control.BackgroundProperty,"FloatClip.Surface");control.SetResourceReference(Control.ForegroundProperty,"FloatClip.Text");control.SetResourceReference(Control.BorderBrushProperty,"FloatClip.Line");control.Padding=new(10,5,10,5);control.MinHeight=32;}
    void Guard(Action action)
    {
        try{action();}
        catch(Exception error)
        {
            host.ReportUiFailure(error);
            if(host.IsIsolated)throw;
            syncStatus.Text="界面操作未完成，详细原因已记录到本机日志。";
        }
    }
    void Refresh(bool reloadDevices=true)
    {
        syncStatus.Text=host.Status+(host.HotkeyError.Length>0?" · "+host.HotkeyError:"");
        var entries=vault.Load()["entries"]!.AsArray();string own=vault.Device()["id"]!.GetValue<string>();var definitions=categories.List();categoryColors=definitions.ToDictionary(c=>c.Name,c=>c.Color);categoryIcons=definitions.ToDictionary(c=>c.Name,c=>c.Icon);copyBadges.Clear();rendering=true;
        try{
            closeSwipe=null;
            if(reloadDevices){
                sources.Children.Clear();var shown=new System.Collections.Generic.HashSet<string>();
                void Filter(string id,string name){if(!shown.Add(id))return;var b=Button(name,()=>{source=id;kindFilter="";Refresh();});DesignStates.SetIsSelected(b,source==id&&kindFilter.Length==0);sources.Children.Add(b);}
                Filter("","全部");Filter(own,"本机");
                var images=Button("图片",()=>{source="";kindFilter="image";Refresh();});DesignStates.SetIsSelected(images,kindFilter=="image");sources.Children.Add(images);
                if(sync!=null)foreach(string key in sync.Pairing.Keys()){var peer=sync.Pairing.Describe(key);if(peer?.DeviceId!=null)Filter(peer.DeviceId.Value.ToString(),peer.Name??"已授权设备");}
                foreach(var g in entries.OfType<JsonObject>().Where(c=>c["source"]!["id"]!.GetValue<string>()!=own).GroupBy(c=>c["source"]!["id"]!.GetValue<string>()))Filter(g.Key,g.First()["source"]!["name"]!.GetValue<string>());
                if(source.Length>0&&!shown.Contains(source))source="";
            }
            list.Children.Clear();string query=viewModel.SearchText.Trim();var visible=entries.OfType<JsonObject>().Where(c=>(kindFilter.Length==0||ClipboardVault.Kind(c)==kindFilter)&&(source==""||c["source"]!["id"]!.GetValue<string>()==source)&&new[]{c["text"]?.GetValue<string>()??"",c["category"]!.GetValue<string>(),c["source"]!["name"]!.GetValue<string>(),ClipboardVault.Kind(c)=="image"?"图片 image png":"文字 text",ClipboardVault.IsPinned(c)?"置顶 pinned":"",ClipboardVault.IsLocked(c)?"固定 protected":""}.Any(v=>v.Contains(query,StringComparison.OrdinalIgnoreCase))).OrderByDescending(c=>ClipboardVault.IsPinned(c)).ThenByDescending(c=>c["updatedAt"]!.GetValue<long>()).ToList();
            foreach(var clip in visible)list.Children.Add(Card(clip,own));
            if(visible.Count==0)list.Children.Add(new TextBlock{Text=query.Length==0?"这里还没有记录":"没有匹配的记录",Foreground=muted,Margin=new(0,30,0,0),HorizontalAlignment=HorizontalAlignment.Center});
            UndoBar();
        }finally{rendering=false;}
    }
    BitmapSource DecodeImage(byte[] bytes,int decodeWidth=0)
    {
        using var stream=new MemoryStream(bytes);var image=new BitmapImage();image.BeginInit();image.CacheOption=BitmapCacheOption.OnLoad;
        if(decodeWidth>0)image.DecodePixelWidth=decodeWidth;image.StreamSource=stream;image.EndInit();image.Freeze();return image;
    }
    void CopyImage(JsonObject clip){host.CopyImageByUser(vault.ReadImage(clip));CopiedFeedback(clip["id"]!.GetValue<string>());}
    void SaveImage(JsonObject clip){var dialog=new SaveFileDialog{FileName="FloatClip-"+DateTime.Now.ToString("yyyyMMdd-HHmmss")+".png",Filter="PNG 图片 (*.png)|*.png",OverwritePrompt=true};if(dialog.ShowDialog(this)==true)File.WriteAllBytes(dialog.FileName,vault.ReadImage(clip));}
    static string PreviewText(string value)
    {
        // Keep previews bounded without splitting surrogate pairs, skin tones or ZWJ sequences.
        var elements=StringInfo.GetTextElementEnumerator(value);int count=0;
        while(elements.MoveNext())if(++count>500)return value[..elements.ElementIndex]+"…";
        return value;
    }
    FrameworkElement Card(JsonObject clip,string own)
    {
        string id=clip["id"]!.GetValue<string>(),value=clip["text"]?.GetValue<string>()??"",platform=clip["source"]!["platform"]!.GetValue<string>();
        bool pinned=ClipboardVault.IsPinned(clip),locked=ClipboardVault.IsLocked(clip),imageClip=ClipboardVault.Kind(clip)=="image";
        var color=DesignTokens.Brush(!sourceColors?"Source.Windows":platform=="android"?"Source.Android":platform=="ipad"?"Source.Tablet":"Source.Windows");
        Color mix(Color a,Color b,double f)=>Color.FromRgb((byte)(a.R*(1-f)+b.R*f),(byte)(a.G*(1-f)+b.G*f),(byte)(a.B*(1-f)+b.B*f));
        var row=new Grid{Tag="clip",Margin=new(0,0,0,cardSpacing),ClipToBounds=true};
        var protectGrid=new Grid{Width=82,HorizontalAlignment=HorizontalAlignment.Left,Visibility=Visibility.Hidden,Background=tint};
        var protect=Button(locked?"取消固定":"固定",()=>Edit(id,c=>c["locked"]=!locked));System.Windows.Automation.AutomationProperties.SetName(protect,locked?"取消固定记录":"固定记录");System.Windows.Automation.AutomationProperties.SetAutomationId(protect,"Protect-"+id);protect.Content=ActionLabel(locked?"\uE785":"\uE72E",locked?"取消固定":"固定");protect.Padding=new(2);protect.FontSize=12;protect.Margin=new(4,0,4,0);protectGrid.Children.Add(protect);row.Children.Add(protectGrid);
        var actionGrid=new Grid{Width=148,HorizontalAlignment=HorizontalAlignment.Right,Visibility=Visibility.Hidden,Background=tint};actionGrid.ColumnDefinitions.Add(new());actionGrid.ColumnDefinitions.Add(new());
        var pin=Button(pinned?"取消置顶":"置顶",()=>Edit(id,c=>c["pinned"]=!pinned));System.Windows.Automation.AutomationProperties.SetName(pin,pinned?"取消置顶":"置顶");System.Windows.Automation.AutomationProperties.SetAutomationId(pin,"Pin-"+id);pin.Content=ActionLabel(pinned?"\uE77A":"\uE718",pinned?"取消置顶":"置顶");pin.Padding=new(2);pin.FontSize=13;pin.Margin=new(6,0,3,0);actionGrid.Children.Add(pin);
        var remove=Button("删除",()=>Delete(clip));System.Windows.Automation.AutomationProperties.SetName(remove,"删除记录");System.Windows.Automation.AutomationProperties.SetAutomationId(remove,"Delete-"+id);remove.Content=ActionLabel("\uE74D","删除");remove.FontSize=13;remove.Padding=new(2);remove.SetResourceReference(Control.ForegroundProperty,"FloatClip.Danger");Grid.SetColumn(remove,1);remove.Margin=new(3,0,0,0);actionGrid.Children.Add(remove);row.Children.Add(actionGrid);
        foreach(var action in new[]{protect,pin,remove}){action.HorizontalAlignment=HorizontalAlignment.Stretch;action.VerticalAlignment=VerticalAlignment.Stretch;action.Margin=new(0);action.BorderThickness=new(0);action.Background=Brushes.Transparent;((Wpf.Ui.Controls.Button)action).Appearance=Wpf.Ui.Controls.ControlAppearance.Transparent;}
        var scale=new ScaleTransform(1,1);var front=new Border{MinHeight=imageClip?208:88,CornerRadius=DesignTokens.Radius("Card"),Background=translucent?new SolidColorBrush(mix(surface.Color,color.Color,dark ? .12 : .075)):surface,RenderTransform=scale,RenderTransformOrigin=new(.5,.5)};
        var idleBrush=front.Background;front.MouseEnter+=(_,_)=>front.SetResourceReference(Border.BackgroundProperty,"FloatClip.Hover");front.MouseLeave+=(_,_)=>front.Background=idleBrush;
        var body=new Grid();front.Child=body;
        if(imageClip)
        {
            try
            {
                var thumbnail=new Image{Source=Thumbnail(clip),Stretch=Stretch.Uniform,IsHitTestVisible=false};
                body.Children.Add(new Border{Child=thumbnail,Height=144,Margin=new(12,32,12,44),CornerRadius=new(7),Background=tint,ClipToBounds=true,IsHitTestVisible=false});
                long width=clip["imageWidth"]?.GetValue<long>()??0,height=clip["imageHeight"]?.GetValue<long>()??0;
                body.Children.Add(new TextBlock{Text=$"图片 · {width} × {height}",FontSize=12,Foreground=muted,VerticalAlignment=VerticalAlignment.Bottom,HorizontalAlignment=HorizontalAlignment.Left,Margin=new(16,0,50,10),IsHitTestVisible=false});
                var copy=Button("复制图片",()=>CopyImage(clip));copy.HorizontalAlignment=HorizontalAlignment.Right;copy.VerticalAlignment=VerticalAlignment.Bottom;copy.Margin=new(0,0,12,9);copy.MinHeight=28;copy.Padding=new(9,4,9,4);copy.FontSize=12;body.Children.Add(copy);
            }
            catch{body.Children.Add(new TextBlock{Text="图片暂不可用",FontSize=15,Foreground=muted,VerticalAlignment=VerticalAlignment.Center,Margin=new(16,16,48,16)});}
        }
        else body.Children.Add(new Emoji.Wpf.TextBlock{Text=PreviewText(value),TextWrapping=TextWrapping.Wrap,TextTrimming=TextTrimming.CharacterEllipsis,MaxHeight=clipFontSize*4.8,FontSize=clipFontSize,LineHeight=clipFontSize*1.55,FontFamily=FontFamily,Language=XmlLanguage.GetLanguage("zh-CN"),Foreground=text,VerticalAlignment=VerticalAlignment.Center,Margin=new(14,30,32,12),IsHitTestVisible=false});
        string sourceLabel=clip["source"]!["id"]!.GetValue<string>()==own?"本机":clip["source"]!["name"]!.GetValue<string>();
        string timestamp=DateTimeOffset.FromUnixTimeMilliseconds(clip["updatedAt"]!.GetValue<long>()).ToLocalTime().ToString("HH:mm");
        var metadata=new StackPanel{Orientation=Orientation.Horizontal,VerticalAlignment=VerticalAlignment.Top,Margin=new(16,11,50,0),IsHitTestVisible=false};
        metadata.Children.Add(new TextBlock{Text=sourceLabel+"  |  "+timestamp,Foreground=muted,FontSize=DesignTokens.Number("Font.Caption"),TextTrimming=TextTrimming.CharacterEllipsis});
        string category=clip["category"]?.GetValue<string>()??"未分类";
        if(category!="未分类"){
            metadata.Children.Add(new Border{Tag=categoryColors.GetValueOrDefault(category,"blue"),Style=(Style)Application.Current.FindResource("FloatClip.Category.Swatch"),Margin=(Thickness)Application.Current.FindResource("FloatClip.Margin.Start8")});
            metadata.Children.Add(new Wpf.Ui.Controls.SymbolIcon(CategorySymbols.For(categoryIcons.GetValueOrDefault(category,"folder"))){FontSize=DesignTokens.Number("Icon.Small"),Foreground=muted,Margin=(Thickness)Application.Current.FindResource("FloatClip.Margin.Start8")});
            metadata.Children.Add(new TextBlock{Text=category,Foreground=muted,FontSize=DesignTokens.Number("Font.Caption"),Margin=(Thickness)Application.Current.FindResource("FloatClip.Margin.Start8"),MaxWidth=DesignTokens.Number("Category.BadgeMaxWidth"),TextTrimming=TextTrimming.CharacterEllipsis});
        }
        body.Children.Add(metadata);
        var copied=new TextBlock{Text="已复制",FontSize=DesignTokens.Number("Font.Caption"),HorizontalAlignment=HorizontalAlignment.Right,VerticalAlignment=VerticalAlignment.Bottom,Margin=(Thickness)Application.Current.FindResource("FloatClip.Padding.Card"),Visibility=viewModel.CopiedId==id?Visibility.Visible:Visibility.Collapsed,IsHitTestVisible=false};copied.SetResourceReference(TextBlock.ForegroundProperty,"FloatClip.Success");body.Children.Add(copied);copyBadges[id]=copied;
        var markers=new StackPanel{VerticalAlignment=VerticalAlignment.Top,HorizontalAlignment=HorizontalAlignment.Right,Margin=new(0,11,10,0),Opacity=.78};
        void Mark(string code){var glyph=Glyph(code,14);((TextBlock)glyph).Foreground=color;glyph.Margin=new(0,0,0,6);markers.Children.Add(glyph);}
        if(pinned)Mark("\uE718");if(locked)Mark("\uE72E");if(clip["source"]!["id"]!.GetValue<string>()!=own)Mark(platform=="android"?"\uE8EA":platform=="ipad"?"\uE70A":"\uE770");if(imageClip)Mark("\uE91B");body.Children.Add(markers);
        var translate=new TranslateTransform();var slide=new Grid{RenderTransform=translate};slide.Children.Add(front);row.Children.Add(slide);
        bool opened=false,moved=false,held=false;double start=0;Point down=new();var hold=new DispatcherTimer{Interval=TimeSpan.FromMilliseconds(DesignTokens.Number("Motion.HoldMilliseconds"))};
        void Press(bool on){if(on)front.SetResourceReference(Border.BackgroundProperty,"FloatClip.Pressed");else if(front.IsMouseOver)front.SetResourceReference(Border.BackgroundProperty,"FloatClip.Hover");else front.Background=idleBrush;double target=on&&DesignTokens.Motion("Press")!=TimeSpan.Zero?DesignTokens.Number("Motion.PressScale"):1;var d=DesignTokens.Motion(on?"Press":"State");var animation=new DoubleAnimation(target,d){EasingFunction=(IEasingFunction)Application.Current.FindResource("FloatClip.Motion.EaseOut")};scale.BeginAnimation(ScaleTransform.ScaleXProperty,animation,HandoffBehavior.SnapshotAndReplace);scale.BeginAnimation(ScaleTransform.ScaleYProperty,animation,HandoffBehavior.SnapshotAndReplace);}
        void Reveal(bool show,bool protectSide=false)
        {
            opened=show;protectGrid.Visibility=show&&protectSide?Visibility.Visible:Visibility.Hidden;actionGrid.Visibility=show&&!protectSide?Visibility.Visible:Visibility.Hidden;
            double target=!show?0:protectSide?82:-148;var duration=new Duration(DesignTokens.Motion("State"));
            translate.BeginAnimation(TranslateTransform.XProperty,new DoubleAnimation(translate.X,target,duration){EasingFunction=(IEasingFunction)Application.Current.FindResource("FloatClip.Motion.EaseOut")},HandoffBehavior.SnapshotAndReplace);
            closeSwipe=show?()=>Reveal(false):null;
        }
        var menu=new ContextMenu();
        void Item(string label,Action action){var item=new MenuItem{Header=label};item.Click+=(_,_)=>Guard(action);menu.Items.Add(item);}
        if(imageClip){Item("查看图片",()=>PreviewImage(clip));Item("复制图片",()=>CopyImage(clip));Item("另存为 PNG",()=>SaveImage(clip));}
        else{Item("查看全文",()=>FullText(id,value));Item("复制文字",()=>CopyEntry(value,id));}
        Item(locked?"取消固定记录":"固定记录",()=>Edit(id,c=>c["locked"]=!locked));Item(pinned?"取消置顶":"置顶",()=>Edit(id,c=>c["pinned"]=!pinned));
        var categoryMenu=new MenuItem{Header="分类"};
        foreach(var definition in categories.List()){
            var item=new MenuItem{Header=definition.Name,IsCheckable=true,IsChecked=category==definition.Name,Icon=new Border{Tag=definition.Color,Style=(Style)Application.Current.FindResource("FloatClip.Category.Swatch")}};
            item.Click+=(_,_)=>Guard(()=>{categories.Assign(id,definition.Name);Refresh();});categoryMenu.Items.Add(item);
        }
        categoryMenu.Items.Add(new Separator());var newCategory=new MenuItem{Header="选择或新建分类…"};newCategory.Click+=(_,_)=>Guard(()=>ChangeCategory(clip));categoryMenu.Items.Add(newCategory);menu.Items.Add(categoryMenu);
        Item("删除",()=>Delete(clip));front.ContextMenu=menu;
        hold.Tick+=(_,_)=>{hold.Stop();held=true;Press(false);front.ReleaseMouseCapture();menu.PlacementTarget=front;menu.IsOpen=true;};front.Unloaded+=(_,_)=>hold.Stop();
        front.MouseLeftButtonDown+=(_,e)=>{front.Focus();if(!opened)closeSwipe?.Invoke();down=e.GetPosition(row);start=translate.X;translate.BeginAnimation(TranslateTransform.XProperty,null);translate.X=start;moved=false;held=false;front.CaptureMouse();Press(true);hold.Start();e.Handled=true;};
        front.MouseMove+=(_,e)=>{if(!front.IsMouseCaptured)return;var p=e.GetPosition(row);double dx=p.X-down.X,dy=p.Y-down.Y;if(Math.Max(Math.Abs(dx),Math.Abs(dy))>8){moved=true;hold.Stop();Press(false);}if(Math.Abs(dx)>Math.Abs(dy)*1.2&&Math.Abs(dx)>9){double x=Math.Clamp(start+dx,-148,82);protectGrid.Visibility=x>8?Visibility.Visible:Visibility.Hidden;actionGrid.Visibility=x< -8?Visibility.Visible:Visibility.Hidden;translate.X=x;}};
        front.MouseLeftButtonUp+=(_,e)=>{if(!front.IsMouseCaptured)return;hold.Stop();front.ReleaseMouseCapture();Press(false);if(held)return;if(moved){if(translate.X>52)Reveal(true,true);else if(translate.X< -52)Reveal(true,false);else Reveal(false);return;}if(opened){Reveal(false);return;}Guard(()=>{if(imageClip)PreviewImage(clip);else CopyEntry(value,id);});e.Handled=true;};
        front.LostMouseCapture+=(_,_)=>{hold.Stop();Press(false);if(moved&&!held){if(translate.X>52)Reveal(true,true);else if(translate.X< -52)Reveal(true,false);else Reveal(false);}};front.Focusable=true;
        System.Windows.Automation.AutomationProperties.SetName(front,(imageClip?"图片":"文字："+value)+"，来源："+clip["source"]!["name"]!.GetValue<string>());
        System.Windows.Automation.AutomationProperties.SetAutomationId(front,"Clip-"+id);
        front.ToolTip=imageClip?"点击预览 · 右滑固定 · 左滑置顶或删除":"点击复制 · 右滑固定 · 左滑置顶或删除 · 右键查看";
        front.KeyDown+=(_,e)=>{if(e.Key==Key.Left){Reveal(true,false);pin.Focus();e.Handled=true;}else if(e.Key==Key.Right){Reveal(true,true);protect.Focus();e.Handled=true;}else if(e.Key==Key.Escape){Reveal(false);front.Focus();e.Handled=true;}else if(e.Key is Key.Enter or Key.Space){Guard(()=>{if(imageClip)PreviewImage(clip);else CopyEntry(value,id);});e.Handled=true;}else if(e.Key==Key.C&&Keyboard.Modifiers.HasFlag(ModifierKeys.Control)){Guard(()=>{if(imageClip)CopyImage(clip);else CopyEntry(value,id);});e.Handled=true;}};
        return row;
    }
    FrameworkElement ActionLabel(string code,string label){var p=new StackPanel{VerticalAlignment=VerticalAlignment.Center,HorizontalAlignment=HorizontalAlignment.Center};p.Children.Add(Glyph(code));p.Children.Add(new TextBlock{Text=label,FontSize=13,HorizontalAlignment=HorizontalAlignment.Center,Margin=new(0,7,0,0)});return p;}
    IEnumerable<string> UndoImageReferences()=>deleted?.Where(c=>ClipboardVault.Kind(c)=="image").Select(c=>c["imageRef"]?.GetValue<string>()??"").Where(x=>x.Length>0)??Array.Empty<string>();
    void PruneImagesPreservingUndo(){try{vault.PruneOrphanedImages(UndoImageReferences());}catch{}}
    void Delete(JsonObject clip){deleted=new List<JsonObject>{(JsonObject)clip.DeepClone()};Edit(clip["id"]!.GetValue<string>(),null);PruneImagesPreservingUndo();}
    void ClearUnprotectedHistory()
    {
        deleted=null;viewModel.ClearHistoryCommand.Execute(null);UndoBar();syncStatus.Text=viewModel.StatusMessage;
    }
    void UndoBar()
    {
        undo.Child=null;undo.Visibility=deleted==null||deleted.Count==0?Visibility.Collapsed:Visibility.Visible;if(deleted==null||deleted.Count==0)return;
        var restore=deleted.Select(c=>(JsonObject)c.DeepClone()).ToList();var row=new DockPanel();var button=Button("撤销",()=>{
            var snap=vault.Load();var clips=snap["entries"]!.AsArray();var ids=clips.OfType<JsonObject>().Select(c=>c["id"]!.GetValue<string>()).ToHashSet();
            var requested=restore.Select(c=>c["id"]!.GetValue<string>()).ToHashSet();
            var missing=restore.Where(c=>!ids.Contains(c["id"]!.GetValue<string>())).OrderByDescending(ClipboardVault.IsPinned).ThenByDescending(ClipboardVault.IsLocked).ThenByDescending(c=>c["updatedAt"]!.GetValue<long>()).ToList();
            // Choose all victims before restoring. A restored old clip must never become
            // the next iteration's "oldest" victim or inflate the reported restore count.
            int needed=Math.Max(0,missing.Count-(500-clips.Count));
            var victims=clips.OfType<JsonObject>().Where(c=>!ClipboardVault.IsPinned(c)&&!ClipboardVault.IsLocked(c)&&!requested.Contains(c["id"]!.GetValue<string>())).OrderBy(c=>c["updatedAt"]!.GetValue<long>()).Take(needed).ToList();
            foreach(var victim in victims){ids.Remove(victim["id"]!.GetValue<string>());clips.Remove(victim);}
            foreach(var item in missing.Take(500-clips.Count)){clips.Add(item.DeepClone());ids.Add(item["id"]!.GetValue<string>());}
            vault.Commit(snap["revision"]!.GetValue<string>(),clips);
            deleted=restore.Where(c=>!ids.Contains(c["id"]!.GetValue<string>())).ToList();int restored=restore.Count-deleted.Count;
            PruneImagesPreservingUndo();Refresh();syncStatus.Text=deleted.Count==0?"已撤销":$"已恢复 {restored} 条；其余 {deleted.Count} 条可在腾出空间后继续撤销，固定和置顶记录已保留";
        });DockPanel.SetDock(button,Dock.Right);row.Children.Add(button);row.Children.Add(new TextBlock{Text=restore.Count==1?"记录已删除":$"已清除 {restore.Count} 条记录",Foreground=muted,VerticalAlignment=VerticalAlignment.Center});undo.Child=row;
    }
    void Edit(string id,Action<JsonObject>? edit){var snap=vault.Load();var clips=snap["entries"]!.AsArray();var c=clips.OfType<JsonObject>().FirstOrDefault(n=>n["id"]!.GetValue<string>()==id);if(c==null)return;if(edit==null)clips.Remove(c);else{edit(c);c["updatedAt"]=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();}vault.Commit(snap["revision"]!.GetValue<string>(),clips);Refresh();}
    void SavePreferences(){File.WriteAllText(Path.Combine(prefsRoot,"theme.txt"),mode);File.WriteAllText(Path.Combine(prefsRoot,"clipboard-ui.json"),new JsonObject{["sourceColors"]=sourceColors,["translucent"]=translucent,["capture"]=capture,["clipFontSize"]=clipFontSize,["cardSpacing"]=cardSpacing,["panelWidth"]=panelWidth,["expandedNavigation"]=expandedNavigation}.ToJsonString());}
    void FullText(string id,string value)
    {
        string key="text:"+id;if(ActivateAuxiliary(key))return;
        var box=new Emoji.Wpf.RichTextBox{IsReadOnly=true,VerticalScrollBarVisibility=ScrollBarVisibility.Auto,BorderThickness=new(0),Padding=new(16),FontSize=16};StyleInput(box);
        // Size the document before glyph substitution; otherwise emoji are created at
        // the document's default 12 DIP while surrounding text is displayed at 16 DIP.
        box.Document.FontSize=16;box.Document.FontFamily=FontFamily;box.Document.LineHeight=25;box.Document.PagePadding=new(0);
        box.Document.SetResourceReference(System.Windows.Documents.TextElement.ForegroundProperty,"FloatClip.Text");box.Text=value;
        var window=new Window{Title="剪贴板全文",Width=600,Height=440,MinWidth=350,MinHeight=240,Content=box};
        CommandManager.AddPreviewExecutedHandler(window,(_,e)=>{
            if(e.Command!=ApplicationCommands.Copy)return;
            // Emoji.Wpf's selection serializer restores EmojiInline to the original
            // Unicode sequence. WPF TextRange.Text would copy object placeholders.
            if(!box.Selection.IsEmpty)Guard(()=>host.CopyByUser(box.Selection.Text));e.Handled=true;
        });
        ShowAuxiliary(key,window);
    }
    void ChangeCategory(JsonObject clip)
    {
        string key="category:"+clip["id"]!.GetValue<string>();if(ActivateAuxiliary(key))return;
        var model=new CategoryPickerViewModel(categories,clip["id"]!.GetValue<string>(),clip["category"]?.GetValue<string>()??"未分类");
        var window=new Window{Title="分类",Width=360,SizeToContent=SizeToContent.Height,MinWidth=300,Content=new CategoryPickerView{DataContext=model}};
        model.Saved+=()=>{Refresh();window.Close();};ShowAuxiliary(key,window);
    }
    TextBlock MutedLabel(string label,Thickness margin)
    {
        var block=new TextBlock{Text=label,Margin=margin,TextWrapping=TextWrapping.Wrap};block.SetResourceReference(TextBlock.ForegroundProperty,"FloatClip.Muted");return block;
    }
    public void OpenSettings()=>Navigate(typeof(SettingsPage));
    FrameworkElement BuildSettingsPage()
    {
        var panel=new StackPanel{Margin=(Thickness)Application.Current.FindResource("FloatClip.Padding.Page")};
        panel.Children.Add(new TextBlock{Text="设置",FontSize=DesignTokens.Number("Font.Title"),FontWeight=FontWeights.Medium,Margin=new(0,4,0,16)});
        panel.Children.Add(MutedLabel("外观",new(0,0,0,8)));var modes=new ComboBox{ItemsSource=new[]{"跟随系统","浅色","深色（Tokyo Night）"},SelectedIndex=mode=="light"?1:mode is "dark" or "tokyo-night"?2:0,MinWidth=132};StyleInput(modes);System.Windows.Automation.AutomationProperties.SetAutomationId(modes,"ThemeMode");modes.SelectionChanged+=(_,_)=>Guard(()=>{mode=modes.SelectedIndex switch{1=>"light",2=>"tokyo-night",_=>"system"};SavePreferences();ApplyPalette();BuildHeader();Refresh();});panel.Children.Add(SettingRow("应用主题","",modes));
        void Option(string label,bool selected,Action<bool> change)=>panel.Children.Add(ToggleSetting(label,"",selected,change));
        Option("展开导航文字",expandedNavigation,v=>{expandedNavigation=v;UpdateNavigation();});
        Option("减少动画",designPreferences.ReduceMotion,v=>{designPreferences.ReduceMotion=v;designPreferences.Save(prefsRoot);});
        var backgroundChoice=new ComboBox{ItemsSource=new[]{"纯色","磨砂","透明（重启应用后生效）"},SelectedIndex=designPreferences.ClipboardBackground=="acrylic"?1:designPreferences.ClipboardBackground=="transparent"?2:0};StyleInput(backgroundChoice);
        var backgroundHint=MutedLabel("",(Thickness)Application.Current.FindResource("FloatClip.Margin.Top4"));
        backgroundChoice.SelectionChanged+=(_,_)=>Guard(()=>{designPreferences.ClipboardBackground=backgroundChoice.SelectedIndex switch{1=>"acrylic",2=>"transparent",_=>"solid"};designPreferences.Save(prefsRoot);backgroundHint.Text=ApplyClipboardBackground();});
        panel.Children.Add(SettingRow("应用背景","适用于剪贴板、文件、设备、设置及辅助窗口",backgroundChoice));panel.Children.Add(backgroundHint);
        panel.Children.Add(SliderSetting("背景不透明度","文字和卡片保持清晰",60,100,designPreferences.BackgroundOpacity*100,v=>{designPreferences.BackgroundOpacity=v/100;designPreferences.Save(prefsRoot);ApplyClipboardBackground();},"BackgroundOpacity"));
        Option("设备特征色",sourceColors,v=>sourceColors=v);Option("彩色卡片背景",translucent,v=>translucent=v);
        panel.Children.Add(MutedLabel("分类特征色",(Thickness)Application.Current.FindResource("FloatClip.Margin.Section")));
        categorySettingsModel=new CategorySettingsViewModel(categories,()=>Guard(()=>Refresh()));panel.Children.Add(new CategorySettingsView{DataContext=categorySettingsModel});
        panel.Children.Add(SliderSetting("文字大小","剪贴板正文",12,18,clipFontSize,v=>clipFontSize=v,"ClipFontSize"));
        panel.Children.Add(SliderSetting("卡片间距","紧凑或宽松",4,18,cardSpacing,v=>cardSpacing=v,"CardSpacing"));
        panel.Children.Add(SliderSetting("面板宽度","按屏幕调整",420,900,panelWidth,v=>{panelWidth=v;Width=v;},"PanelWidth"));
        panel.Children.Add(MutedLabel("收录与启动",new(0,16,0,8)));
        Option("收录文字和图片",capture,v=>capture=v);
        Option("登录 Windows 时启动",WindowsStartup.Enabled,WindowsStartup.SetEnabled);
        panel.Children.Add(MutedLabel("调出悬浮剪贴板的快捷键",new(0,12,0,4)));
        bool recording=false;uint pendingModifiers=0,pendingKey=0;
        var shortcut=new TextBox{Text=host.HotkeyLabel,IsReadOnly=true,Focusable=false,ToolTip="点击更改后按下组合键，再保存"};StyleInput(shortcut);panel.Children.Add(shortcut);
        var shortcutStatus=MutedLabel(host.HotkeyError,new(0,4,0,12));shortcutStatus.FontSize=12;panel.Children.Add(shortcutStatus);
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
        panel.Children.Add(Button("跨网设备（iPad / Web）",CrossNetworkPortal.Open));
        return new ScrollViewer{Content=panel,VerticalScrollBarVisibility=ScrollBarVisibility.Auto};
    }
}
