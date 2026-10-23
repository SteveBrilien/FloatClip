using System;
using System.Linq;
using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using FloatClip.Desktop.Services;
using Wpf.Ui.Controls;
namespace FloatClip.Desktop.ViewModels;
public sealed class CategoryPickerViewModel:ObservableObject
{
    string category,error="";
    public string[] Categories{get;}
    public string Category{get=>category;set=>SetProperty(ref category,value);}
    public string Error{get=>error;private set=>SetProperty(ref error,value);}
    public IRelayCommand SaveCommand{get;}
    public event Action? Saved;
    public CategoryPickerViewModel(CategoryService service,string id,string current)
    {
        category=current;Categories=service.List().Select(c=>c.Name).ToArray();
        SaveCommand=new RelayCommand(()=>{try{service.Assign(id,Category);Error="";Saved?.Invoke();}catch(Exception e){Error=e.Message;}});
    }
}
public sealed record CategoryIconChoice(string Id,string Label,SymbolRegular Symbol);
public static class CategorySymbols
{
    public static SymbolRegular For(string id)=>Enum.TryParse<SymbolRegular>(CategoryService.Icons.FirstOrDefault(c=>c.Id==id)?.Symbol,out var value)?value:SymbolRegular.Folder24;
    public static CategoryIconChoice[] Choices=>CategoryService.Icons.Select(c=>new CategoryIconChoice(c.Id,c.Label,For(c.Id))).ToArray();
}
public sealed class CategoryAppearanceViewModel:ObservableObject
{
    readonly CategoryService service;readonly Action changed,reload;string color,icon,editedName,error="";bool confirming;
    public string Name{get;}
    public bool CanModify=>Name!="未分类";
    public string EditedName{get=>editedName;set=>SetProperty(ref editedName,value);}
    public string Error{get=>error;private set=>SetProperty(ref error,value);}
    public string DeleteLabel=>confirming?"确认删除":"删除";
    public CategoryColor[] Colors=>CategoryService.Colors;
    public CategoryIconChoice[] Icons=>CategorySymbols.Choices;
    void Run(Action action){try{action();Error="";changed();}catch(Exception e){Error=e.Message;}}
    public string Color{get=>color;set{if(value==color||value==null)return;Run(()=>{service.SetColor(Name,value);SetProperty(ref color,value);});}}
    public string Icon{get=>icon;set{if(value==icon||value==null)return;Run(()=>{service.SetIcon(Name,value);SetProperty(ref icon,value);});}}
    public IRelayCommand SaveCommand{get;}
    public IRelayCommand DeleteCommand{get;}
    public IRelayCommand CancelCommand{get;}
    public CategoryAppearanceViewModel(CategoryService service,CategoryDefinition category,Action changed,Action? reload=null)
    {
        this.service=service;this.changed=changed;this.reload=reload??(()=>{});Name=category.Name;editedName=Name;color=category.Color;icon=category.Icon;
        SaveCommand=new RelayCommand(()=>Run(()=>{service.Rename(Name,EditedName);this.reload();}),()=>CanModify);
        DeleteCommand=new RelayCommand(()=>{if(!confirming){confirming=true;OnPropertyChanged(nameof(DeleteLabel));Error="再次点击确认删除；记录将移到未分类。";}else Run(()=>{service.Delete(Name);this.reload();});},()=>CanModify);
        CancelCommand=new RelayCommand(()=>{EditedName=Name;confirming=false;Error="";OnPropertyChanged(nameof(DeleteLabel));});
    }
}
public sealed class CategorySettingsViewModel:ObservableObject
{
    readonly CategoryService service;readonly Action changed;
    string name="",error="";
    public ObservableCollection<CategoryAppearanceViewModel> Categories{get;}=new();
    public string NewName{get=>name;set=>SetProperty(ref name,value);}
    public string Error{get=>error;private set=>SetProperty(ref error,value);}
    public IRelayCommand AddCommand{get;}
    public void Reload(){Categories.Clear();foreach(var c in service.List())Categories.Add(new(service,c,changed,Reload));}
    public CategorySettingsViewModel(CategoryService service,Action changed)
    {
        this.service=service;this.changed=changed;
        Reload();AddCommand=new RelayCommand(()=>{try{string value=CategoryService.Validate(NewName);if(service.List().Any(c=>c.Name==value))throw new ArgumentException("分类已存在");service.SetColor(value,"blue");NewName="";Error="";Reload();changed();}catch(Exception e){Error=e.Message;}});
    }
}
