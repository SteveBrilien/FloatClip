"""Prepare the new signed update payload from two verified, immutable release manifests."""
import argparse,json,re
from pathlib import Path
from urllib.parse import urlsplit
root=Path(__file__).resolve().parents[1]
a=argparse.ArgumentParser()
a.add_argument('--android-manifest',type=Path,default=root/'updates/android-candidate.json')
a.add_argument('--windows-manifest',type=Path,default=root/'updates/native-candidate.json')
a.add_argument('--output',type=Path,default=root/'updates/share-preview-v3.json')
args=a.parse_args()
android=json.loads(args.android_manifest.read_text())
windows=json.loads(args.windows_manifest.read_text())
identity=json.loads((root/'updates/android-identity.json').read_text())
version=android['version'];build=android['build']
assert re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+',version)
assert isinstance(build,int) and build>=57
assert windows['windows']['bytes']>0
def valid(row):
 u=urlsplit(row['url'])
 assert u.scheme=='https' and u.hostname in ('mcp.wmy-cloud.cn','github.com') and u.port in (None,443) and not u.username
 assert re.fullmatch('[0-9a-f]{64}',row['sha256'])
 assert isinstance(row['bytes'],int) and 0<row['bytes']<=100*1024*1024
valid(android['android']);valid(windows['windows'])
old=json.loads((root/'updates/share-preview.json').read_text())
old.update(version=version,notes=(
 'Android '+version+' build'+str(build)+'：统一应用内词条长按与全文查看交互；'
 '悬浮全文页复制增加热区反馈、成功高亮与操作栏对齐；'
 '新增 Orange Pi v3 RSA 签名更新源和旧 v2 兼容读取。'
 '请使用原安装签名的 APK 覆盖安装，勿卸载以免损失历史数据。'
),releaseTrain='2026.11.14-native-v3',designRevision='2026.11-floatclip-ui-4')
old['android']={
 'version':version,'build':build,'url':android['android']['url'],
 'sha256':android['android']['sha256'],'bytes':android['android']['bytes'],
 'status':'available','package':identity['package'],
 'signerSha256':identity['signerSha256'],
}
old['windows']={
 'version':windows['version'],'build':windows['build'],
 'url':windows['windows']['url'],'sha256':windows['windows']['sha256'],
 'bytes':windows['windows']['bytes'],'status':'available',
}
assert old['schema']==1 and old['channel']=='share-preview'
args.output.write_text(json.dumps(old,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'version':version,'androidBuild':build,'windowsBuild':windows['build'],'output':str(args.output)}))
