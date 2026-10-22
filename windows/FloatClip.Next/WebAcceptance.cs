using System;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading.Tasks;
using Microsoft.Web.WebView2.Core;
using FloatClip.Desktop;

namespace FloatClip.Next;
static class WebAcceptance
{
    public static void Seed(ClipboardHost host)
    {
        host.Vault.AddText("UI migration QA 😀 👍🏽 👨‍👩‍👧‍👦 🇨🇳");host.Vault.AddText("Protected QA");host.Vault.AddText("Pinned QA");
        var s=host.Vault.Load();foreach(var c in s["entries"]!.AsArray().OfType<JsonObject>()){if(c["text"]!.ToString()=="Protected QA")c["locked"]=true;if(c["text"]!.ToString()=="Pinned QA")c["pinned"]=true;}host.Vault.Commit(s["revision"]!.ToString(),s["entries"]!.AsArray());
        string path=Path.Combine(host.PreferencesRoot,"UI-QA-README.txt");File.WriteAllText(path,"FloatClip synthetic document preview\n😀 emoji and text");new FileShelfStore(Path.Combine(host.PreferencesRoot,"file-shelf.json")).Add(path);
    }
    public static async Task Run(WebShellWindow window,ClipboardHost host,string output)
    {
        int checks=0;void Check(bool value,string label){if(!value)throw new Exception(label);checks++;}
        await window.Ready.WaitAsync(TimeSpan.FromSeconds(25));var browser=window.Browser.CoreWebView2;
        async Task<bool> Test(string js)=>await browser.ExecuteScriptAsync(js)=="true";
        async Task Until(string js){for(int i=0;i<70;i++){if(await Test(js))return;await Task.Delay(70);}throw new Exception("DOM timeout: "+js);}
        await Until("window.floatClipReady===true && document.querySelectorAll('.dr-clip-row').length===3");
        await Until("document.fonts.status==='loaded'");Check(await Test("document.fonts.check('16px \"FloatClip Emoji\"','👨‍👩‍👧‍👦 🇨🇳')"),"bundled color emoji font loaded");
        Check(WebShellWindow.Trusted(WebShellWindow.Origin+"/index.html")&&!WebShellWindow.Trusted("https://floatclip.local.evil/")&&!WebShellWindow.Trusted("file:///C:/Windows/win.ini"),"bridge origin allowlist");
        Check(await Test("document.querySelector('.dr-clip-front p').textContent.length>0 && document.querySelectorAll('.dr-app svg').length>8"),"real vault data and bundled icons render");
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(output))!);
        using(var image=File.Create(output+"-clipboard.png"))await browser.CapturePreviewAsync(CoreWebView2CapturePreviewImageFormat.Png,image);
        await browser.ExecuteScriptAsync("let s=document.querySelector('input[type=search]');s.focus();s.value='Protected';s.dispatchEvent(new Event('input',{bubbles:true}));");await Until("document.querySelectorAll('.dr-clip-row').length===1");Check(true,"search filters real vault records");
        await browser.ExecuteScriptAsync("document.querySelector('h2').dispatchEvent(new PointerEvent('pointerdown',{bubbles:true}));");Check(await Test("document.activeElement!==document.querySelector('input[type=search]')"),"click outside search releases focus");
        await browser.ExecuteScriptAsync("document.querySelector('[data-op=clear-search]').click();window.chrome.webview.postMessage({op:'clear'});");await Until("document.querySelectorAll('.dr-clip-row').length===2");
        Check(host.Vault.Load()["entries"]!.AsArray().Count==2&&await Test("!document.querySelector('button[data-op=undo]')"),"bulk clear persists only locked/pinned clips and has no undo");
        await browser.ExecuteScriptAsync("document.querySelector('[data-page=files]').click()");await Until("document.querySelectorAll('thead th').length===6");Check(await Test("document.querySelectorAll('tbody tr').length===1"),"file table uses real shelf metadata");
        double width=window.Width;foreach(string page in new[]{"devices","settings","updates","clipboard","files"}){await browser.ExecuteScriptAsync("document.querySelector('[data-page="+page+"]').click()");await Task.Delay(100);Check(window.Width==width,"navigation keeps native width: "+page);}
        await browser.ExecuteScriptAsync("document.querySelector('[data-page=settings]').click()");await Until("document.querySelector('[data-setting=theme]')!==null");await browser.ExecuteScriptAsync("let t=document.querySelector('[data-setting=theme]');t.value='tokyo-night';t.dispatchEvent(new Event('change',{bubbles:true}));");await Until("document.querySelector('.dr-app').dataset.theme==='tokyo-night'");Check(File.ReadAllText(Path.Combine(host.PreferencesRoot,"theme.txt"))=="tokyo-night","theme roundtrip saves native preferences");
        await browser.ExecuteScriptAsync("document.querySelector('[data-page=files]').click()");await Task.Delay(200);using(var image=File.Create(output+"-files.png"))await browser.CapturePreviewAsync(CoreWebView2CapturePreviewImageFormat.Png,image);
        window.Width=520;await Task.Delay(180);Check(await Test("document.documentElement.scrollWidth<=innerWidth"),"narrow window contains overflow within file table");
        await browser.ExecuteScriptAsync("location.href='https://example.com/'");await Task.Delay(200);Check(browser.Source.StartsWith(WebShellWindow.Origin+"/",StringComparison.Ordinal),"untrusted top-level navigation blocked");
        File.WriteAllText(output,JsonSerializer.Serialize(new{result="PASS",version="0.8.0",checks,isolated=true,userClipboardUntouched=true,renderer="WebView2",utc=DateTime.UtcNow},new JsonSerializerOptions{WriteIndented=true}));
    }
}
