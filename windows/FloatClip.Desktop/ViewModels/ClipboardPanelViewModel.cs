using System;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using FloatClip.Desktop.Services;
namespace FloatClip.Desktop.ViewModels;
public sealed class ClipboardPanelViewModel:ObservableObject
{
    string searchText="",statusMessage="";
    public string SearchText{get=>searchText;set{if(SetProperty(ref searchText,value))SearchChanged?.Invoke();}}
    public string StatusMessage{get=>statusMessage;private set=>SetProperty(ref statusMessage,value);}
    public IRelayCommand ClearSearchCommand{get;}
    public IRelayCommand ClearHistoryCommand{get;}
    public event Action? SearchChanged;
    public event Action? HistoryChanged;
    public event Action<Exception>? OperationFailed;
    public ClipboardPanelViewModel(IClipboardLibraryService library)
    {
        ClearSearchCommand=new RelayCommand(()=>SearchText="");
        ClearHistoryCommand=new RelayCommand(()=>
        {
            int count;
            try{count=library.ClearUnprotected();}
            catch(Exception error){StatusMessage="清除未完成，详细原因已记录到本机日志";OperationFailed?.Invoke(error);return;}
            StatusMessage=$"已清除 {count} 条普通记录，固定和置顶记录已保留";
            HistoryChanged?.Invoke();
        });
    }
}
