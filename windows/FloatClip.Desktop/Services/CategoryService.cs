using System;
using System.IO;
using System.Linq;
using System.Collections.Generic;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace FloatClip.Desktop.Services;
public sealed record CategoryDefinition(string Name,string Color,string Icon="folder");
public sealed record CategoryColor(string Id,string Label);
public sealed record CategoryIcon(string Id,string Label,string Symbol);
/** Category names remain in the existing vault format; appearance is an independent preference. */
public sealed class CategoryService(ClipboardVault vault,string root)
{
    public static readonly CategoryColor[] Colors=[new("blue","蓝色"),new("teal","青绿"),new("purple","紫色"),new("orange","橙色"),new("red","红色"),new("neutral","中性")];
    public static readonly CategoryIcon[] Icons=[new("folder","文件夹","Folder24"),new("star","星标","Star24"),new("link","链接","Link24"),new("mail","邮件","Mail24"),new("phone","电话","Phone24"),new("location","位置","Location24"),new("code","代码","Code24"),new("work","工作","Briefcase24"),new("lock","保护","LockClosed24"),new("book","阅读","Book24"),new("heart","收藏","Heart24"),new("calendar","日程","Calendar24"),new("document","文档","Document24"),new("tag","标签","Tag24")];
    string PathName=>Path.Combine(root,"category-preferences.json");
    Dictionary<string,string> Load()
    {
        if(!File.Exists(PathName))return new();
        if(new FileInfo(PathName).Length>65536)throw new IOException("分类配置过大，请保留数据后检查");
        return JsonSerializer.Deserialize<Dictionary<string,string>>(File.ReadAllText(PathName))??throw new IOException("分类配置无法读取");
    }
    public CategoryDefinition[] List()
    {
        var saved=Load();
        var hidden=ReadPreference<string[]>("category-hidden.json")??[];
        var icons=ReadPreference<Dictionary<string,string>>("category-icons.json")??new();
        var names=new[]{"未分类","工作","学习","生活"}.Where(n=>n=="未分类"||!hidden.Contains(n)).Concat(saved.Keys).Concat(vault.Load()["entries"]!.AsArray().OfType<JsonObject>().Select(c=>c["category"]?.GetValue<string>()??"未分类"));
        return names.Distinct(StringComparer.Ordinal).Select(name=>new CategoryDefinition(name,saved.TryGetValue(name,out var color)&&Colors.Any(c=>c.Id==color)?color:"blue",icons.TryGetValue(name,out var icon)&&Icons.Any(c=>c.Id==icon)?icon:"folder")).ToArray();
    }
    T? ReadPreference<T>(string file){var path=Path.Combine(root,file);if(!File.Exists(path))return default;if(new FileInfo(path).Length>65536)throw new IOException("分类配置过大");return JsonSerializer.Deserialize<T>(File.ReadAllText(path));}
    void WritePreference<T>(string file,T value){Directory.CreateDirectory(root);var path=Path.Combine(root,file);File.WriteAllText(path+".tmp",JsonSerializer.Serialize(value));File.Move(path+".tmp",path,true);}
    public void SetIcon(string name,string icon){name=Validate(name);if(!Icons.Any(c=>c.Id==icon))throw new ArgumentException("请选择已有图标");var all=ReadPreference<Dictionary<string,string>>("category-icons.json")??new();all[name]=icon;WritePreference("category-icons.json",all);}
    public void Rename(string oldName,string value)
    {
        string name=Validate(value);if(oldName=="未分类")throw new ArgumentException("未分类不能重命名");if(name==oldName)return;
        var current=List().SingleOrDefault(c=>c.Name==oldName)??throw new ArgumentException("分类已移除");
        if(List().Any(c=>c.Name==name))throw new ArgumentException("分类已存在");
        // Appearance is prepared first. If a vault commit fails, every original record is retained.
        SetColor(name,current.Color);SetIcon(name,current.Icon);MoveRecords(oldName,name);RemoveDefinition(oldName);
    }
    public void Delete(string name){if(name=="未分类")throw new ArgumentException("未分类不能删除");MoveRecords(name,"未分类");RemoveDefinition(name);}
    void MoveRecords(string oldName,string name){var snapshot=vault.Load();var entries=snapshot["entries"]!.AsArray();foreach(var row in entries.OfType<JsonObject>().Where(c=>c["category"]?.GetValue<string>()==oldName))row["category"]=name;vault.Commit(snapshot["revision"]!.GetValue<string>(),entries);}
    void RemoveDefinition(string name){var all=Load();all.Remove(name);WritePreference("category-preferences.json",all);var icons=ReadPreference<Dictionary<string,string>>("category-icons.json")??new();icons.Remove(name);WritePreference("category-icons.json",icons);var hidden=(ReadPreference<string[]>("category-hidden.json")??[]).Concat(new[]{name}.Where(n=>n is "工作" or "学习" or "生活")).Distinct().ToArray();WritePreference("category-hidden.json",hidden);}
    public void SetColor(string name,string color)
    {
        name=Validate(name);if(!Colors.Any(c=>c.Id==color))throw new ArgumentException("请选择已有的特征色");
        var all=Load();if(!all.ContainsKey(name)&&all.Count>=128)throw new IOException("最多保存 128 个分类");all[name]=color;
        Directory.CreateDirectory(root);File.WriteAllText(PathName+".tmp",JsonSerializer.Serialize(all));File.Move(PathName+".tmp",PathName,true);
    }
    public void Assign(string id,string name)
    {
        name=Validate(name);var snapshot=vault.Load();var entries=snapshot["entries"]!.AsArray();
        var entry=entries.OfType<JsonObject>().FirstOrDefault(c=>c["id"]?.GetValue<string>()==id)??throw new IOException("这条记录已移除");
        if(!Load().ContainsKey(name))SetColor(name,"blue");
        entry["category"]=name;vault.Commit(snapshot["revision"]!.GetValue<string>(),entries);
    }
    public static string Validate(string value)
    {
        string name=value.Trim();if(name.Length==0||name.Length>80||name.Any(char.IsControl))throw new ArgumentException("分类名称需为 1–80 个字符");return name;
    }
}
