#!/usr/bin/env python3
"""Publish metadata only after immutable APK/ZIP snapshots and persistent URLs exist."""
import argparse, json, re
from pathlib import Path
from urllib.parse import urlparse, parse_qs
root=Path(__file__).resolve().parents[1]
args=argparse.ArgumentParser(); args.add_argument('--android-url',required=True); args.add_argument('--windows-url',required=True); args.add_argument('--notes',required=True); options=args.parse_args()
gradle=(root/'nearby-probe/build.gradle.kts').read_text()
version=re.search(r'versionName = "([0-9.]+)-preview"',gradle).group(1)
build=int(re.search(r'versionCode = (\d+)',gradle).group(1))
checks=json.loads((root/'analysis/share-releases'/version/'checksums.json').read_text())
artifacts={p['name']:p for p in checks['artifacts']}
result={'schema':1,'channel':'share-preview','version':version,'notes':options.notes[:1200]}
for platform,url,name in [('android',options.android_url,f'FloatClip-Share-{version}-preview.apk'),('windows',options.windows_url,f'FloatClip-Share-Windows-{version}.zip')]:
    parsed=urlparse(url)
    if parsed.scheme!='https' or parsed.hostname not in ('github.com','mcp.wmy-cloud.cn') or parsed.username or parsed.password or parsed.port not in (None,443) or parse_qs(parsed.query).get('exp') not in (None,['0']):
        raise SystemExit('Expected persistent HTTPS platform URL: '+platform)
    artifact=artifacts[name]
    result[platform]={'build':build,'url':url,'sha256':artifact['sha256'],'bytes':artifact['bytes']}
path=root/'updates/share-preview.json'
if path.exists():
    prior=json.loads(path.read_text())
    for platform in ('android','windows'):
        if prior[platform]['build']>build or (prior[platform]['build']==build and prior[platform]['sha256']!=result[platform]['sha256']):
            raise SystemExit('Update build must increase when published bytes change')
path.parent.mkdir(parents=True,exist_ok=True); path.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print(str(path.relative_to(root)))
