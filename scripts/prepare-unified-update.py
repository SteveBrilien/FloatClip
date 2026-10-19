"""Publish matching update metadata after immutable clients have download URLs."""
import argparse,json,re
from pathlib import Path
from urllib.parse import urlparse,parse_qs
root=Path(__file__).resolve().parents[1]
a=argparse.ArgumentParser();a.add_argument('--android-url');a.add_argument('--windows-url',required=True);args=a.parse_args()
gradle=(root/'app/build.gradle.kts').read_text();version=re.search(r'versionName = "([0-9.]+)"',gradle).group(1);build=int(re.search(r'versionCode = (\d+)',gradle).group(1))
checks=json.loads((root/'analysis/unified-releases'/version/'checksums.json').read_text());artifacts={r['name']:r for r in checks['artifacts']}
notes='0.6.0 三端整合：Android 原版加密剪贴板、分类、置顶、备份与悬浮球保留；Android/Windows/iPad 共用共享界面与协议；剪贴板按来源设备分组，支持搜索、复制、分类、置顶、合并导入导出与再次发送。Android 安装包升级 com.floatclip.app，旧 Share 为独立包，请保留原版后覆盖升级；Windows 共享界面需要 WebView2。蓝牙与后台自动同步尚未发布。'
path=root/'updates/share-preview.json';prior=json.loads(path.read_text())
if not args.android_url:notes+=' Android 0.6.0 已完成构建与自动检查，等待原版签名，暂未发布安装包；手机更新入口仍保留旧 Share 0.4.0，不要卸载原版或清除数据。'
feed={'schema':1,'channel':'share-preview','version':version,'notes':notes,'releaseTrain':'2026.10.20-unified','designRevision':'2026.10-blue-2','web':{'version':version,'url':'https://clip.wmy-cloud.cn','protocol':'web-v1','authentication':'account','nativeInterop':True}}
if not args.android_url:feed['android']={**prior['android'],'version':prior['android'].get('version',prior['version']),'status':'awaiting-original-signature','package':prior['android'].get('package','com.floatclip.probe')}
for platform,url,name in [('android',args.android_url,'FloatClip-'+version+'.apk'),('windows',args.windows_url,'FloatClip-Windows-'+version+'.zip')]:
 if not url:continue
 u=urlparse(url)
 if u.scheme!='https' or u.hostname not in ['mcp.wmy-cloud.cn','github.com'] or u.username or u.password or u.port not in [None,443] or parse_qs(u.query).get('exp') not in [None,['0']]:raise SystemExit('Invalid persistent download URL')
 row=artifacts[name];feed[platform]={'version':version,'build':build,'url':url,'sha256':row['sha256'],'bytes':row['bytes'],'status':'available'}
 if platform=='android':feed[platform]['package']='com.floatclip.app'
for platform in ['android','windows']:
 if platform=='android' and not args.android_url:continue
 if prior[platform]['build']>build or prior[platform]['build']==build and prior[platform]['sha256']!=feed[platform]['sha256']:raise SystemExit('Increase build before changing published installer bytes')
path.write_text(json.dumps(feed,ensure_ascii=False,indent=2)+'\n')
p=root/'web/public/release.json';r=json.loads(p.read_text());r['downloads']={s:feed[s] for s in ['android','windows'] if feed[s]['status']=='available'};r['distribution']={'android':'available' if args.android_url else 'awaiting-original-signature','windows':'available','web':'available'};p.write_text(json.dumps(r,ensure_ascii=False,indent=2)+'\n')
print('Updated native installer and PWA release metadata: '+version)
