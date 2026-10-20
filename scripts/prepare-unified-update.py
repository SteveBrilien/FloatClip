"""Publish matching update metadata after immutable clients have download URLs."""
import argparse,json,re
from pathlib import Path
from urllib.parse import urlparse,parse_qs
root=Path(__file__).resolve().parents[1]
a=argparse.ArgumentParser();a.add_argument('--android-url',required=True);a.add_argument('--windows-url',required=True);args=a.parse_args()
gradle=(root/'app/build.gradle.kts').read_text();version=re.search(r'versionName = "([0-9.]+)"',gradle).group(1);build=int(re.search(r'versionCode = (\d+)',gradle).group(1))
identity=json.loads((root/'updates/android-identity.json').read_text())
checks=json.loads((root/'analysis/unified-releases'/version/'checksums.json').read_text());artifacts={r['name']:r for r in checks['artifacts']}
assert checks['androidStatus']=='signed' and checks['androidSignerSha256']==identity['signerSha256']
notes='0.7.1 测试版：修复 Android 复制远端条目后被重复收录为本机内容；正文垂直居中，设备来源收为角落图标；实时搜索、左滑置顶/删除与撤销、按压反馈、24px 图标与深浅色。Android/Windows 直接进入本机剪贴板，本机及原生局域网无需网页登录。iPad/跨网入口保留认证与原生数据桥。Windows 支持托盘与 Win+Shift+V。沿用当前 Share 包名和签名，可覆盖升级；原版独立应用仍可保留。后台持续同步、自动重连及 Android11 全天候收录尚未完成真机验收。'
path=root/'updates/share-preview.json';prior=json.loads(path.read_text())
feed={'schema':1,'channel':'share-preview','version':version,'notes':notes,'releaseTrain':'2026.10.20-clipboard-candidate','designRevision':'2026.10-clipboard-3','web':{'version':version,'url':'https://clip.wmy-cloud.cn','protocol':'web-v1','authentication':'account','nativeInterop':True}}
for platform,url,name in [('android',args.android_url,'FloatClip-'+version+'.apk'),('windows',args.windows_url,'FloatClip-Windows-'+version+'.zip')]:
 u=urlparse(url)
 if u.scheme!='https' or u.hostname not in ['mcp.wmy-cloud.cn','github.com'] or u.username or u.password or u.port not in [None,443] or parse_qs(u.query).get('exp') not in [None,['0']]:raise SystemExit('Invalid persistent download URL')
 row=artifacts[name];feed[platform]={'version':version,'build':build,'url':url,'sha256':row['sha256'],'bytes':row['bytes'],'status':'available'}
 if platform=='android':feed[platform].update(package=identity['package'],signerSha256=identity['signerSha256'])
 if prior[platform]['build']>build or prior[platform]['build']==build and prior[platform]['sha256']!=row['sha256']:raise SystemExit('Increase build before changing published installer bytes')
path.write_text(json.dumps(feed,ensure_ascii=False,indent=2)+'\n')
p=root/'web/public/release.json';r=json.loads(p.read_text());r['versions']={s:version for s in ['android','windows','web']};r['interoperability']={'nativeLAN':['android','windows'],'nodeWeb':['android-node-entry','windows-node-entry','ipad-pwa'],'automaticClipboardSync':'pending'};r['pending']=sorted(set(r.get('pending',[])+['android11-background-capture-acceptance','continuous-clipboard-handoff','cross-transport-device-identity']));r['releaseTrain']=feed['releaseTrain'];r['designRevision']=feed['designRevision'];r['channel']='candidate';r['deviceAcceptance']={s:'pending' for s in ['android11','windows10','windows11','ipad16']};r['nativeHost']['androidLauncher']='native-clipboard';r['nativeHost']['windowsLauncher']='native-clipboard';r['nativeHost']['windowsRuntime']='WebView2 only for the optional node route';r['nativeHost']['androidPackage']=identity['package'];r['nativeHost']['androidSignerSha256']=identity['signerSha256'];r['downloads']={s:feed[s] for s in ['android','windows']};r['distribution']={s:'available' for s in ['android','windows','web']};p.write_text(json.dumps(r,ensure_ascii=False,indent=2)+'\n')
print('Updated native installer and PWA release metadata: '+version)
