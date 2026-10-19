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
notes='0.6.0 三端整合：沿用当前共享版包名和固定签名，Share 0.4.0 可覆盖升级；加入原版剪贴板、分类、置顶、备份、悬浮球及无障碍粘贴。原版 FloatClip 可并存，旧记录需通过其便携加密备份手动恢复，不会跨应用自动读取。Android/Windows/iPad 共用共享界面与协议；剪贴板按来源设备分区，支持搜索、复制、合并导入导出和再次发送。Windows 共享需要 WebView2；蓝牙与后台自动同步未发布。'
path=root/'updates/share-preview.json';prior=json.loads(path.read_text())
feed={'schema':1,'channel':'share-preview','version':version,'notes':notes,'releaseTrain':'2026.10.20-unified','designRevision':'2026.10-blue-2','web':{'version':version,'url':'https://clip.wmy-cloud.cn','protocol':'web-v1','authentication':'account','nativeInterop':True}}
for platform,url,name in [('android',args.android_url,'FloatClip-'+version+'.apk'),('windows',args.windows_url,'FloatClip-Windows-'+version+'.zip')]:
 u=urlparse(url)
 if u.scheme!='https' or u.hostname not in ['mcp.wmy-cloud.cn','github.com'] or u.username or u.password or u.port not in [None,443] or parse_qs(u.query).get('exp') not in [None,['0']]:raise SystemExit('Invalid persistent download URL')
 row=artifacts[name];feed[platform]={'version':version,'build':build,'url':url,'sha256':row['sha256'],'bytes':row['bytes'],'status':'available'}
 if platform=='android':feed[platform].update(package=identity['package'],signerSha256=identity['signerSha256'])
 if prior[platform]['build']>build or prior[platform]['build']==build and prior[platform]['sha256']!=row['sha256']:raise SystemExit('Increase build before changing published installer bytes')
path.write_text(json.dumps(feed,ensure_ascii=False,indent=2)+'\n')
p=root/'web/public/release.json';r=json.loads(p.read_text());r['nativeHost']['androidPackage']=identity['package'];r['nativeHost']['androidSignerSha256']=identity['signerSha256'];r['downloads']={s:feed[s] for s in ['android','windows']};r['distribution']={s:'available' for s in ['android','windows','web']};p.write_text(json.dumps(r,ensure_ascii=False,indent=2)+'\n')
print('Updated native installer and PWA release metadata: '+version)
