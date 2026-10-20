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
            Throws(()=>vault.Commit("stale-revision",new JsonArray()),"stale writer accepted");Check(vault.Load()["entries"]!.AsArray().Count==2,"stale writer changed storage");
            byte[] encrypted=File.ReadAllBytes(Path.Combine(root,"clipboard.v1.bin"));Check(!Encoding.UTF8.GetString(encrypted).Contains("secret-original"),"test codec wrote plaintext");
            var invalid=vault.Load();invalid["entries"]![0]!["source"]!["id"]="../../escape";Throws(()=>vault.Commit(invalid["revision"]!.GetValue<string>(),invalid["entries"]!.AsArray()),"invalid source accepted");
            Check(vault.Load()["entries"]!.AsArray().Count==2,"validation failure overwrote storage");
            var remote=new SyncDevice(Guid.NewGuid(),"手机","android");var delivered=new SyncEntry(Guid.NewGuid(),remote,1,100,"received-sync");
            vault.ReceiveShared(delivered);vault.ReceiveShared(delivered);Check(vault.Load()["entries"]!.AsArray().Count==3,"delivery idempotence");
            var organized=vault.Load();var remoteRow=organized["entries"]!.AsArray().OfType<JsonObject>().Single(c=>c["id"]!.GetValue<string>()==delivered.Id.ToString());remoteRow["pinned"]=true;remoteRow["category"]="同步分类";vault.Commit(organized["revision"]!.GetValue<string>(),organized["entries"]!.AsArray());
            var newCopy=delivered with {Id=Guid.NewGuid(),Clock=2};vault.ReceiveShared(newCopy);var preserved=vault.Load()["entries"]!.AsArray().OfType<JsonObject>().Single(c=>c["id"]!.GetValue<string>()==newCopy.Id.ToString());Check(preserved["pinned"]!.GetValue<bool>()&&preserved["category"]!.GetValue<string>()=="同步分类","repeated copy keeps organization");
            string pairRoot=Path.Combine(root,"Pairing"),pin=new string('a',64);byte[] credential=RandomNumberGenerator.GetBytes(32);var pairs=new PairStore(pairRoot,Protect,Unprotect);pairs.Put(pin,credential);
            string relation=Convert.ToHexString(SHA256.HashData(credential)).ToLowerInvariant();
            Check(pairs.KeyForPin(pin)==relation&&pairs.GetByKey(relation).AsSpan().SequenceEqual(credential),"relation key is credential hash");
            var rememberedDevice=new SyncDevice(Guid.NewGuid(),"手机","android");pairs.RememberDevice(relation,rememberedDevice);var described=pairs.Describe(relation);Check(described?.DeviceId==rememberedDevice.Id&&described.Name=="手机","trusted device metadata persisted");
            Check(new PairStore(pairRoot,Protect,Unprotect).Get(pin).AsSpan().SequenceEqual(credential),"authorization survives restart");
            Check(new PairStore(pairRoot,Protect,Unprotect).Describe(relation)?.DeviceId==rememberedDevice.Id,"trusted relation metadata survives restart");
            pairs.RememberBluetooth(relation,0xAABBCCDDEEFF);Check(new PairStore(pairRoot,Protect,Unprotect).Describe(relation)?.BluetoothAddress==0xAABBCCDDEEFF,"trusted Bluetooth locator survives restart");
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
            Console.WriteLine("FloatClip clipboard library: "+checks+" persistence, provenance and conflict checks passed (test AES codec; DPAPI requires Windows).");return 0;
        }catch(Exception e){Console.Error.WriteLine(e);return 1;}finally{Directory.Delete(root,true);}
    }
}
