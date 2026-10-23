using System;
using System.IO;
using System.Linq;
using System.Collections.Generic;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace FloatClip.Desktop.Services;
public sealed record CategoryDefinition(string Name,string Color);
public sealed record CategoryColor(string Id,string Label);
/** Category names remain in the existing vault format; appearance is an independent preference. */
public sealed class CategoryService(ClipboardVault vault,string root)
{
    public static readonly CategoryColor[] Colors=[new("blue","蓝色"),new("teal","青绿"),new("purple","紫色"),new("orange","橙色"),new("red","红色"),new("neutral","中性")];
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
        var names=new[]{"未分类","工作","学习","生活"}.Concat(saved.Keys).Concat(vault.Load()["entries"]!.AsArray().OfType<JsonObject>().Select(c=>c["category"]?.GetValue<string>()??"未分类"));
        return names.Distinct(StringComparer.Ordinal).Select(name=>new CategoryDefinition(name,saved.TryGetValue(name,out var color)&&Colors.Any(c=>c.Id==color)?color:"blue")).ToArray();
    }
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
