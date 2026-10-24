using System;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Input;
using System.Windows.Markup;
using System.Windows.Media;
using Microsoft.Win32;
using FloatClip.Share;
using FloatClip.Desktop.Design;
using FloatClip.Desktop.Services;

namespace FloatClip.Desktop;

public sealed class FileShelfRow
{
    public string Name{get;init;}="";
    public ImageSource ShellIcon=>ShellFileIcons.For(HasLocalFile&&Path.Length>0?Path:Name);
    public string Extension{get;init;}="";
    public string Modified{get;init;}="—";
    public string Type{get;init;}="文件";
    public string SizeLabel{get;init;}="—";
    public string Source{get;init;}="本机";
    public string Status{get;init;}="";
    public string Subtext{get;init;}="";
    public string Path{get;init;}="";
    public long SizeBytes{get;init;}
    public long ModifiedTicks{get;init;}
    public long CreationTicks{get;init;}
    public long AddedTicks{get;init;}
    public bool HasLocalFile{get;init;}
    public bool IsLocalShelf{get;init;}
    public bool IsAvailable{get;init;}
    public Guid? TransferId{get;init;}
    public TransferPhase? Phase{get;init;}
    public bool Outgoing{get;init;}
    public long Done{get;init;}
    public long Total{get;init;}
    public SharedFileItem? Remote{get;init;}
    public bool Kept{get;init;}
    public string Key=>Remote?.Key??Path;
}

public sealed class FileShelfView:UserControl,IDisposable
{
    readonly NativeClipboardSync? sync;
    readonly string preferencesRoot;
    readonly FileShelfStore store;
    readonly ObservableCollection<FileShelfRow> localRows=new(),transferRows=new();
    readonly ListView localList=new(),transferList=new();
    readonly ComboBox peers=new();
    readonly TextBlock status=new(),selectedInfo=new();
    readonly Button removeLocal,sendFile,accept,decline,cancel,saveAs,openFile,forget;
    readonly Button downloadCommand,previewCommand,saveCommand,forwardCommand,propertiesCommand;
    readonly ComboBox dateRange=new();
    readonly ProgressBar batchProgress=new(){Height=4,Maximum=100,Visibility=Visibility.Collapsed};
    readonly Border activity=new(){Height=126,Padding=new Thickness(0,8,0,0),BorderThickness=new Thickness(0,1,0,0)};
    readonly TextBlock activitySummary=new(){Text="暂无传输活动",FontSize=12,VerticalAlignment=VerticalAlignment.Center};
    CancellationTokenSource? batch;
    bool closed;
    readonly DispatcherTimer statusTimer=new(){Interval=TimeSpan.FromSeconds(5)};

