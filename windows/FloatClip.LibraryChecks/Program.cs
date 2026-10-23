using System;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json.Nodes;
using System.Security.Cryptography;
using FloatClip.Desktop;
using FloatClip.Share;

static class Program
{
    static int checks;
    static void Check(bool value,string label){if(!value)throw new Exception(label);checks++;}
    static void Throws(Action action,string label){try{action();}catch{checks++;return;}throw new Exception(label);}
    static void ImageChecks(string root,Func<byte[],byte[]> protect,Func<byte[],byte[]> unprotect,byte[] png)
    {
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
        var ordinary=(JsonObject)source.DeepClone();ordinary["id"]="ordinary-image";images.AddImage(png,1,1,ordinary);images.AddText("ordinary-text");
        var protectSnapshot=images.Load();var protectedRows=protectSnapshot["entries"]!.AsArray().OfType<JsonObject>();var locked=protectedRows.Single(c=>c["source"]!["id"]!.GetValue<string>()=="locked-image");locked["locked"]=true;
        images.Commit(protectSnapshot["revision"]!.GetValue<string>(),protectSnapshot["entries"]!.AsArray());
        Check(images.ClearUnprotected()==2,"clear removes ordinary image and text");
        var reboot=new ClipboardVault(imagesRoot,protect,unprotect);reboot.PruneOrphanedImages();var retained=reboot.Load()["entries"]!.AsArray().OfType<JsonObject>().ToList();
        Check(retained.Count==2&&retained.All(c=>reboot.ReadImage(c).AsSpan().SequenceEqual(png)),"protected images survive clear and restart pruning");
        Check(retained.Any(ClipboardVault.IsPinned)&&retained.All(ClipboardVault.IsLocked),"image protection flags survive restart");
        Check(Directory.GetFiles(Path.Combine(imagesRoot,"Images"),"*.bin").Length==2,"orphaned image files pruned after restart");
        Check(reboot.ClearUnprotected()==0,"repeated clear preserves all protected images");
        var oversizedMetadata=reboot.Load();var huge=oversizedMetadata["entries"]![0]!.AsObject();for(int i=0;i<10;i++){var clone=(JsonObject)huge.DeepClone();clone["id"]=Guid.NewGuid().ToString();clone["imageRef"]=Guid.NewGuid().ToString("N");clone["imageBytes"]=(long)ClipboardVault.MaximumImageBytes;oversizedMetadata["entries"]!.AsArray().Add(clone);}
        Throws(()=>reboot.Commit(oversizedMetadata["revision"]!.GetValue<string>(),oversizedMetadata["entries"]!.AsArray()),"aggregate image storage bound ignored");
        Check(reboot.Load()["entries"]!.AsArray().Count==2,"capacity failure removed protected images");
        if(OperatingSystem.IsWindows())
        {
            string dpapiRoot=Path.Combine(root,"WindowsDpapi");var windowsVault=new ClipboardVault(dpapiRoot);windowsVault.AddText("FC-SYNTHETIC-DPAPI-😀");windowsVault.AddImage(png,1,1);
            var saved=windowsVault.Load();foreach(var entry in saved["entries"]!.AsArray().OfType<JsonObject>())entry["locked"]=true;windowsVault.Commit(saved["revision"]!.GetValue<string>(),saved["entries"]!.AsArray());
            var restartedWindowsVault=new ClipboardVault(dpapiRoot);restartedWindowsVault.PruneOrphanedImages();
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
            var imageSource=new JsonObject{["id"]="windows-image",["name"]="电脑",["platform"]="windows"};var imageVault=vault.AddImage(png,1,1,imageSource);
            var imageRow=imageVault["entries"]!.AsArray().OfType<JsonObject>().Single(c=>ClipboardVault.Kind(c)=="image");
            Check(vault.ReadImage(imageRow).AsSpan().SequenceEqual(png),"image blob round trip");
            Check(new ClipboardVault(root,Protect,Unprotect).ReadImage(imageRow).AsSpan().SequenceEqual(png),"image blob survives vault reload");
            var imageAgain=vault.AddImage(png,1,1,imageSource);Check(imageAgain["entries"]!.AsArray().OfType<JsonObject>().Count(c=>ClipboardVault.Kind(c)=="image")==1,"image dedup by source and hash");
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
            string pairRoot=Path.Combine(root,"Pairing"),pin=new string('a',64);byte[] credential=RandomNumberGenerator.GetBytes(32);var pairs=new PairStore(pairRoot,Protect,Unprotect);pairs.Put(pin,credential);
            string relation=Convert.ToHexString(SHA256.HashData(credential)).ToLowerInvariant();
            Check(pairs.KeyForPin(pin)==relation&&pairs.GetByKey(relation).AsSpan().SequenceEqual(credential),"relation key is credential hash");
            var rememberedDevice=new SyncDevice(Guid.NewGuid(),"手机","android");pairs.RememberDevice(relation,rememberedDevice);var described=pairs.Describe(relation);Check(described?.DeviceId==rememberedDevice.Id&&described.Name=="手机","trusted device metadata persisted");
            Check(new PairStore(pairRoot,Protect,Unprotect).Get(pin).AsSpan().SequenceEqual(credential),"authorization survives restart");
            Check(new PairStore(pairRoot,Protect,Unprotect).Describe(relation)?.DeviceId==rememberedDevice.Id,"trusted relation metadata survives restart");
            pairs.RememberBluetooth(relation,0xAABBCCDDEEFF);Check(new PairStore(pairRoot,Protect,Unprotect).Describe(relation)?.BluetoothAddress==0xAABBCCDDEEFF,"trusted Bluetooth locator survives restart");
            byte[] btOnly=RandomNumberGenerator.GetBytes(32);string btOnlyKey=pairs.PutBluetooth(btOnly,0x112233445566);Check(pairs.GetByKey(btOnlyKey).AsSpan().SequenceEqual(btOnly),"Bluetooth-first relation token saved");pairs.RemoveKey(btOnlyKey);
            Check(!Encoding.UTF8.GetString(File.ReadAllBytes(Path.Combine(pairRoot,"paired-devices.dat"))).Contains(Convert.ToBase64String(credential)),"authorization persisted as plaintext");
            var failedPairStore=new PairStore(pairRoot,_=>throw new IOException("disk error"),Unprotect);Throws(()=>failedPairStore.Put(new string('b',64),RandomNumberGenerator.GetBytes(32)),"credential failure accepted");Check(new PairStore(pairRoot,Protect,Unprotect).Count==1,"credential failure lost existing grants");
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
