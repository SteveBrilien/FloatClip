using System;
using System.IO;
using System.Linq;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Microsoft.Win32;

namespace FloatClip.Desktop;

/** Offline access remains available even when the WebView runtime or node is unavailable. */
public sealed class LocalClipboardWindow:Window
{
    readonly ClipboardVault vault;readonly StackPanel list=new();readonly TextBox search=new(),draft=new();readonly ComboBox devices=new();
    readonly SolidColorBrush surface,text,muted,line,tint,accent;
    bool rendering;
    sealed record DeviceFilter(string Id,string Name){public override string ToString()=>Name;}
    public LocalClipboardWindow(ClipboardVault vault)
    {
        this.vault=vault;Title="FloatClip · 本机剪贴板";Width=820;Height=720;MinWidth=560;MinHeight=500;WindowStartupLocation=WindowStartupLocation.CenterScreen;FontFamily=new FontFamily("Microsoft YaHei UI, Segoe UI");
        string mode="system";try{mode=File.ReadAllText(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"FloatClip","Host","theme.txt")).Trim();}catch{}
        bool dark=mode=="dark"||mode=="system"&&(Registry.GetValue(@"HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize","AppsUseLightTheme",1) as int?)==0;
        SolidColorBrush B(string c)=>new((Color)ColorConverter.ConvertFromString(c));
        Background=B(dark?"#0C1422":"#F4F7FC");surface=B(dark?"#162235":"#FFFFFF");text=B(dark?"#EDF3FC":"#19283F");muted=B(dark?"#A9B8CD":"#596C85");line=B(dark?"#2D3C52":"#DFE7F2");tint=B(dark?"#243B5A":"#EBF2FF");accent=B(dark?"#8DBBFF":"#245DCE");Foreground=text;
        var root=new DockPanel{Margin=new(24)};Content=root;var header=new StackPanel();DockPanel.SetDock(header,Dock.Top);root.Children.Add(header);
        header.Children.Add(new TextBlock{Text="本机剪贴板",FontSize=25,Margin=new(0,0,0,8)});
        header.Children.Add(new TextBlock{Text="来源设备与分类保留在本机，可离线复制和整理",Foreground=muted,Margin=new(0,0,0,16)});
        var filters=new DockPanel{Margin=new(0,0,0,12)};header.Children.Add(filters);devices.Width=220;devices.Margin=new(0,0,12,0);StyleInput(devices);DockPanel.SetDock(devices,Dock.Left);filters.Children.Add(devices);search.ToolTip="搜索内容";StyleInput(search);filters.Children.Add(search);
        draft.AcceptsReturn=true;draft.MinHeight=64;draft.MaxHeight=150;draft.TextWrapping=TextWrapping.Wrap;StyleInput(draft);header.Children.Add(draft);
        var actions=new WrapPanel{Margin=new(0,9,0,12)};header.Children.Add(actions);
        actions.Children.Add(Button("粘贴保存",()=>{if(!Clipboard.ContainsText())throw new IOException("剪贴板中没有文字");vault.AddText(Clipboard.GetText());Refresh();}));
        actions.Children.Add(Button("保存文字",()=>{vault.AddText(draft.Text);draft.Clear();Refresh();}));
        actions.Children.Add(Button("导出备份",Export));actions.Children.Add(Button("合并导入",Import));
        root.Children.Add(new ScrollViewer{Content=list,VerticalScrollBarVisibility=ScrollBarVisibility.Auto});
        search.TextChanged+=(_,_)=>{if(!rendering)Refresh(false);};devices.SelectionChanged+=(_,_)=>{if(!rendering)Refresh(false);};Activated+=(_,_)=>Guard(()=>Refresh());Guard(()=>Refresh());
    }
    void StyleInput(Control control){control.Background=surface;control.Foreground=text;control.BorderBrush=line;control.Padding=new(10,8,10,8);control.MinHeight=40;}
    Button Button(string label,Action action){var b=new Button{Content=label,Background=tint,Foreground=accent,BorderThickness=new(0),Padding=new(12,8,12,8),Margin=new(0,0,8,0),MinHeight=38};b.Click+=(_,_)=>Guard(action);return b;}
    void Guard(Action action){try{action();}catch(Exception e){MessageBox.Show(this,e is IOException?e.Message:"本机操作未完成，请保留数据并重试。","FloatClip",MessageBoxButton.OK,MessageBoxImage.Information);}}
    void Refresh(bool reloadDevices=true)
    {
        var snapshot=vault.Load();var entries=(JsonArray)snapshot["entries"]!;var own=vault.Device();string selected=(devices.SelectedItem as DeviceFilter)?.Id??"all";
        rendering=true;
        try{
            if(reloadDevices){devices.Items.Clear();devices.Items.Add(new DeviceFilter("all","全部设备"));devices.Items.Add(new DeviceFilter(own["id"]!.GetValue<string>(),"本机 · "+own["name"]!.GetValue<string>()));
                foreach(var g in entries.OfType<JsonObject>().GroupBy(c=>c["source"]!["id"]!.GetValue<string>()).Where(g=>g.Key!=own["id"]!.GetValue<string>()))devices.Items.Add(new DeviceFilter(g.Key,g.First()["source"]!["name"]!.GetValue<string>()+" · "+g.Key[^Math.Min(6,g.Key.Length)..]));
                devices.SelectedItem=devices.Items.OfType<DeviceFilter>().FirstOrDefault(d=>d.Id==selected)??devices.Items[0];}
            list.Children.Clear();var visible=entries.OfType<JsonObject>().Where(c=>(selected=="all"||c["source"]!["id"]!.GetValue<string>()==selected)&&c["text"]!.GetValue<string>().Contains(search.Text,StringComparison.CurrentCultureIgnoreCase)).OrderByDescending(c=>c["pinned"]!.GetValue<bool>()).ThenByDescending(c=>c["updatedAt"]!.GetValue<long>()).ToList();
            foreach(var g in visible.GroupBy(c=>c["source"]!["id"]!.GetValue<string>())){
                list.Children.Add(new TextBlock{Text=g.Key==own["id"]!.GetValue<string>()?"本机":g.First()["source"]!["name"]!.GetValue<string>(),FontSize=16,FontWeight=FontWeights.SemiBold,Margin=new(0,15,0,10)});
                foreach(var c in g){string id=c["id"]!.GetValue<string>(),value=c["text"]!.GetValue<string>();var panel=new StackPanel();var card=new Border{Background=surface,BorderBrush=line,BorderThickness=new(1),CornerRadius=new(13),Padding=new(16),Margin=new(0,0,0,11),Child=panel};list.Children.Add(card);
                    panel.Children.Add(new TextBlock{Text=c["category"]!.GetValue<string>()+(c["pinned"]!.GetValue<bool>()?" · 已置顶":""),FontSize=12,Foreground=muted});
                    panel.Children.Add(new TextBox{Text=value,IsReadOnly=true,TextWrapping=TextWrapping.Wrap,MaxHeight=190,Background=Brushes.Transparent,Foreground=text,BorderThickness=new(0),Padding=new(0,12,0,12),VerticalScrollBarVisibility=ScrollBarVisibility.Auto});
                    var row=new WrapPanel();panel.Children.Add(row);row.Children.Add(Button("复制",()=>Clipboard.SetText(value)));row.Children.Add(Button(c["pinned"]!.GetValue<bool>()?"取消置顶":"置顶",()=>Edit(id,n=>n["pinned"]=!n["pinned"]!.GetValue<bool>())));row.Children.Add(Button("删除",()=>Edit(id,null)));
                    var category=new TextBox{Text=c["category"]!.GetValue<string>(),Width=125,MaxLength=80};StyleInput(category);category.ToolTip="修改分类后离开输入框保存";row.Children.Add(category);category.LostKeyboardFocus+=(_,_)=>{if(category.Text.Trim()!=c["category"]!.GetValue<string>())Guard(()=>Edit(id,n=>n["category"]=string.IsNullOrWhiteSpace(category.Text)?"未分类":category.Text.Trim()));};
                }
            }
            if(visible.Count==0)list.Children.Add(new TextBlock{Text="暂无内容，粘贴保存或接收设备发送的文字。",Foreground=muted,Margin=new(0,30,0,0)});
        }finally{rendering=false;}
    }
    void Edit(string id,Action<JsonObject>? edit){var snapshot=vault.Load();var entries=snapshot["entries"]!.AsArray();var c=entries.OfType<JsonObject>().FirstOrDefault(x=>x["id"]!.GetValue<string>()==id);if(c==null)return;if(edit==null)entries.Remove(c);else edit(c);vault.Commit(snapshot["revision"]!.GetValue<string>(),entries);Refresh();}
    void Export(){var dialog=new SaveFileDialog{FileName="FloatClip-clipboard-"+DateTime.Now.ToString("yyyy-MM-dd")+".json",Filter="剪贴板备份 (*.json)|*.json",OverwritePrompt=true};if(dialog.ShowDialog(this)!=true)return;var snapshot=vault.Load();File.WriteAllText(dialog.FileName,new JsonObject{["format"]="FloatClip.clipboard",["schema"]=1,["entries"]=snapshot["entries"]!.DeepClone()}.ToJsonString(new(){WriteIndented=true}));}
    void Import(){var dialog=new OpenFileDialog{Filter="剪贴板备份 (*.json)|*.json",CheckFileExists=true};if(dialog.ShowDialog(this)!=true)return;if(new FileInfo(dialog.FileName).Length>ClipboardVault.MaximumBytes)throw new IOException("备份最大 16 MiB");var doc=JsonNode.Parse(File.ReadAllText(dialog.FileName))!;if(doc["format"]?.GetValue<string>()!="FloatClip.clipboard"||doc["schema"]?.GetValue<int>()!=1)throw new IOException("请导入新版 FloatClip 剪贴板备份");var incoming=doc["entries"]!.AsArray();ClipboardVault.Validate(incoming);if(MessageBox.Show(this,"将备份记录合并到本机剪贴板，保留设备来源和分类。继续？","FloatClip",MessageBoxButton.OKCancel)!=MessageBoxResult.OK)return;
        var snapshot=vault.Load();var entries=snapshot["entries"]!.AsArray();foreach(var c in incoming.OfType<JsonObject>()){var old=entries.OfType<JsonObject>().FirstOrDefault(x=>x["id"]!.GetValue<string>()==c["id"]!.GetValue<string>());if(old!=null){if(old["source"]!["id"]!.GetValue<string>()!=c["source"]!["id"]!.GetValue<string>())throw new IOException("备份中存在来源冲突");if(c["updatedAt"]!.GetValue<long>()<=old["updatedAt"]!.GetValue<long>())continue;entries.Remove(old);}entries.Add(c.DeepClone());}vault.Commit(snapshot["revision"]!.GetValue<string>(),entries);Refresh();}
}