    public FileShelfView(NativeClipboardSync? sync,string preferencesRoot)
    {
        this.preferencesRoot=preferencesRoot;this.sync=sync;store=sync?.Files.Store??new FileShelfStore(System.IO.Path.Combine(preferencesRoot,"file-shelf.json"));
        Background=Brushes.Transparent;SetResourceReference(ForegroundProperty,"FloatClip.Text");
        SetResourceReference(FontFamilyProperty,"FloatClip.FontFamily");SetResourceReference(FontSizeProperty,"FloatClip.Font.Body");
        var root=new Grid();
        root.RowDefinitions.Add(new RowDefinition{Height=GridLength.Auto});
        root.RowDefinitions.Add(new RowDefinition{Height=GridLength.Auto});
        root.RowDefinitions.Add(new RowDefinition{Height=new GridLength(1,GridUnitType.Star)});
        root.RowDefinitions.Add(new RowDefinition{Height=GridLength.Auto});
        Content=root;
        var heading=new StackPanel{Margin=(Thickness)Application.Current.FindResource("FloatClip.Padding.Page")};
        heading.Children.Add(new TextBlock{Text="共享文件",FontSize=DesignTokens.Number("Font.Title"),FontWeight=FontWeights.Medium});
        heading.Children.Add(batchProgress);
        root.Children.Add(heading);

        var actions=new ToolBar{Padding=new Thickness(0),Background=Brushes.Transparent,BorderThickness=new Thickness(0)};
        ToolBarTray.SetIsLocked(actions,true);
        var add=Button("添加文件",AddFiles);CommandIcon(add,"\uE8E5");System.Windows.Automation.AutomationProperties.SetAutomationId(add,"AddShelfFiles");actions.Items.Add(add);
        actions.Items.Add(new Separator{Margin=new Thickness(5,4,5,4)});
        downloadCommand=Button("下载",()=>_=DownloadSelected());CommandIcon(downloadCommand,"\uE896");((Wpf.Ui.Controls.Button)downloadCommand).Appearance=Wpf.Ui.Controls.ControlAppearance.Primary;
        DesignTokens.Button(downloadCommand,"Accent");actions.Items.Add(downloadCommand);
        previewCommand=Button("预览",OpenSelectedLocal);CommandIcon(previewCommand,"\uE8A7");actions.Items.Add(previewCommand);
        saveCommand=Button("另存为",()=>_=SaveLocalAs());CommandIcon(saveCommand,"\uE74E");actions.Items.Add(saveCommand);
        forwardCommand=Button("转发",ForwardSelected);CommandIcon(forwardCommand,"\uE72A");actions.Items.Add(forwardCommand);
        var more=Button("更多",()=>{});var moreMenu=new ContextMenu();moreMenu.SetResourceReference(Control.BackgroundProperty,"FloatClip.Surface");moreMenu.SetResourceReference(Control.ForegroundProperty,"FloatClip.Text");
        void More(string label,Action action){var item=new MenuItem{Header=label};item.SetResourceReference(Control.ForegroundProperty,"FloatClip.Text");item.Click+=(_,_)=>Run(action);moreMenu.Items.Add(item);}
        More("下载 / 另存为",()=>_=DownloadSelected());More("保留 / 取消保留",KeepSelected);More("清理所选下载缓存",()=>ClearFiles(false));More("移除所选记录",RemoveLocal);More("取消下载",()=>batch?.Cancel());more.Click+=(_,_)=>{moreMenu.PlacementTarget=more;moreMenu.IsOpen=true;};actions.Items.Add(more);
        dateRange.ItemsSource=new[]{"全部时间","最近24小时","最近7天","最近30天"};dateRange.SelectedIndex=0;dateRange.SelectionChanged+=(_,_)=>RefreshLocal();
        var dates=new MenuItem{Header="按时间筛选"};moreMenu.Items.Add(dates);for(int index=0;index<4;index++){int chosen=index;var item=new MenuItem{Header=dateRange.Items[index]};item.Click+=(_,_)=>dateRange.SelectedIndex=chosen;dates.Items.Add(item);}
        removeLocal=Button("移除所选",RemoveLocal);System.Windows.Automation.AutomationProperties.SetAutomationId(removeLocal,"RemoveShelfFile");
        peers.MinWidth=150;peers.MinHeight=32;peers.Margin=new Thickness(4,0,8,0);peers.SetResourceReference(Control.BackgroundProperty,"FloatClip.Surface");peers.SetResourceReference(Control.ForegroundProperty,"FloatClip.Text");System.Windows.Automation.AutomationProperties.SetName(peers,"发送到局域网设备");System.Windows.Automation.AutomationProperties.SetAutomationId(peers,"FileTransferPeer");peers.SelectionChanged+=(_,_)=>RefreshActions();
        sendFile=Button("直接发送",SendSelected);System.Windows.Automation.AutomationProperties.SetAutomationId(sendFile,"SendShelfFile");
        var commands=new DockPanel{LastChildFill=true};propertiesCommand=Button("属性",ShowProperties);CommandIcon(propertiesCommand,"\uE946");DockPanel.SetDock(propertiesCommand,Dock.Right);commands.Children.Add(propertiesCommand);commands.Children.Add(actions);
        var commandBand=new Border{Child=commands,Padding=new Thickness(12,6,12,6),BorderThickness=new Thickness(0,1,0,1)};commandBand.SetResourceReference(Border.BorderBrushProperty,"FloatClip.Line");commandBand.Background=Brushes.Transparent;Grid.SetRow(commandBand,1);root.Children.Add(commandBand);

        var body=new Grid{Margin=new Thickness(12,0,12,0)};body.RowDefinitions.Add(new RowDefinition{Height=new GridLength(1,GridUnitType.Star)});body.RowDefinitions.Add(new RowDefinition{Height=GridLength.Auto});
        var localPanel=new DockPanel{LastChildFill=true};
        selectedInfo.SetResourceReference(TextBlock.ForegroundProperty,"FloatClip.Muted");selectedInfo.TextWrapping=TextWrapping.NoWrap;selectedInfo.TextTrimming=TextTrimming.CharacterEllipsis;selectedInfo.Height=28;selectedInfo.FontSize=12;selectedInfo.VerticalAlignment=VerticalAlignment.Center;selectedInfo.Margin=new Thickness(2,0,2,0);DockPanel.SetDock(selectedInfo,Dock.Bottom);localPanel.Children.Add(selectedInfo);
        ConfigureList(localList,localRows);System.Windows.Automation.AutomationProperties.SetAutomationId(localList,"FileShelfList");localList.SelectionChanged+=(_,_)=>{UpdateSelectedInfo();RefreshActions();};
        localList.MouseDoubleClick+=(_,_)=>OpenSelectedLocal();
        localList.PreviewMouseRightButtonDown+=(_,e)=>{if(e.OriginalSource is DependencyObject target){var item=ItemsControl.ContainerFromElement(localList,target) as ListViewItem;if(item!=null){item.IsSelected=true;item.Focus();}}};
        var localMenu=new ContextMenu();localMenu.SetResourceReference(Control.BackgroundProperty,"FloatClip.Surface");localMenu.SetResourceReference(Control.ForegroundProperty,"FloatClip.Text");
        void MenuItem(string label,Action action){var item=new System.Windows.Controls.MenuItem{Header=label};item.SetResourceReference(Control.ForegroundProperty,"FloatClip.Text");item.Click+=(_,_)=>Run(action);localMenu.Items.Add(item);}
        MenuItem("下载 / 另存为",()=>_=DownloadSelected());MenuItem("预览",OpenSelectedLocal);MenuItem("保留 / 取消保留",KeepSelected);MenuItem("在资源管理器中显示",RevealSelectedLocal);MenuItem("清理下载缓存",()=>ClearFiles(false));MenuItem("从文件清单移除",RemoveLocal);localList.ContextMenu=localMenu;
        localPanel.Children.Add(localList);
        body.Children.Add(localPanel);

        var transferPanel=new DockPanel{LastChildFill=true};
        var transferHeader=new DockPanel{Margin=new Thickness(0,0,0,6)};
        transferHeader.Children.Add(new TextBlock{Text="传输活动",FontSize=13,FontWeight=FontWeights.Medium,VerticalAlignment=VerticalAlignment.Center,Margin=new Thickness(0,0,12,0)});
        activitySummary.SetResourceReference(TextBlock.ForegroundProperty,"FloatClip.Muted");transferHeader.Children.Add(activitySummary);
        DockPanel.SetDock(transferHeader,Dock.Top);transferPanel.Children.Add(transferHeader);
        var transferActions=new WrapPanel{Margin=new Thickness(0,7,0,0)};
        accept=Button("接收",AcceptSelected);decline=Button("拒绝",DeclineSelected);cancel=Button("取消并重连",CancelSelected);saveAs=Button("另存为",SaveSelectedAs);openFile=Button("打开 / 预览",OpenSelectedTransfer);forget=Button("移除接收缓存",ForgetSelected);
        foreach(var b in new[]{accept,decline,cancel,saveAs,openFile,forget})transferActions.Children.Add(b);
        More("直接发送到设备",()=>{var menu=new ContextMenu();if(sync!=null)foreach(var peer in sync.Transfers.Peers()){var item=new MenuItem{Header=peer.Name};item.Click+=(_,_)=>Run(()=>{peers.SelectedItem=peer;SendSelected();});menu.Items.Add(item);}if(menu.Items.Count==0)menu.Items.Add(new MenuItem{Header="暂无文件连接",IsEnabled=false});menu.PlacementTarget=more;menu.IsOpen=true;});
        DockPanel.SetDock(transferActions,Dock.Bottom);transferPanel.Children.Add(transferActions);
        ConfigureList(transferList,transferRows);System.Windows.Automation.AutomationProperties.SetAutomationId(transferList,"TransferActivityList");transferList.SelectionChanged+=(_,_)=>RefreshActions();transferList.MouseDoubleClick+=(_,_)=>OpenSelectedTransfer();transferPanel.Children.Add(transferList);
        transferList.MinHeight=0;activity.Child=transferPanel;activity.Margin=new Thickness(0,8,0,0);activity.SetResourceReference(Border.BorderBrushProperty,"FloatClip.Line");Grid.SetRow(activity,1);body.Children.Add(activity);
        Grid.SetRow(body,2);root.Children.Add(body);

        status.TextWrapping=TextWrapping.NoWrap;status.TextTrimming=TextTrimming.CharacterEllipsis;status.FontSize=12;status.Margin=new Thickness(14,0,14,6);status.Visibility=Visibility.Collapsed;status.SetResourceReference(TextBlock.ForegroundProperty,"FloatClip.Text");Grid.SetRow(status,3);root.Children.Add(status);
        statusTimer.Tick+=(_,_)=>{statusTimer.Stop();status.Visibility=Visibility.Collapsed;};DependencyPropertyDescriptor.FromProperty(TextBlock.TextProperty,typeof(TextBlock)).AddValueChanged(status,(_,_)=>{status.ToolTip=status.Text;status.Visibility=string.IsNullOrEmpty(status.Text)?Visibility.Collapsed:Visibility.Visible;statusTimer.Stop();statusTimer.Start();});
        localList.ItemsSource=localRows;transferList.ItemsSource=transferRows;
        SizeChanged+=(_,_)=>FitColumns();Loaded+=(_,_)=>FitColumns();
        RefreshLocal();RefreshPeers();RefreshTransfers();RefreshActions();
        if(sync!=null){sync.Transfers.Changed+=TransferChanged;sync.Files.Changed+=CatalogChanged;sync.RequestFileConnection();}
    }

