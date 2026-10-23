using System;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Wpf.Ui.Controls;
using FloatClip.Desktop.Design;

namespace FloatClip.Desktop;

public sealed class ClipboardPage:UserControl{}
public sealed class DevicesPage:UserControl{}
public sealed class FilesPage:UserControl{}
public sealed class SettingsPage:UserControl{}
public sealed class UpdatesPage:UserControl{}

public sealed partial class LocalClipboardWindow
{
    readonly NavigationView navigation=new(){PaneDisplayMode=NavigationViewPaneDisplayMode.Left,IsPaneOpen=false,IsBackButtonVisible=NavigationViewBackButtonVisible.Collapsed,IsPaneToggleVisible=true,OpenPaneLength=160,CompactPaneLength=48};
    readonly DockPanel shell=new();
    readonly Dictionary<Type,FrameworkElement> pages=new();
    ClipboardSharingView? devicesView;
    FileShelfView? filesView;
    Type currentPage=typeof(ClipboardPage);
    bool shellReady,expandedNavigation;
    double clipFontSize=14,cardSpacing=8,panelWidth=520;
    sealed class PageProvider(Func<Type,object> resolve):IServiceProvider{public object GetService(Type type)=>resolve(type);}
    void BuildShell()
    {
        shell.SetResourceReference(Panel.BackgroundProperty,"FloatClip.Background");
        var titleBar=new DockPanel{Height=DesignTokens.Number("Hit.Title"),Margin=new(0,2,8,0),LastChildFill=true,Background=Brushes.Transparent};
        System.Windows.Automation.AutomationProperties.SetAutomationId(titleBar,"WindowDragRegion");
        titleBar.MouseLeftButtonDown+=(_,e)=>{
            for(var p=e.OriginalSource as DependencyObject;p!=null&&p!=titleBar;p=p is Visual?VisualTreeHelper.GetParent(p):LogicalTreeHelper.GetParent(p))if(p is System.Windows.Controls.Primitives.ButtonBase)return;
            if(e.ClickCount==1){DragMove();host.RememberPlacement();e.Handled=true;}
        };
        var close=IconButton("\uE711","收起 FloatClip",Hide);DockPanel.SetDock(close,Dock.Right);titleBar.Children.Add(close);
        var pin=IconButton("\uE718","固定面板",()=>{host.SetPinned(!host.Pinned);BuildShellTitle();});DockPanel.SetDock(pin,Dock.Right);titleBar.Children.Add(pin);
        var brand=new StackPanel{Orientation=Orientation.Horizontal,VerticalAlignment=VerticalAlignment.Center,Margin=new(10,0,0,0)};var mark=BrandMark();mark.LayoutTransform=new ScaleTransform(.7,.7);brand.Children.Add(mark);brand.Children.Add(new System.Windows.Controls.TextBlock{Text="FloatClip",FontSize=13,FontWeight=FontWeights.Medium,VerticalAlignment=VerticalAlignment.Center,Margin=new(8,0,0,0)});
        titleBar.Children.Add(brand);DockPanel.SetDock(titleBar,Dock.Top);shell.Children.Add(titleBar);BuildShellTitle();
        if(Content is Border oldBorder)oldBorder.Child=null;else Content=null;
        var clipboardPage=new ClipboardPage{Content=root};clipboardPage.SetResourceReference(Control.ForegroundProperty,"FloatClip.Text");pages[typeof(ClipboardPage)]=clipboardPage;
        navigation.SetServiceProvider(new PageProvider(type=>{
            if(pages.TryGetValue(type,out var existing))return existing;
            FrameworkElement page;
            if(type==typeof(SettingsPage))page=new SettingsPage{Content=BuildSettingsPage()};
            else if(type==typeof(DevicesPage)){
                devicesView=sync==null?null:new ClipboardSharingView(sync);
                page=new DevicesPage{Content=devicesView??(object)new System.Windows.Controls.TextBlock{Text=syncError??"共享暂不可用，请检查本机存储。",TextWrapping=TextWrapping.Wrap,Margin=new(20)}};
            }else if(type==typeof(FilesPage)){
                filesView=new FileShelfView(sync,prefsRoot);
                page=new FilesPage{Content=filesView};
            }else page=new UpdatesPage{Content=new ScrollViewer{Content=BuildUpdateSection(),Margin=new(20,8,20,16),VerticalScrollBarVisibility=ScrollBarVisibility.Auto}};
            page.SetResourceReference(Control.ForegroundProperty,"FloatClip.Text");pages[type]=page;return page;
        }));
        void Item(string label,SymbolRegular icon,Type type,bool footer=false){var item=new NavigationViewItem{Content=label,Icon=new SymbolIcon(icon){FontSize=18},TargetPageType=type,ToolTip=label};System.Windows.Automation.AutomationProperties.SetAutomationId(item,"Navigate"+type.Name);if(footer)navigation.FooterMenuItems.Add(item);else navigation.MenuItems.Add(item);}
        Item("剪贴板",SymbolRegular.Clipboard24,typeof(ClipboardPage));Item("文件",SymbolRegular.Folder24,typeof(FilesPage));Item("我的设备",SymbolRegular.Phone24,typeof(DevicesPage));Item("设置",SymbolRegular.Settings24,typeof(SettingsPage),true);Item("版本与更新",SymbolRegular.ArrowDownload24,typeof(UpdatesPage),true);
        navigation.Navigated+=(_,args)=>{if(args.Page is FrameworkElement element)currentPage=element.GetType();};
        navigation.SetResourceReference(Control.BackgroundProperty,"FloatClip.Background");navigation.SetResourceReference(Control.ForegroundProperty,"FloatClip.Text");shell.Children.Add(navigation);
        if(Content is Border border)border.Child=shell;else Content=shell;
        shellReady=true;UpdateNavigation();Loaded+=(_,_)=>Navigate(currentPage);Closed+=(_,_)=>{devicesView?.Dispose();filesView?.Dispose();};
    }
    void BuildShellTitle()
    {
        var title=(DockPanel)shell.Children[0];var pin=(System.Windows.Controls.Button)title.Children[1];
        pin.Content=new SymbolIcon(SymbolRegular.Pin24){Filled=host.Pinned,FontSize=17};pin.ToolTip=host.Pinned?"取消固定面板":"固定面板";
        System.Windows.Automation.AutomationProperties.SetName(pin,host.Pinned?"取消固定面板":"固定面板");
    }
    void UpdateNavigation(){navigation.PaneDisplayMode=NavigationViewPaneDisplayMode.Left;navigation.IsPaneOpen=expandedNavigation;}
    void Navigate(Type type){currentPage=type;if(shellReady)navigation.Navigate(type);}
    FrameworkElement SettingRow(string title,string detail,FrameworkElement control)
    {
        var grid=new Grid();grid.ColumnDefinitions.Add(new(){Width=new GridLength(1,GridUnitType.Star)});grid.ColumnDefinitions.Add(new(){Width=GridLength.Auto});
        var labels=new StackPanel{VerticalAlignment=VerticalAlignment.Center};labels.Children.Add(new System.Windows.Controls.TextBlock{Text=title,FontSize=14,TextWrapping=TextWrapping.Wrap});if(detail.Length>0){var hint=MutedLabel(detail,new(0,4,0,0));hint.FontSize=12;labels.Children.Add(hint);}grid.Children.Add(labels);
        control.VerticalAlignment=VerticalAlignment.Center;control.Margin=new(16,0,0,0);Grid.SetColumn(control,1);grid.Children.Add(control);
        return new Border{Child=grid,Style=(Style)Application.Current.FindResource("FloatClip.Setting.Row")};
    }
    FrameworkElement ToggleSetting(string title,string detail,bool selected,Action<bool> change,string id="")
    {
        var toggle=new ToggleSwitch{IsChecked=selected,OnContent="",OffContent="",MinWidth=40};System.Windows.Automation.AutomationProperties.SetName(toggle,title);if(id.Length>0)System.Windows.Automation.AutomationProperties.SetAutomationId(toggle,id);
        toggle.Click+=(_,_)=>Guard(()=>{change(toggle.IsChecked==true);SavePreferences();Refresh();});return SettingRow(title,detail,toggle);
    }
    FrameworkElement SliderSetting(string title,string detail,double min,double max,double value,Action<double> change,string id)
    {
        var panel=new StackPanel{Width=132};var label=MutedLabel(value.ToString("F0"),new(0,0,0,4));label.HorizontalAlignment=HorizontalAlignment.Right;panel.Children.Add(label);
        var slider=new Slider{Minimum=min,Maximum=max,Value=value,TickFrequency=1,IsSnapToTickEnabled=true};System.Windows.Automation.AutomationProperties.SetName(slider,title);System.Windows.Automation.AutomationProperties.SetAutomationId(slider,id);
        slider.ValueChanged+=(_,_)=>Guard(()=>{label.Text=slider.Value.ToString("F0");change(slider.Value);SavePreferences();Refresh();});panel.Children.Add(slider);return SettingRow(title,detail,panel);
    }
}
