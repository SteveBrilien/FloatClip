"""Snapshot tested unified clients. Published version bytes are immutable."""
from pathlib import Path
import hashlib,json,re,zipfile,argparse,subprocess,os
root=Path(__file__).resolve().parents[1]
version=re.search(r'versionName = "([0-9.]+)"',(root/'app/build.gradle.kts').read_text()).group(1)
identity=json.loads((root/'updates/android-identity.json').read_text())
destination=root/'analysis/unified-releases'/version;destination.mkdir(parents=True,exist_ok=True)
def freeze(name,data):
 p=destination/name
 if p.exists():
  if p.read_bytes()!=data:raise SystemExit('Immutable version conflict: '+name)
 else:p.write_bytes(data)
 return {'name':name,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}
rows=[]
parser=argparse.ArgumentParser();parser.add_argument('--windows-dir',default='analysis/unified-windows');parser.add_argument('--android-apk');parser.add_argument('--apksigner');parser.add_argument('--aapt');options=parser.parse_args()
if options.android_apk:
 if not options.apksigner or not options.aapt:raise SystemExit('--apksigner and --aapt are required for Android publication')
 verifier=[str(Path(os.environ['JAVA_HOME'])/'bin/java'),'-Xmx128m','-jar',options.apksigner] if options.apksigner.endswith('.jar') else [options.apksigner]
 result=subprocess.run(verifier+['verify','--print-certs',str(root/options.android_apk)],check=True,capture_output=True,text=True)
 expected=identity['signerSha256']
 signers=re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)',result.stdout)
 if signers!=[expected]:raise SystemExit('Current Share upgrade certificate is required; keep the existing app and data')
 badging=subprocess.run([options.aapt,'dump','badging',str(root/options.android_apk)],check=True,capture_output=True,text=True).stdout
 declared=re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'",badging)
 build=re.search(r'versionCode = (\d+)',(root/'app/build.gradle.kts').read_text()).group(1)
 if not declared or declared.groups()!=(identity['package'],build,version):raise SystemExit('APK package or version does not match the update identity')
 if "launchable-activity: name='com.floatclip.app.UnifiedActivity'" not in badging:raise SystemExit('Unified launcher missing from APK')
 manifest_args=['--file','AndroidManifest.xml'] if Path(options.aapt).name=='aapt2' else ['AndroidManifest.xml']
 manifest=subprocess.run([options.aapt,'dump','xmltree',str(root/options.android_apk)]+manifest_args,check=True,capture_output=True,text=True).stdout
 if identity['legacyClipboardPackage']+'.permission.INTERNAL' in manifest or identity['package']+'.permission.INTERNAL' not in manifest:raise SystemExit('Permission conflicts with the original clipboard app')
 rows.append(freeze('FloatClip-'+version+'.apk',(root/options.android_apk).read_bytes()))
source=root/options.windows_dir
if not (source/'FloatClip.exe').is_file():raise SystemExit('Windows executable missing; build before packaging')
archive=root/('analysis/unified-'+version+'-windows.zip')
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=7) as z:
 for p in sorted(source.rglob('*')):
  if p.is_file() and p.suffix.lower() not in ['.pdb','.xml']:
   info=zipfile.ZipInfo('FloatClip/'+str(p.relative_to(source)),(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,p.read_bytes())
 info=zipfile.ZipInfo('FloatClip/README.txt',(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
 z.writestr(info,('FloatClip '+version+'\n\nWindows 10/11 x64：完整解压后运行 FloatClip.exe。\n本机剪贴板和局域网设备不需要网页账号或 WebView2。连接 iPad / 经小主机共享的可选入口需要 WebView2 与网页认证。\n剪贴板为主界面，左滑或右键可置顶、删除并撤销；Win + Shift + V 调出。正常关闭窗口后在托盘运行，可从托盘退出。\n本机剪贴板由当前 Windows 用户加密保存。\n直接运行 FloatClip.exe 打开本机剪贴板；--web 打开可选跨网入口。\n局域网设备：FloatClip.exe --legacy-lan\n\nPWA：https://clip.wmy-cloud.cn\n').encode('utf-8'))
rows.append(freeze('FloatClip-Windows-'+version+'.zip',archive.read_bytes()))
manifest={'version':version,'androidPackage':identity['package'],'androidSignerSha256':identity['signerSha256'],'androidStatus':'signed' if options.android_apk else 'not-packaged','artifacts':rows}
(destination/'checksums.json').write_text(json.dumps(manifest,indent=2)+'\n')
print(json.dumps(manifest))