    public void Dispose(){if(closed)return;closed=true;statusTimer.Stop();batch?.Cancel();if(sync!=null){sync.Transfers.Changed-=TransferChanged;sync.Files.Changed-=CatalogChanged;}}
    bool catalogQueued;
    void CatalogChanged(){if(closed||Dispatcher.HasShutdownStarted||catalogQueued)return;catalogQueued=true;Dispatcher.BeginInvoke(new Action(()=>{catalogQueued=false;if(!closed&&batch==null)RefreshLocal();}),DispatcherPriority.Background);}
    bool transferQueued;
    void TransferChanged()
    {
        if(closed||Dispatcher.HasShutdownStarted||transferQueued)return;transferQueued=true;
        Dispatcher.BeginInvoke(new Action(()=>{var timer=new DispatcherTimer{Interval=TimeSpan.FromMilliseconds(150)};timer.Tick+=(_,_)=>{timer.Stop();transferQueued=false;if(closed)return;RefreshPeers();RefreshTransfers();RefreshActions();};timer.Start();}));
    }
    static TextBlock Muted(string value,Thickness margin){var t=new TextBlock{Text=value,Margin=margin,TextWrapping=TextWrapping.Wrap,FontSize=12};t.SetResourceReference(TextBlock.ForegroundProperty,"FloatClip.Muted");return t;}
    Button Button(string label,Action action)
    {
        var b=new Wpf.Ui.Controls.Button{Content=label,Appearance=Wpf.Ui.Controls.ControlAppearance.Transparent,Margin=new Thickness(0,0,DesignTokens.Number("Space.4"),0)};
        DesignTokens.Button(b,"Toolbar");b.Click+=(_,_)=>Run(action);return b;
    }
    static void CommandIcon(Button button,string glyph)
    {
        var label=button.Content;
        var symbol=glyph switch{"\uE8E5"=>Wpf.Ui.Controls.SymbolRegular.DocumentAdd24,"\uE896"=>Wpf.Ui.Controls.SymbolRegular.ArrowDownload24,"\uE8A7"=>Wpf.Ui.Controls.SymbolRegular.Eye24,"\uE74E"=>Wpf.Ui.Controls.SymbolRegular.Save24,"\uE72A"=>Wpf.Ui.Controls.SymbolRegular.ArrowForward24,_=>Wpf.Ui.Controls.SymbolRegular.Info24};
        var panel=new StackPanel{Orientation=Orientation.Horizontal};panel.Children.Add(new Wpf.Ui.Controls.SymbolIcon(symbol){FontSize=DesignTokens.Number("Icon.Small"),VerticalAlignment=VerticalAlignment.Center,Margin=(Thickness)Application.Current.FindResource("FloatClip.Margin.End8")});panel.Children.Add(new TextBlock{Text=label?.ToString(),VerticalAlignment=VerticalAlignment.Center});button.Content=panel;System.Windows.Automation.AutomationProperties.SetName(button,label?.ToString());
    }
    static void ConfigureList(ListView list,ObservableCollection<FileShelfRow> rows)
    {
        list.ItemsSource=rows;list.SelectionMode=SelectionMode.Extended;list.MinHeight=92;list.BorderThickness=new Thickness(0);list.Padding=new Thickness(0);
        list.SetResourceReference(Control.BackgroundProperty,"FloatClip.Surface");list.SetResourceReference(Control.ForegroundProperty,"FloatClip.Text");list.SetResourceReference(Control.BorderBrushProperty,"FloatClip.Line");
        list.ItemContainerStyle=(Style)Application.Current.FindResource("FloatClip.File.Row");
        list.Resources.Add(typeof(GridViewColumnHeader),Application.Current.FindResource("FloatClip.File.Header"));
        var view=new GridView();
        view.Columns.Add(new GridViewColumn{Header="名称",Width=265,CellTemplate=NameTemplate()});
        view.Columns.Add(new GridViewColumn{Header="修改时间",Width=128,DisplayMemberBinding=new Binding("Modified")});
        view.Columns.Add(new GridViewColumn{Header="类型",Width=68,DisplayMemberBinding=new Binding("Type")});
        view.Columns.Add(new GridViewColumn{Header="大小",Width=76,DisplayMemberBinding=new Binding("SizeLabel")});
        view.Columns.Add(new GridViewColumn{Header="来源",Width=84,DisplayMemberBinding=new Binding("Source")});
        view.Columns.Add(new GridViewColumn{Header="状态",Width=118,DisplayMemberBinding=new Binding("Status")});
        list.View=view;
        list.AddHandler(GridViewColumnHeader.ClickEvent,new RoutedEventHandler((_,e)=>
        {
            if(e.OriginalSource is not GridViewColumnHeader header||header.Column?.Header is not string label)return;
            string property=label switch{"名称"=>"Name","修改时间"=>"ModifiedTicks","类型"=>"Type","大小"=>"SizeBytes","来源"=>"Source","状态"=>"Status",_=>""};
            if(property.Length==0)return;
            var sorted=CollectionViewSource.GetDefaultView(list.ItemsSource);var prior=sorted.SortDescriptions.FirstOrDefault();
            var direction=sorted.SortDescriptions.Count>0&&prior.PropertyName==property&&prior.Direction==ListSortDirection.Ascending?ListSortDirection.Descending:ListSortDirection.Ascending;
            sorted.SortDescriptions.Clear();sorted.SortDescriptions.Add(new SortDescription(property,direction));e.Handled=true;
        }));
    }
    void FitColumns()
    {
        double available=Math.Max(320,ActualWidth-36);
        foreach(var list in new[]{localList,transferList})
        {
            if(list.View is not GridView view||view.Columns.Count<6)continue;
            bool wide=available>=660;double tail=wide?448:382;view.Columns[0].Width=Math.Max(180,available-tail);view.Columns[1].Width=wide?128:112;view.Columns[2].Width=wide?80:64;view.Columns[3].Width=wide?70:64;view.Columns[4].Width=wide?80:64;view.Columns[5].Width=wide?90:78;
        }
    }
    static DataTemplate NameTemplate()
    {
        const string xaml="""
<DataTemplate xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation" xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">
 <Grid VerticalAlignment="Center">
  <Grid.ColumnDefinitions><ColumnDefinition Width="28"/><ColumnDefinition Width="40"/><ColumnDefinition Width="*"/></Grid.ColumnDefinitions>
  <CheckBox IsChecked="{Binding IsSelected, RelativeSource={RelativeSource AncestorType=ListViewItem}, Mode=TwoWay}" VerticalAlignment="Center" Focusable="False" ToolTip="选择文件" Width="20" MinWidth="0" Padding="0" Margin="0"/>
  <Grid Grid.Column="1" Width="32" Height="32" HorizontalAlignment="Left">
   <Image Source="{Binding ShellIcon}" Width="{DynamicResource FloatClip.Icon.File}" Height="{DynamicResource FloatClip.Icon.File}" Stretch="Uniform" RenderOptions.BitmapScalingMode="HighQuality"/>
   <Rectangle RadiusX="{DynamicResource FloatClip.Space.4}" RadiusY="{DynamicResource FloatClip.Space.4}" Stroke="{DynamicResource FloatClip.TextSecondary}" StrokeThickness="1" StrokeDashArray="3,2">
    <Rectangle.Style><Style TargetType="Rectangle"><Setter Property="Visibility" Value="Collapsed"/><Style.Triggers><DataTrigger Binding="{Binding HasLocalFile}" Value="False"><Setter Property="Visibility" Value="Visible"/></DataTrigger></Style.Triggers></Style></Rectangle.Style>
   </Rectangle>
  </Grid>
  <TextBlock Grid.Column="2" Text="{Binding Name}" Foreground="{DynamicResource FloatClip.Text}" TextTrimming="CharacterEllipsis" VerticalAlignment="Center" ToolTip="{Binding Subtext}"/>
 </Grid>
</DataTemplate>
""";
        return (DataTemplate)XamlReader.Parse(xaml);
    }
    static string FormatSize(long size)=>FileSizeFormatter.Format(size);
    static (string Extension,string Type) FileType(string name)
    {
        string ext=System.IO.Path.GetExtension(name).TrimStart('.').ToUpperInvariant();
        string type=ext switch{"PDF"=>"PDF","DOC"=>"Word","DOCX"=>"Word","XLS"=>"Excel","XLSX"=>"Excel","PPT"=>"PowerPoint","PPTX"=>"PowerPoint","PNG"=>"图片","JPG"=>"图片","JPEG"=>"图片","GIF"=>"图片","WEBP"=>"图片","TXT"=>"文本","MD"=>"文本","CSV"=>"表格","ZIP"=>"压缩包","7Z"=>"压缩包","RAR"=>"压缩包",_=>"文件"};
        if(ext.Length==0)ext="FILE";if(ext.Length>5)ext=ext[..5];return(ext,type);
    }
    void RefreshLocal(string? selectPath=null)
    {
        var selected=localList.SelectedItems.Cast<FileShelfRow>().Select(r=>r.Key).ToHashSet();LocalShelfEntry[] rows;
        try{rows=store.Load();}catch(Exception e)when(e is IOException or UnauthorizedAccessException){rows=Array.Empty<LocalShelfEntry>();status.Text="本机文件清单无法读取，已保留原文件。剪贴板仍可使用。";}localRows.Clear();
        foreach(var item in rows.OrderByDescending(r=>r.AddedUtcTicks))
        {
            bool available=item.IsAvailable;var type=FileType(item.Name);
            string modified=item.LastWriteUtcTicks>0?new DateTime(item.LastWriteUtcTicks,DateTimeKind.Utc).ToLocalTime().ToString("yyyy-MM-dd HH:mm"):"—";
            if(!InRange(item.AddedUtcTicks))continue;
            localRows.Add(new FileShelfRow{Name=item.Name,Path=item.Path,Extension=type.Extension,Type=type.Type,SizeLabel=FormatSize(item.Size),Modified=modified,Source="本机",Status=item.Kept?"已保留":available?"本机文件":"文件不可用",Subtext=available?$"{FormatSize(item.Size)} · 本机 · 修改于 {modified}":"源文件已移动或删除",SizeBytes=item.Size,ModifiedTicks=item.LastWriteUtcTicks,CreationTicks=item.CreationUtcTicks,AddedTicks=item.AddedUtcTicks,HasLocalFile=available,IsLocalShelf=true,IsAvailable=available,Kept=item.Kept});
        }
        if(sync!=null)foreach(var item in sync.Files.Snapshot()){
            long added=UnixTicks(item.Shared);if(!InRange(added))continue;var type=FileType(item.Name);bool local=sync.Files.IsLocal(item);string source=sync.Files.PeerName(item.Peer);string modified=item.Modified>0?new DateTime(UnixTicks(item.Modified),DateTimeKind.Utc).ToLocalTime().ToString("yyyy-MM-dd HH:mm"):"—";
            localRows.Add(new FileShelfRow{Name=item.Name,Path=local?item.LocalPath:"",Remote=item,Extension=type.Extension,Type=type.Type,SizeLabel=FormatSize(item.Size),Modified=modified,Source=source,Status=item.Kept?"已保留":local?"已下载":item.Online?"未下载":"来源离线",Subtext=$"{FormatSize(item.Size)} · {source} · "+(local?"本机已有":item.Online?"按需下载":"连接来源设备后下载"),SizeBytes=item.Size,ModifiedTicks=UnixTicks(item.Modified),CreationTicks=UnixTicks(item.Created),AddedTicks=added,HasLocalFile=local,IsAvailable=local,Kept=item.Kept});
        }
        foreach(var row in localRows.Where(r=>selected.Contains(r.Key)))localList.SelectedItems.Add(row);
        if(selectPath!=null)localList.SelectedItem=localRows.FirstOrDefault(r=>string.Equals(r.Path,selectPath,StringComparison.OrdinalIgnoreCase));
        UpdateSelectedInfo();RefreshActions();
        if(sync?.Files.Warning is string warning)status.Text=warning;
    }
    static long UnixTicks(long value){try{return DateTimeOffset.FromUnixTimeMilliseconds(value).UtcTicks;}catch{return 0;}}
    bool InRange(long ticks)=>dateRange.SelectedIndex<=0||ticks>=DateTime.UtcNow.AddDays(dateRange.SelectedIndex switch{1=>-1,2=>-7,_=>-30}).Ticks;
    void RefreshPeers()
    {
        if(sync==null){peers.ItemsSource=new[]{new FilePeer("","暂无文件连接")};peers.SelectedIndex=0;peers.IsEnabled=false;return;}
        string? selected=(peers.SelectedItem as FilePeer)?.Key;var found=sync.Transfers.Peers();
        if(found.Length==0){peers.ItemsSource=new[]{new FilePeer("","暂无文件连接")};peers.SelectedIndex=0;peers.IsEnabled=false;}
        else{peers.ItemsSource=found;peers.SelectedItem=found.FirstOrDefault(p=>p.Key==selected)??found[0];peers.IsEnabled=true;}
        peers.ToolTip=found.Length>0?"选择已连接的文件接收设备":"连接局域网或蓝牙后可发送文件；离线文件可从共享清单续接下载";
    }
    void RefreshTransfers(Guid? selectedId=null)
    {
        if(sync==null){transferRows.Clear();transferList.Visibility=Visibility.Collapsed;activity.Visibility=Visibility.Collapsed;return;}
        Guid? previous=selectedId??(transferList.SelectedItem as FileShelfRow)?.TransferId;
        transferRows.Clear();
        foreach(var transfer in sync.Transfers.Snapshot())
        {
            var type=FileType(transfer.Name);long modifiedTicks=0;
            if(transfer.Outgoing&&transfer.SourcePath!=null&&File.Exists(transfer.SourcePath)){try{modifiedTicks=File.GetLastWriteTimeUtc(transfer.SourcePath).Ticks;}catch{}}
            string modified=modifiedTicks>0?new DateTime(modifiedTicks,DateTimeKind.Utc).ToLocalTime().ToString("yyyy-MM-dd HH:mm"):"未知";
            string eventTime=transfer.CreatedAtUtcTicks>0?new DateTime(transfer.CreatedAtUtcTicks,DateTimeKind.Utc).ToLocalTime().ToString("yyyy-MM-dd HH:mm"):"—";
            bool local=transfer.Outgoing?transfer.SourcePath!=null&&File.Exists(transfer.SourcePath):transfer.Phase==TransferPhase.Completed&&transfer.Path!=null&&File.Exists(transfer.Path);
            string size=FormatSize(transfer.Total);
            string detail=transfer.Phase switch{TransferPhase.Offered=>"等待你确认接收",TransferPhase.Preparing=>"准备传输",TransferPhase.Transferring=>$"{FormatSize(transfer.Done)} / {FormatSize(transfer.Total)}",TransferPhase.Completed=>transfer.Outgoing?"对方已接收并校验":"已接收并校验",TransferPhase.Declined=>"已拒绝",TransferPhase.Canceled=>"已取消",_=>transfer.Message};
            string subtext=transfer.Outgoing?$"{transfer.Peer} · 本机原文件 · 传输开始 {eventTime} · {transfer.Message}":$"{transfer.Peer} · 传输请求 {eventTime} · {transfer.Message}";
            transferRows.Add(new FileShelfRow{Name=transfer.Name,Path=transfer.Outgoing?transfer.SourcePath??"":transfer.Path??"",Extension=type.Extension,Type=type.Type,SizeLabel=size,Modified=modified,Source=transfer.Peer,Status=detail,Subtext=subtext,SizeBytes=transfer.Total,ModifiedTicks=modifiedTicks,AddedTicks=transfer.CreatedAtUtcTicks,HasLocalFile=local,TransferId=transfer.Id,Phase=transfer.Phase,Outgoing=transfer.Outgoing,Done=transfer.Done,Total=transfer.Total});
        }
        if(previous.HasValue)transferList.SelectedItem=transferRows.FirstOrDefault(r=>r.TransferId==previous);
        activitySummary.Text=transferRows.Count==0?"暂无传输活动":$"{transferRows.Count} 项 · {transferRows.Count(r=>r.Phase is TransferPhase.Offered or TransferPhase.Preparing or TransferPhase.Transferring)} 项进行中";
        transferList.Visibility=transferRows.Count==0?Visibility.Collapsed:Visibility.Visible;
        activity.Visibility=transferRows.Count==0?Visibility.Collapsed:Visibility.Visible;
    }
    void UpdateSelectedInfo()
    {
        if(localList.SelectedItem is not FileShelfRow row){selectedInfo.Text=$"{localRows.Count} 个文件";return;}
        string created=row.CreationTicks>0?new DateTime(row.CreationTicks,DateTimeKind.Utc).ToLocalTime().ToString("yyyy-MM-dd HH:mm"):"未知";string added=row.AddedTicks>0?new DateTime(row.AddedTicks,DateTimeKind.Utc).ToLocalTime().ToString("yyyy-MM-dd HH:mm"):"未知";
        selectedInfo.Text=$"{localRows.Count} 个文件  |  已选择 {localList.SelectedItems.Count} 个";
        selectedInfo.ToolTip=$"{row.Name}\n创建：{created} · 修改：{row.Modified} · 加入清单：{added}\n{row.Path}";
    }
    void RefreshActions()
    {
        var local=localList.SelectedItem as FileShelfRow;removeLocal.IsEnabled=local!=null;
        downloadCommand.IsEnabled=localList.SelectedItems.Count>0;previewCommand.IsEnabled=localList.SelectedItems.Count==1&&local?.IsAvailable==true&&FilePreviewWindow.CanPreview(local.Path);saveCommand.IsEnabled=localList.SelectedItems.Count>0;forwardCommand.IsEnabled=localList.SelectedItems.Count==1&&local?.IsAvailable==true;propertiesCommand.IsEnabled=localList.SelectedItems.Count==1;
        sendFile.IsEnabled=local?.IsAvailable==true&&sync!=null&&peers.SelectedItem is FilePeer peer&&peer.Key.Length>0;
        var row=transferList.SelectedItem as FileShelfRow;var phase=row?.Phase;
        bool offered=phase==TransferPhase.Offered,active=phase is TransferPhase.Preparing or TransferPhase.Transferring;
        bool received=phase==TransferPhase.Completed&&row is{Outgoing:false,Path.Length:>0}&&File.Exists(row.Path);
        bool open=row!=null&&row.Path.Length>0&&File.Exists(row.Path);
        void Show(Button button,bool visible){button.Visibility=visible?Visibility.Visible:Visibility.Collapsed;button.IsEnabled=visible;}
        Show(accept,offered);Show(decline,offered);Show(cancel,active);Show(saveAs,received);Show(forget,received);Show(openFile,open&&row!=null&&FilePreviewWindow.CanPreview(row.Path));
    }
    void AddFiles()
    {
        var dialog=new OpenFileDialog{Title="添加到 FloatClip 文件清单",Filter="所有文件 (*.*)|*.*",Multiselect=true,CheckFileExists=true};
        if(dialog.ShowDialog(Window.GetWindow(this))!=true)return;
        int added=0;string? last=null;var errors=new System.Collections.Generic.List<string>();
        foreach(string file in dialog.FileNames)
        {
            try{if(store.Load().Length>=FileCatalog.MaximumShared)throw new IOException("最多共享 64 个文件，请先移除不需要的条目");var row=store.Add(file);added++;last=row.Path;}
            catch(IOException e){if(errors.Count<4)errors.Add(System.IO.Path.GetFileName(file)+": "+e.Message);}
        }
        sync?.Files.Publish();sync?.RequestFileConnection();RefreshLocal(last);status.Text=errors.Count==0?$"已添加 {added} 个文件":$"已添加 {added} 个文件。"+string.Join("；",errors);
    }
    void RemoveLocal()
    {
        ClearFiles(true);
    }
    void KeepSelected(){foreach(var row in localList.SelectedItems.Cast<FileShelfRow>().ToArray()){if(row.Remote!=null)sync?.Files.Keep(row.Remote,!row.Kept);else store.Keep(row.Path,!row.Kept);}RefreshLocal();}
    void ClearFiles(bool records){var rows=localList.SelectedItems.Cast<FileShelfRow>().ToArray();if(rows.Length==0)return;var eligible=rows.Where(r=>!r.Kept).ToArray();if(records&&MessageBox.Show(Window.GetWindow(this),$"移除 {eligible.Length} 条记录，跳过 {rows.Length-eligible.Length} 个保留文件？本机原始文件不会删除。","清除文件记录",MessageBoxButton.OKCancel)!=MessageBoxResult.OK)return;
        if(sync!=null)sync.Files.Clear(eligible.Where(r=>r.Remote!=null).Select(r=>r.Remote!),records,true);if(records)foreach(var row in eligible.Where(r=>r.IsLocalShelf))store.Remove(row.Path);sync?.Files.Publish();sync?.RequestFileConnection();RefreshLocal();status.Text=records?"已移除所选记录，本机原始文件保留。":"已清理所选下载缓存，保留的文件已跳过。";
    }
    async Task DownloadSelected(){if(batch!=null)return;var rows=localList.SelectedItems.Cast<FileShelfRow>().ToArray();if(rows.Length==0){status.Text="请先选择文件。";return;}var folder=new OpenFolderDialog{Title="选择下载文件夹",Multiselect=false};if(folder.ShowDialog(Window.GetWindow(this))!=true)return;
        var token=new CancellationTokenSource();batch=token;batchProgress.Visibility=Visibility.Visible;batchProgress.Value=0;long completed=0,total=Math.Max(1,rows.Sum(r=>r.SizeBytes));var started=DateTime.UtcNow;FileShelfRow? current=null;
        var timer=new DispatcherTimer{Interval=TimeSpan.FromMilliseconds(250)};timer.Tick+=(_,_)=>{long bytes=Math.Min(total,completed+(current?.Remote?.Done??0));batchProgress.Value=100d*bytes/total;double elapsed=(DateTime.UtcNow-started).TotalSeconds;string eta=bytes>0&&elapsed>1?" · 预计剩余 "+TimeSpan.FromSeconds((total-bytes)*elapsed/bytes).ToString(@"mm\:ss"):"";status.Text=$"{current?.Name} · {FormatSize(bytes)} / {FormatSize(total)}{eta}";};timer.Start();
        try{foreach(var row in rows){current=row;token.Token.ThrowIfCancellationRequested();string source=row.HasLocalFile?row.Path:row.Remote!=null&&sync!=null?await sync.Files.Download(row.Remote,token.Token):throw new IOException("文件不可用");await Task.Run(()=>{token.Token.ThrowIfCancellationRequested();string destination=System.IO.Path.Combine(folder.FolderName,row.Name);for(int n=1;File.Exists(destination);n++)destination=System.IO.Path.Combine(folder.FolderName,System.IO.Path.GetFileNameWithoutExtension(row.Name)+$" ({n})"+System.IO.Path.GetExtension(row.Name));File.Copy(source,destination,false);},token.Token);completed+=row.SizeBytes;current=null;}status.Text=$"已将 {rows.Length} 个文件保存到 {folder.FolderName}";}
        catch(Exception e){status.Text=token.IsCancellationRequested?"下载已取消；正在传输的设备会自动重连。":$"下载未完成：{e.Message}。已完成文件保留，可重新选择后重试。";}
        finally{timer.Stop();batchProgress.Visibility=Visibility.Collapsed;batch=null;token.Dispose();RefreshLocal();}
    }
    void SendSelected()
    {
        if(sync==null||localList.SelectedItem is not FileShelfRow row||!row.IsAvailable||peers.SelectedItem is not FilePeer peer)return;
        try{var id=sync.Transfers.Send(peer.Key,row.Path);RefreshTransfers(id);status.Text="已发起文件传输；接收端需要确认。";}
        catch(Exception e){status.Text=e is IOException?e.Message:"文件发送未完成，请检查设备连接后重试。";}
    }
    void AcceptSelected(){if(sync==null||transferList.SelectedItem is not FileShelfRow row||row.TransferId is not Guid id)return;Run(()=>sync.Transfers.Accept(id));}
    void DeclineSelected(){if(sync==null||transferList.SelectedItem is not FileShelfRow row||row.TransferId is not Guid id)return;Run(()=>sync.Transfers.Decline(id));}
    void CancelSelected(){if(sync==null||transferList.SelectedItem is not FileShelfRow row||row.TransferId is not Guid id)return;Run(()=>sync.Transfers.Cancel(id));}
    void SaveSelectedAs()
    {
        if(transferList.SelectedItem is not FileShelfRow row||row.Phase!=TransferPhase.Completed||row.Outgoing||!File.Exists(row.Path))return;
        var dialog=new SaveFileDialog{FileName=row.Name,Filter="所有文件 (*.*)|*.*",OverwritePrompt=true,CheckPathExists=true};
        if(dialog.ShowDialog(Window.GetWindow(this))!=true)return;
        try{File.Copy(row.Path,dialog.FileName,true);status.Text="文件已另存为："+dialog.FileName;}
        catch(IOException e){status.Text="保存失败："+e.Message;}
    }
    void OpenSelectedLocal()
    {
        if(localList.SelectedItem is not FileShelfRow row)return;if(!row.IsAvailable){status.Text="文件尚未下载，请先点击下载 / 另存为。";return;}
        if(!FilePreviewWindow.CanPreview(row.Path)){status.Text="此类型暂不支持内嵌预览，可在资源管理器中打开。";return;}
        var preview=new FilePreviewWindow(row.Path){Owner=Window.GetWindow(this)};preview.Show();
    }
    async Task SaveLocalAs()
    {
        if(batch!=null)return;
        if(localList.SelectedItems.Count!=1){await DownloadSelected();return;}
        if(localList.SelectedItem is not FileShelfRow row)return;
        var dialog=new SaveFileDialog{FileName=row.Name,Title="另存为",OverwritePrompt=true};if(dialog.ShowDialog(Window.GetWindow(this))!=true)return;
        var token=new CancellationTokenSource();batch=token;
        try{string source=row.Path;if(!row.IsAvailable){if(row.Remote==null||sync==null)throw new IOException("文件当前不可用");status.Text="正在下载后另存为";source=await sync.Files.Download(row.Remote,token.Token);}token.Token.ThrowIfCancellationRequested();if(!string.Equals(System.IO.Path.GetFullPath(source),System.IO.Path.GetFullPath(dialog.FileName),StringComparison.OrdinalIgnoreCase))File.Copy(source,dialog.FileName,true);status.Text="已另存为";}
        catch(OperationCanceledException){status.Text="已取消另存为";}catch(Exception e){status.Text=e is IOException?"保存失败："+e.Message:"另存为未完成，请检查连接";}
        finally{batch=null;token.Dispose();if(!closed)RefreshLocal();}
    }
    void ForwardSelected()
    {
        if(localList.SelectedItem is not FileShelfRow row||!row.IsAvailable)return;
        var menu=new ContextMenu();foreach(var peer in sync?.Transfers.Peers()??Array.Empty<FilePeer>()){var item=new MenuItem{Header="发送到 "+peer.Name};item.Click+=(_,_)=>Run(()=>sync!.Transfers.Send(peer.Key,row.Path));menu.Items.Add(item);}
        if(menu.Items.Count==0)menu.Items.Add(new MenuItem{Header="连接局域网设备后可转发",IsEnabled=false});menu.PlacementTarget=forwardCommand;menu.IsOpen=true;
    }
    void ShowProperties()
    {
        if(localList.SelectedItem is not FileShelfRow row)return;
        var window=new Window{Title="属性",Width=460,Height=360,MinWidth=340,MinHeight=280,Owner=Window.GetWindow(this),WindowStartupLocation=WindowStartupLocation.CenterOwner};window.SetResourceReference(Window.BackgroundProperty,"FloatClip.Background");window.SetResourceReference(Window.ForegroundProperty,"FloatClip.Text");
        var panel=new Grid{Margin=new Thickness(20)};panel.ColumnDefinitions.Add(new(){Width=new GridLength(86)});panel.ColumnDefinitions.Add(new(){Width=new GridLength(1,GridUnitType.Star)});
        var values=new[]{("名称",row.Name),("类型",row.Type),("大小",FileSizeFormatter.Format(row.SizeBytes)),("修改时间",row.Modified),("来源",row.Source),("本机状态",row.Status),("位置",row.Path)};
        for(int index=0;index<values.Length;index++){panel.RowDefinitions.Add(new(){Height=GridLength.Auto});var label=new TextBlock{Text=values[index].Item1,Margin=new Thickness(0,0,10,14)};var value=new TextBlock{Text=values[index].Item2,TextWrapping=TextWrapping.Wrap,Margin=new Thickness(0,0,0,14)};Grid.SetRow(label,index);Grid.SetRow(value,index);Grid.SetColumn(value,1);panel.Children.Add(label);panel.Children.Add(value);}window.Content=new ScrollViewer{Content=panel,VerticalScrollBarVisibility=ScrollBarVisibility.Auto};FloatClip.Desktop.Services.ClipboardBackgroundService.Configure(window,FloatClip.Desktop.Design.DesignPreferences.Load(preferencesRoot),caption:true);window.Show();
    }
    void RevealSelectedLocal()
    {
        if(localList.SelectedItem is not FileShelfRow row||!row.IsAvailable)return;
        try{var start=new ProcessStartInfo("explorer.exe"){UseShellExecute=true};start.ArgumentList.Add("/select,"+row.Path);Process.Start(start);}
        catch(Exception e){status.Text="无法在资源管理器中定位文件："+e.Message;}
    }
    void OpenSelectedTransfer()
    {
        if(transferList.SelectedItem is FileShelfRow row&&row.Path.Length>0&&File.Exists(row.Path)&&FilePreviewWindow.CanPreview(row.Path))new FilePreviewWindow(row.Path){Owner=Window.GetWindow(this)}.Show();
    }
    void OpenPath(string path)
    {
        try{Process.Start(new ProcessStartInfo(path){UseShellExecute=true});status.Text="已交给系统默认应用打开。";}
        catch(Exception e){status.Text="无法打开文件："+e.Message;}
    }
    void ForgetSelected()
    {
        if(sync==null||transferList.SelectedItem is not FileShelfRow row||row.TransferId is not Guid id||row.Outgoing)return;
        Run(()=>sync.Transfers.ForgetReceived(id));
    }
    void Run(Action action)
    {
        try{action();RefreshPeers();RefreshTransfers();RefreshActions();}
        catch(Exception e){status.Text=e is IOException?e.Message:"操作未完成，请检查连接后重试。";}
    }
    static string SizeLabel(long size)=>FormatSize(size);
}
