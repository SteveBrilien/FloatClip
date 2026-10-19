"""Snapshot tested unified clients. Published version bytes are immutable."""
from pathlib import Path
import hashlib,json,re,zipfile,argparse,subprocess
root=Path(__file__).resolve().parents[1]
version=re.search(r'versionName = "([0-9.]+)"',(root/'app/build.gradle.kts').read_text()).group(1)
destination=root/'analysis/unified-releases'/version;destination.mkdir(parents=True,exist_ok=True)
def freeze(name,data):
 p=destination/name
 if p.exists():
  if p.read_bytes()!=data:raise SystemExit('Immutable version conflict: '+name)
 else:p.write_bytes(data)
 return {'name':name,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}
rows=[]
parser=argparse.ArgumentParser();parser.add_argument('--windows-dir',default='analysis/unified-windows');parser.add_argument('--android-apk');parser.add_argument('--apksigner');options=parser.parse_args()
if options.android_apk:
 if not options.apksigner:raise SystemExit('--apksigner is required for Android publication')
 result=subprocess.run([options.apksigner,'verify','--print-certs',str(root/options.android_apk)],check=True,capture_output=True,text=True)
 expected='89f27902ef697dd6a1802cad39cf3067bf83f0a078bda92f30895c6b62d45dd3'
 signers=re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)',result.stdout)
 if signers!=[expected]:raise SystemExit('Original Android upgrade certificate is required; do not uninstall or clear app data')
 rows.append(freeze('FloatClip-'+version+'.apk',(root/options.android_apk).read_bytes()))
source=root/options.windows_dir
archive=root/'analysis/unified-0.6-windows.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=7) as z:
 for p in sorted(source.rglob('*')):
  if p.is_file() and p.suffix.lower() not in ['.pdb','.xml']:
   info=zipfile.ZipInfo('FloatClip/'+str(p.relative_to(source)),(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,p.read_bytes())
 info=zipfile.ZipInfo('FloatClip/README.txt',(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
 z.writestr(info,('FloatClip '+version+'\n\nWindows 10/11 x64：完整解压后运行 FloatClip.exe。\n首次共享需要 Microsoft Edge WebView2 Runtime；若缺少组件，应用会提供微软官方安装入口。\n主界面与 Android 0.6.0 / iPad PWA 共用共享协议。\n本机剪贴板由当前 Windows 用户加密保存。\n离线工具：FloatClip.exe --clipboard\n旧局域网兼容模式：FloatClip.exe --legacy-lan\n\nPWA：https://clip.wmy-cloud.cn\n').encode('utf-8'))
rows.append(freeze('FloatClip-Windows-'+version+'.zip',archive.read_bytes()))
manifest={'version':version,'androidPackage':'com.floatclip.app','androidStatus':'signed' if options.android_apk else 'awaiting-original-signature','artifacts':rows}
(destination/'checksums.json').write_text(json.dumps(manifest,indent=2)+'\n')
print(json.dumps(manifest))
