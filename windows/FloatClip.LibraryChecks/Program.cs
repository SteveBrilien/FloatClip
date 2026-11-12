using System;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json.Nodes;
using System.Security.Cryptography;
using FloatClip.Desktop;
using FloatClip.Share;
using FloatClip.Desktop.Services;

static class Program
{
    static int checks;
    static void Check(bool value,string label){if(!value)throw new Exception(label);checks++;}
    static void Throws(Action action,string label){try{action();}catch{checks++;return;}throw new Exception(label);}
    static void CategoryChecks(string root)
    {
        var vault=new ClipboardVault(Path.Combine(root,"CategoryVault"),b=>b,b=>b);vault.AddText("protected category content");
        var service=new CategoryService(vault,Path.Combine(root,"CategoryPreferences"));var snapshot=vault.Load();var row=snapshot["entries"]![0]!.AsObject();
        row["pinned"]=true;row["locked"]=true;row["category"]="工作";string id=row["id"]!.GetValue<string>();vault.Commit(snapshot["revision"]!.GetValue<string>(),snapshot["entries"]!.AsArray());
        service.SetColor("工作","purple");service.SetIcon("工作","book");service.Rename("工作","研究");
        Check(service.List().Single(c=>c.Name=="研究") is {Color:"purple",Icon:"book"},"rename retains feature color and icon");
        Check(!service.List().Any(c=>c.Name=="工作"),"renamed built-in category does not reappear");
        Throws(()=>service.Rename("研究","学习"),"duplicate category rename accepted");Throws(()=>service.Delete("未分类"),"unclassified group deletion accepted");
        service.Delete("研究");var retained=vault.Load()["entries"]!.AsArray().OfType<JsonObject>().Single();
        Check(retained["id"]!.GetValue<string>()==id&&retained["category"]!.GetValue<string>()=="未分类"&&ClipboardVault.IsPinned(retained)&&ClipboardVault.IsLocked(retained),"delete moves protected content without deleting records");
        Check(!new CategoryService(vault,Path.Combine(root,"CategoryPreferences")).List().Any(c=>c.Name is "研究" or "工作"),"deleted group remains absent after reload");
    }
    static void RecoveryChecks(string root,Func<byte[],byte[]> protect,Func<byte[],byte[]> unprotect,byte[] png)
    {
        string live=Path.Combine(root,"RecoveryLive"),mirror=Path.Combine(root,"RecoveryMirror");
        var vault=new ClipboardVault(live,protect,unprotect,recoveryDirectory:mirror);
        string own=vault.Device()["id"]!.GetValue<string>();
        vault.AddText("protected text");
        var snapshot=vault.AddImage(png,1,1);
        var entries=snapshot["entries"]!.AsArray();
        foreach(var row in entries.OfType<JsonObject>()){row["pinned"]=true;row["locked"]=true;}
        vault.Commit(snapshot["revision"]!.GetValue<string>(),entries);
        vault.AddText("ordinary after protection");
        vault.FlushRecoveryCheckpoint();
        Check(vault.RecoveryWarning==null&&File.Exists(Path.Combine(mirror,"clipboard.v1.bin")),"independent checkpoint saved");
        Directory.Delete(live,true);
        var restarted=new ClipboardVault(live,protect,unprotect,recoveryDirectory:mirror);
        Check(restarted.Device()["id"]!.GetValue<string>()==own,"directory loss restores original device identity");
        var recovered=restarted.Load()["entries"]!.AsArray().OfType<JsonObject>().ToArray();
        Check(recovered.Length==3&&recovered.Count(ClipboardVault.IsLocked)==2&&recovered.Count(ClipboardVault.IsPinned)==2
              &&recovered.All(row=>row["source"]!["id"]!.GetValue<string>()==own),"directory loss restores history and protection under local source");
        var image=recovered.Single(row=>ClipboardVault.Kind(row)=="image");
        string blob=Path.Combine(live,"Images",image["imageRef"]!.GetValue<string>()+".bin");
        File.Delete(blob);
        Check(restarted.ReadImage(image).AsSpan().SequenceEqual(png)&&File.Exists(blob),"missing referenced original repairs from its verified copy");
        Check(restarted.ClearUnprotected()==1,"manual clear removes ordinary history");
        restarted.FlushRecoveryCheckpoint();
        File.Delete(Path.Combine(live,"clipboard.v1.bin"));
        Check(restarted.Load()["entries"]!.AsArray().Count==2,"latest checkpoint preserves explicit clear after index loss");
        File.Delete(Path.Combine(live,"clipboard.v1.bin"));
        File.WriteAllBytes(Path.Combine(mirror,"clipboard.v1.bin"),new byte[]{1,2,3});
        Throws(()=>restarted.Load(),"corrupt checkpoint accepted");
        Check(File.Exists(blob)&&!File.Exists(Path.Combine(live,"clipboard.v1.bin")),"corrupt checkpoint preserves remaining originals");
        string untracked=Path.Combine(root,"MissingIndex");
        var missing=new ClipboardVault(untracked,protect,unprotect);
        string savedImage=Guid.NewGuid().ToString("N");
        string untrackedBlob=Path.Combine(untracked,"Images",savedImage+".bin");
        Directory.CreateDirectory(Path.GetDirectoryName(untrackedBlob)!);
        File.WriteAllBytes(untrackedBlob,protect(png));
        missing.RemoveUnreferencedImages(new[]{savedImage});
        Check(File.Exists(untrackedBlob),"missing index cannot authorize original deletion");
        var first=missing.AddText("first new row after index loss");
        Check(missing.LoadIfChanged(first["revision"]!.GetValue<string>())==null&&File.Exists(untrackedBlob),"new index preserves unknown originals and an unchanged revision avoids a clone");
    }
    static void ImageChecks(string root,Func<byte[],byte[]> protect,Func<byte[],byte[]> unprotect,byte[] png)
    {
        CategoryChecks(root);
        string imagesRoot=Path.Combine(root,"ImagesTest");var images=new ClipboardVault(imagesRoot,protect,unprotect);
        var source=new JsonObject{["id"]="image-device",["name"]="图片设备",["platform"]="windows"};
        JsonObject Row(ClipboardVault v)=>v.Load()["entries"]!.AsArray().OfType<JsonObject>().First(c=>ClipboardVault.Kind(c)=="image");
        string Blob(JsonObject row)=>Path.Combine(imagesRoot,"Images",row["imageRef"]!.GetValue<string>()+".bin");
        images.AddImage(png,1,1,source);var row=Row(images);string identity=row["id"]!.GetValue<string>();
        Check(!File.ReadAllBytes(Blob(row)).AsSpan().SequenceEqual(png),"image bytes stored encrypted");
        var organized=images.Load();var arranged=organized["entries"]![0]!.AsObject();arranged["pinned"]=true;arranged["locked"]=true;arranged["category"]="留存图片";images.Commit(organized["revision"]!.GetValue<string>(),organized["entries"]!.AsArray());
        File.Delete(Blob(row));images.AddImage(png,1,1,source);row=Row(images);
        Check(images.ReadImage(row).AsSpan().SequenceEqual(png),"copy repairs missing image blob");
        Check(row["id"]!.GetValue<string>()==identity&&ClipboardVault.IsPinned(row)&&ClipboardVault.IsLocked(row)&&row["category"]!.GetValue<string>()=="留存图片","image repair preserves organization");
        File.WriteAllText(Blob(row),"damaged-image");images.AddImage(png,1,1,source);row=Row(images);
        Check(images.ReadImage(row).AsSpan().SequenceEqual(png),"copy repairs damaged image blob");
        string baseline=images.Load()["revision"]!.GetValue<string>();
        var sharedVault=new ClipboardVault(Path.Combine(root,"SharedImageTest"),protect,unprotect);
        var remoteDevice=new SyncDevice(Guid.NewGuid(),"远端手机","android");var remoteEvent=Guid.NewGuid();
        sharedVault.ReceiveSharedImage(remoteEvent,remoteDevice,png,1,1,1234);
        var shared=sharedVault.Load()["entries"]!.AsArray().OfType<JsonObject>().Single();
        Check(shared["id"]!.GetValue<string>()==remoteEvent.ToString()&&shared["source"]!["id"]!.GetValue<string>()==remoteDevice.Id.ToString(),"shared image preserves remote event and source identity");
        Check(sharedVault.ReadImage(shared).AsSpan().SequenceEqual(png),"shared image bytes survive encrypted vault write");
        shared["pinned"]=true;shared["locked"]=true;shared["category"]="远端图片";var sharedSnapshot=sharedVault.Load();var sharedRow=sharedSnapshot["entries"]!.AsArray().OfType<JsonObject>().Single();sharedRow["pinned"]=true;sharedRow["locked"]=true;sharedRow["category"]="远端图片";sharedVault.Commit(sharedSnapshot["revision"]!.GetValue<string>(),sharedSnapshot["entries"]!.AsArray());
        sharedVault.ReceiveSharedImage(Guid.NewGuid(),remoteDevice,png,1,1,2234);var sharedAgain=sharedVault.Load()["entries"]!.AsArray().OfType<JsonObject>().Single();
        Check(ClipboardVault.IsPinned(sharedAgain)&&ClipboardVault.IsLocked(sharedAgain)&&sharedAgain["category"]!.GetValue<string>()=="远端图片","repeated remote image preserves protection and organization");
        Throws(()=>sharedVault.ReceiveSharedImage(Guid.NewGuid(),remoteDevice,png,2,1,2234),"shared image forged dimensions accepted");
        Throws(()=>images.AddImage(png,2,1,source),"forged image dimensions accepted");
        Throws(()=>images.AddImage(png[..8],1,1,source),"truncated PNG accepted");
        var corrupt=(byte[])png.Clone();corrupt[43]^=1;Throws(()=>images.AddImage(corrupt,1,1,source),"corrupt PNG accepted");
        Throws(()=>images.AddImage(new byte[ClipboardVault.MaximumImageBytes+1],1,1,source),"oversize PNG accepted");
        Check(images.Load()["revision"]!.GetValue<string>()==baseline,"invalid images changed metadata");

        var backup=images.ExportBackup();Check(backup["schema"]!.GetValue<int>()==2&&backup["images"]!.AsObject().Count==1,"portable backup embeds image data");
        string restoreRoot=Path.Combine(root,"RestoreTest");var restore=new ClipboardVault(restoreRoot,protect,unprotect);string restoreDevice=restore.Device()["id"]!.GetValue<string>();
        Check(restore.ImportBackup(backup)==1,"portable image imported");var restored=Row(restore);
        Check(restored["imageRef"]!.GetValue<string>()!=row["imageRef"]!.GetValue<string>()&&restore.ReadImage(restored).AsSpan().SequenceEqual(png),"backup restored into fresh encrypted blob");
        Check(restore.ImportBackup(backup)==0,"reimport is idempotent");
        Check(restore.Device()["id"]!.GetValue<string>()==restoreDevice,"import changed installation identity");
        string restoreRevision=restore.Load()["revision"]!.GetValue<string>();int blobCount=Directory.GetFiles(Path.Combine(restoreRoot,"Images")).Length;
        var missing=(JsonObject)backup.DeepClone();missing["images"]=new JsonObject();Throws(()=>restore.ImportBackup(missing),"missing backup blob accepted");
        var invalidHash=(JsonObject)backup.DeepClone();invalidHash["images"]!.AsObject()[row["imageSha256"]!.GetValue<string>()]=Convert.ToBase64String(png.Reverse().ToArray());Throws(()=>restore.ImportBackup(invalidHash),"bad backup hash accepted");
        var forged=(JsonObject)backup.DeepClone();forged["entries"]![0]!["imageWidth"]=2;Throws(()=>restore.ImportBackup(forged),"forged backup dimensions accepted");
        var traversing=(JsonObject)backup.DeepClone();traversing["entries"]![0]!["imageRef"]="../../escape";Throws(()=>restore.ImportBackup(traversing),"image path traversal accepted");
        var sourceConflict=(JsonObject)backup.DeepClone();sourceConflict["entries"]![0]!["source"]!["id"]="different-device";Throws(()=>restore.ImportBackup(sourceConflict),"backup source conflict accepted");
        var legacyImage=(JsonObject)backup.DeepClone();legacyImage["schema"]=1;legacyImage.Remove("images");Throws(()=>restore.ImportBackup(legacyImage),"image reference-only backup accepted");
        Check(restore.Load()["revision"]!.GetValue<string>()==restoreRevision&&Directory.GetFiles(Path.Combine(restoreRoot,"Images")).Length==blobCount,"rejected backups modified storage");

        var texts=new ClipboardVault(Path.Combine(root,"TextBackup"),protect,unprotect);texts.AddText("legacy-text 😀");var legacyText=new JsonObject{["format"]="FloatClip.clipboard",["schema"]=1,["entries"]=texts.Load()["entries"]!.DeepClone()};
        Check(restore.ImportBackup(legacyText)==1,"schema1 text backup compatibility");
        var failed=new ClipboardVault(Path.Combine(root,"FailBackup"),_=>throw new IOException("disk failure"),unprotect);Throws(()=>failed.ImportBackup(backup),"failed image encryption accepted");
        Check(failed.Load()["entries"]!.AsArray().Count==0&&Directory.GetFiles(Path.Combine(root,"FailBackup","Images")).Length==0,"failed import left partial metadata or blobs");
        string failedCommitRoot=Path.Combine(root,"FailCommitBackup");var beforeFailure=new ClipboardVault(failedCommitRoot,protect,unprotect);beforeFailure.AddText("must-survive-import-failure");string priorRevision=beforeFailure.Load()["revision"]!.GetValue<string>();int writes=0;
        var commitFailure=new ClipboardVault(failedCommitRoot,b=>++writes==2?throw new IOException("metadata failure"):protect(b),unprotect);Throws(()=>commitFailure.ImportBackup(backup),"failed metadata commit accepted");
        Check(beforeFailure.Load()["revision"]!.GetValue<string>()==priorRevision&&Directory.GetFiles(Path.Combine(failedCommitRoot,"Images")).Length==0,"metadata commit failure lost prior data or left staged blobs");

        var protectedSource=(JsonObject)source.DeepClone();protectedSource["id"]="locked-image";images.AddImage(png,1,1,protectedSource);
        var ordinary=(JsonObject)source.DeepClone();ordinary["id"]="ordinary-image";images.AddImage(png,1,1,ordinary);
        Check(images.Load()["entries"]!.AsArray().Count==1,"identical images from different devices occupy one row");
        images.AddText("ordinary-text");
        Check(images.ClearUnprotected()==1,"clear removes ordinary text, not protected image");
        var reboot=new ClipboardVault(imagesRoot,protect,unprotect);var retained=reboot.Load()["entries"]!.AsArray().OfType<JsonObject>().ToList();
        Check(retained.Count==1&&reboot.ReadImage(retained.Single()).AsSpan().SequenceEqual(png),"protected image survives clear and restart");
        Check(retained.All(ClipboardVault.IsPinned)&&retained.All(ClipboardVault.IsLocked),"image protection flags survive restart");
        Check(Directory.GetFiles(Path.Combine(imagesRoot,"Images"),"*.bin").Length==1,"deduplicated image blob is stored only once");
        Check(reboot.ClearUnprotected()==0,"repeated clear preserves protected image");
        var oversizedMetadata=reboot.Load();var huge=oversizedMetadata["entries"]![0]!.AsObject();for(int i=0;i<10;i++){var clone=(JsonObject)huge.DeepClone();clone["id"]=Guid.NewGuid().ToString();clone["imageRef"]=Guid.NewGuid().ToString("N");clone["imageBytes"]=(long)ClipboardVault.MaximumImageBytes;oversizedMetadata["entries"]!.AsArray().Add(clone);}
        Throws(()=>reboot.Commit(oversizedMetadata["revision"]!.GetValue<string>(),oversizedMetadata["entries"]!.AsArray()),"aggregate image storage bound ignored");
        Check(reboot.Load()["entries"]!.AsArray().Count==1,"capacity failure removed protected images");
        if(OperatingSystem.IsWindows())
        {
            string dpapiRoot=Path.Combine(root,"WindowsDpapi");var windowsVault=new ClipboardVault(dpapiRoot);windowsVault.AddText("FC-SYNTHETIC-DPAPI-😀");windowsVault.AddImage(png,1,1);
            var saved=windowsVault.Load();foreach(var entry in saved["entries"]!.AsArray().OfType<JsonObject>())entry["locked"]=true;windowsVault.Commit(saved["revision"]!.GetValue<string>(),saved["entries"]!.AsArray());
            var restartedWindowsVault=new ClipboardVault(dpapiRoot);
            Check(restartedWindowsVault.Load()["entries"]!.AsArray().Count==2&&restartedWindowsVault.ClearUnprotected()==0,"real Windows DPAPI protected records survive reload and clear");
            var windowsImage=Row(restartedWindowsVault);Check(restartedWindowsVault.ReadImage(windowsImage).AsSpan().SequenceEqual(png),"real Windows DPAPI image survives reload");
            byte[] disk=File.ReadAllBytes(Path.Combine(dpapiRoot,"clipboard.v1.bin"));Check(!Encoding.UTF8.GetString(disk).Contains("FC-SYNTHETIC-DPAPI"),"real Windows vault contains plaintext");
            Check(ProtectedData.Unprotect(File.ReadAllBytes(Path.Combine(dpapiRoot,"Images",windowsImage["imageRef"]!.GetValue<string>()+".bin")),null,DataProtectionScope.CurrentUser).AsSpan().SequenceEqual(png),"Windows image encryption uses current-user DPAPI");
        }
    }
    static int Main()
    {
        string root=Path.Combine(Path.GetTempPath(),"floatclip-library-checks-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        byte[] key=RandomNumberGenerator.GetBytes(32);
        byte[] Protect(byte[] b){byte[] iv=RandomNumberGenerator.GetBytes(12),tag=new byte[16],cipher=new byte[b.Length];using var aes=new AesGcm(key,16);aes.Encrypt(iv,b,cipher,tag);return iv.Concat(tag).Concat(cipher).ToArray();}
        byte[] Unprotect(byte[] b){byte[] plain=new byte[b.Length-28];using var aes=new AesGcm(key,16);aes.Decrypt(b.AsSpan(0,12),b.AsSpan(28),b.AsSpan(12,16),plain);return plain;}
        try{
            Check(HostPolicy.IsTrusted("https://clip.wmy-cloud.cn/path"),"trusted origin rejected");
            foreach(string url in new[]{"http://clip.wmy-cloud.cn/","https://clip.wmy-cloud.cn:444/","https://clip.wmy-cloud.cn.evil.example/","https://clip.wmy-cloud.cn@evil.example/","file:///clip.wmy-cloud.cn"})Check(!HostPolicy.IsTrusted(url),"untrusted bridge origin accepted");
            var vault=new ClipboardVault(root,Protect,Unprotect);var source=new JsonObject{["id"]="android-a",["name"]="同名设备",["platform"]="android"};
            string device=vault.Device()["id"]!.GetValue<string>();Check(new ClipboardVault(root,Protect,Unprotect).Device()["id"]!.GetValue<string>()==device,"stable installation identity");
            var first=vault.AddText("secret-original",source);var rows=first["entries"]!.AsArray();Check(rows.Count==1,"first item saved");rows[0]!["pinned"]=true;rows[0]!["category"]="工作";
            vault.Commit(first["revision"]!.GetValue<string>(),rows);var repeated=vault.AddText("secret-original",source);Check(repeated["entries"]!.AsArray().Count==1,"dedup within source");Check(repeated["entries"]![0]!["pinned"]!.GetValue<bool>()&&repeated["entries"]![0]!["category"]!.GetValue<string>()=="工作","pin and category preserved");
            var other=(JsonObject)source.DeepClone();other["id"]="android-b";var separate=vault.AddText("secret-original",other);Check(separate["entries"]!.AsArray().Count==2,"same text from distinct devices retained");
            byte[] png=Convert.FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==");
            ImageChecks(root,Protect,Unprotect,png);
            RecoveryChecks(root,Protect,Unprotect,png);
            var imageSource=new JsonObject{["id"]="windows-image",["name"]="电脑",["platform"]="windows"};var imageVault=vault.AddImage(png,1,1,imageSource);
            var imageRow=imageVault["entries"]!.AsArray().OfType<JsonObject>().Single(c=>ClipboardVault.Kind(c)=="image");
            Check(vault.ReadImage(imageRow).AsSpan().SequenceEqual(png),"image blob round trip");
            Check(new ClipboardVault(root,Protect,Unprotect).ReadImage(imageRow).AsSpan().SequenceEqual(png),"image blob survives vault reload");
            var otherImageSource=(JsonObject)imageSource.DeepClone();otherImageSource["id"]="other-device";
            long oldUpdatedAt=imageRow["updatedAt"]!.GetValue<long>();
            var imageAgain=vault.AddImage(png,1,1,otherImageSource);
            Check(imageAgain["entries"]!.AsArray().OfType<JsonObject>().Count(c=>ClipboardVault.Kind(c)=="image")==1,"cross-source SHA-256 dedup");
            Check(imageAgain["entries"]!.AsArray().OfType<JsonObject>().Single(c=>ClipboardVault.Kind(c)=="image")["updatedAt"]!.GetValue<long>()==oldUpdatedAt,"duplicate image does not reorder");
            var clearRoot=Path.Combine(root,"ClearTest");var clearVault=new ClipboardVault(clearRoot,Protect,Unprotect);clearVault.AddText("ordinary-clear");clearVault.AddText("keep-pinned");clearVault.AddText("keep-locked");
            var clearSnapshot=clearVault.Load();var clearRows=clearSnapshot["entries"]!.AsArray().OfType<JsonObject>().ToList();clearRows.Single(c=>c["text"]!.GetValue<string>()=="keep-pinned")["pinned"]=true;clearRows.Single(c=>c["text"]!.GetValue<string>()=="keep-locked")["locked"]=true;clearVault.Commit(clearSnapshot["revision"]!.GetValue<string>(),clearSnapshot["entries"]!.AsArray());
            Check(clearVault.ClearUnprotected()==1,"clear-all removes only unprotected rows");var afterClear=clearVault.Load()["entries"]!.AsArray().OfType<JsonObject>().ToList();
            Check(afterClear.Count==2&&afterClear.Any(c=>c["text"]!.GetValue<string>()=="keep-pinned")&&afterClear.Any(c=>c["text"]!.GetValue<string>()=="keep-locked"),"clear-all preserves pinned and locked rows");
            Throws(()=>vault.Commit("stale-revision",new JsonArray()),"stale writer accepted");Check(vault.Load()["entries"]!.AsArray().Count==3,"stale writer changed storage");
            byte[] encrypted=File.ReadAllBytes(Path.Combine(root,"clipboard.v1.bin"));Check(!Encoding.UTF8.GetString(encrypted).Contains("secret-original"),"test codec wrote plaintext");
            var invalid=vault.Load();invalid["entries"]![0]!["source"]!["id"]="../../escape";Throws(()=>vault.Commit(invalid["revision"]!.GetValue<string>(),invalid["entries"]!.AsArray()),"invalid source accepted");
            Check(vault.Load()["entries"]!.AsArray().Count==3,"validation failure overwrote storage");
            var remote=new SyncDevice(Guid.NewGuid(),"手机","android");var delivered=new SyncEntry(Guid.NewGuid(),remote,1,100,"received-sync");
            vault.ReceiveShared(delivered);vault.ReceiveShared(delivered);Check(vault.Load()["entries"]!.AsArray().Count==4,"delivery idempotence");
            var organized=vault.Load();var remoteRow=organized["entries"]!.AsArray().OfType<JsonObject>().Single(c=>c["id"]!.GetValue<string>()==delivered.Id.ToString());remoteRow["pinned"]=true;remoteRow["category"]="同步分类";vault.Commit(organized["revision"]!.GetValue<string>(),organized["entries"]!.AsArray());
            var newCopy=delivered with {Id=Guid.NewGuid(),Clock=2};vault.ReceiveShared(newCopy);var preserved=vault.Load()["entries"]!.AsArray().OfType<JsonObject>().Single(c=>c["id"]!.GetValue<string>()==newCopy.Id.ToString());Check(preserved["pinned"]!.GetValue<bool>()&&preserved["category"]!.GetValue<string>()=="同步分类","repeated copy keeps organization");
            var orderVault=new ClipboardVault(Path.Combine(root,"RepeatOrder"),Protect,Unprotect);
            var oldCopy=new SyncEntry(Guid.NewGuid(),remote,10,10,"repeat order");orderVault.ReceiveShared(oldCopy);
            orderVault.ReceiveShared(new SyncEntry(Guid.NewGuid(),remote,11,11,"other order"));
            var orderFixture=orderVault.Load();foreach(var item in orderFixture["entries"]!.AsArray().OfType<JsonObject>())item["updatedAt"]=item["id"]!.GetValue<string>()==oldCopy.Id.ToString()?1L:2L;
            orderVault.Commit(orderFixture["revision"]!.GetValue<string>(),orderFixture["entries"]!.AsArray());
            var freshCopy=oldCopy with{Id=Guid.NewGuid(),Clock=12};orderVault.ReceiveShared(freshCopy);
            var ordered=orderVault.Load()["entries"]!.AsArray().OfType<JsonObject>().OrderByDescending(c=>c["updatedAt"]!.GetValue<long>()).ToArray();
            Check(ordered.Length==2&&ordered[0]["id"]!.GetValue<string>()==freshCopy.Id.ToString(),"new copy of remote text moves the existing content to newest position");
            string orderRevision=orderVault.Load()["revision"]!.GetValue<string>();orderVault.ReceiveShared(freshCopy);
            Check(orderVault.Load()["revision"]!.GetValue<string>()==orderRevision,"network retransmission does not reshuffle clipboard history");
            string pairRoot=Path.Combine(root,"Pairing"),pin=new string('a',64);byte[] credential=RandomNumberGenerator.GetBytes(32);var pairs=new PairStore(pairRoot,Protect,Unprotect);pairs.Put(pin,credential);
            string relation=Convert.ToHexString(SHA256.HashData(credential)).ToLowerInvariant();
            Check(pairs.KeyForPin(pin)==relation&&pairs.GetByKey(relation).AsSpan().SequenceEqual(credential),"relation key is credential hash");
            var rememberedDevice=new SyncDevice(Guid.NewGuid(),"手机","android");pairs.RememberDevice(relation,rememberedDevice);var described=pairs.Describe(relation);Check(described?.DeviceId==rememberedDevice.Id&&described.Name=="手机","trusted device metadata persisted");
            pairs.RememberDevice(relation,new SyncDevice(rememberedDevice.Id,"未知设备","android"));Check(pairs.Describe(relation)?.Name=="手机","generic transport name must not overwrite trusted name");
            Check(new PairStore(pairRoot,Protect,Unprotect).Get(pin).AsSpan().SequenceEqual(credential),"authorization survives restart");
            Check(new PairStore(pairRoot,Protect,Unprotect).Describe(relation)?.DeviceId==rememberedDevice.Id,"trusted relation metadata survives restart");
            pairs.RememberBluetooth(relation,0xAABBCCDDEEFF);Check(new PairStore(pairRoot,Protect,Unprotect).Describe(relation)?.BluetoothAddress==0xAABBCCDDEEFF,"trusted Bluetooth locator survives restart");
            byte[] btOnly=RandomNumberGenerator.GetBytes(32);string btOnlyKey=pairs.PutBluetooth(btOnly,0x112233445566);Check(pairs.GetByKey(btOnlyKey).AsSpan().SequenceEqual(btOnly),"Bluetooth-first relation token saved");pairs.RemoveKey(btOnlyKey);
            Check(!Encoding.UTF8.GetString(File.ReadAllBytes(Path.Combine(pairRoot,"paired-devices.dat"))).Contains(Convert.ToBase64String(credential)),"authorization persisted as plaintext");
            var failedPairStore=new PairStore(pairRoot,_=>throw new IOException("disk error"),Unprotect);Throws(()=>failedPairStore.Put(new string('b',64),RandomNumberGenerator.GetBytes(32)),"credential failure accepted");Check(new PairStore(pairRoot,Protect,Unprotect).Count==1,"credential failure lost existing grants");
            var groupedId=Guid.NewGuid();
            var grouped=FloatClip.Desktop.Services.DevicePeerGrouping.Group(new[]{
                new FloatClip.Desktop.Services.DeviceRelationState("lan-key",groupedId,"我的手机",true,true,"lan",false),
                new FloatClip.Desktop.Services.DeviceRelationState("bt-key",groupedId,"未知设备",true,true,null,false)
            },true,true);
            Check(grouped.Length==1&&grouped[0].Name=="我的手机"&&grouped[0].RelatedKeys!.Count==2,"one authenticated identity groups LAN and Bluetooth without losing its name");
            Check(grouped[0].Status=="局域网已连接","offline Bluetooth credential cannot hide a live LAN connection");
            var reversed=FloatClip.Desktop.Services.DevicePeerGrouping.Group(new[]{
                new FloatClip.Desktop.Services.DeviceRelationState("bt-key",groupedId,"unknown",true,true,null,false),
                new FloatClip.Desktop.Services.DeviceRelationState("lan-key",groupedId,"我的手机",true,true,"lan",false)
            },true,true);
            Check(reversed[0].Key==grouped[0].Key,"adding a transport preserves the device row identity");
            Check(FloatClip.Desktop.Services.DevicePeerGrouping.Group(new[]{
                new FloatClip.Desktop.Services.DeviceRelationState("a",Guid.NewGuid(),"同名设备",true,true,null,false),
                new FloatClip.Desktop.Services.DeviceRelationState("b",Guid.NewGuid(),"同名设备",true,true,null,false)
            },true,true).Length==2,"device names alone never merge separate authorizations");
            pairs.Remove(pin);Check(new PairStore(pairRoot,Protect,Unprotect).Count==0,"revoked grant returned after restart");
            string legacyRoot=Path.Combine(root,"LegacyPairing");Directory.CreateDirectory(legacyRoot);string legacyPath=Path.Combine(legacyRoot,"paired-devices.dat");
            byte[] legacyToken=RandomNumberGenerator.GetBytes(32);string legacyPin=new string('c',64);File.WriteAllBytes(legacyPath,Protect(System.Text.Json.JsonSerializer.SerializeToUtf8Bytes(new System.Collections.Generic.Dictionary<string,string>{{legacyPin,Convert.ToBase64String(legacyToken)}})));
            var migrated=new PairStore(legacyRoot,Protect,Unprotect);string legacyRelation=Convert.ToHexString(SHA256.HashData(legacyToken)).ToLowerInvariant();
            Check(migrated.KeyForPin(legacyPin)==legacyRelation&&migrated.GetByKey(legacyRelation).AsSpan().SequenceEqual(legacyToken),"v1 pin authorization did not migrate to stable relation");
            string pairPath=Path.Combine(pairRoot,"paired-devices.dat");File.WriteAllText(pairPath,"corrupt credentials");var damagedPairs=new PairStore(pairRoot,Protect,Unprotect);Check(damagedPairs.Corrupt,"damaged grants not detected");Throws(()=>damagedPairs.Put(pin,credential),"damaged grants silently replaced");Check(File.ReadAllText(pairPath)=="corrupt credentials","damaged credential evidence lost");damagedPairs.Clear();damagedPairs.Put(pin,credential);Check(damagedPairs.Count==1,"explicit reset did not restore pairing");
            var full=vault.Load();var all=full["entries"]!.AsArray();while(all.Count<500){var c=all[0]!.DeepClone();c["id"]=Guid.NewGuid().ToString();c["pinned"]=true;all.Add(c);}foreach(var c in all)c!["pinned"]=true;vault.Commit(full["revision"]!.GetValue<string>(),all);Throws(()=>vault.AddText("overflow"),"pinned data evicted");Check(vault.Load()["entries"]!.AsArray().Count==500,"capacity violated");
            string path=Path.Combine(root,"clipboard.v1.bin");File.WriteAllText(path,"damaged-original");Throws(()=>vault.Load(),"corrupted storage accepted");Throws(()=>vault.AddText("overwrite"),"corrupted storage overwritten");Check(File.ReadAllText(path)=="damaged-original","corrupted source was destroyed");
            Console.WriteLine("FloatClip clipboard library: "+checks+" persistence, provenance and conflict checks passed ("+(OperatingSystem.IsWindows()?"AES test codec and Windows CurrentUser DPAPI":"AES test codec; DPAPI requires Windows")+").");return 0;
        }catch(Exception e){Console.Error.WriteLine(e);return 1;}finally{Directory.Delete(root,true);}
    }
}
