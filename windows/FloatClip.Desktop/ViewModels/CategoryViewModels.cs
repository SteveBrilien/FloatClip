using System;
using System.Linq;
using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using FloatClip.Desktop.Services;
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
public sealed class CategoryAppearanceViewModel:ObservableObject
{
    readonly CategoryService service;readonly Action changed;string color;
    public string Name{get;}
    public CategoryColor[] Colors=>CategoryService.Colors;
    public string Color{get=>color;set{if(value==color)return;service.SetColor(Name,value);SetProperty(ref color,value);changed();}}
    public CategoryAppearanceViewModel(CategoryService service,CategoryDefinition category,Action changed){this.service=service;this.changed=changed;Name=category.Name;color=category.Color;}
}
public sealed class CategorySettingsViewModel:ObservableObject
{
    readonly CategoryService service;readonly Action changed;
    string name="",error="";
    public ObservableCollection<CategoryAppearanceViewModel> Categories{get;}=new();
    public string NewName{get=>name;set=>SetProperty(ref name,value);}
    public string Error{get=>error;private set=>SetProperty(ref error,value);}
    public IRelayCommand AddCommand{get;}
    public void Reload(){Categories.Clear();foreach(var c in service.List())Categories.Add(new(service,c,changed));}
    public CategorySettingsViewModel(CategoryService service,Action changed)
    {
        this.service=service;this.changed=changed;
        Reload();AddCommand=new RelayCommand(()=>{try{string value=CategoryService.Validate(NewName);if(service.List().Any(c=>c.Name==value))throw new ArgumentException("分类已存在");service.SetColor(value,"blue");NewName="";Error="";Reload();changed();}catch(Exception e){Error=e.Message;}});
    }
}
