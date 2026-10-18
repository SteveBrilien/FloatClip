#!/usr/bin/env python3
"""Snapshot tested outputs once; never overwrite published version bytes."""
from pathlib import Path
import hashlib, json, re
root=Path(__file__).resolve().parents[1]
version=re.search(r'versionName = "([0-9.]+)-preview"',(root/'nearby-probe/build.gradle.kts').read_text()).group(1)
folder=root/'analysis/share-releases'/version
folder.mkdir(parents=True,exist_ok=True)
inputs={f'FloatClip-Share-{version}-preview.apk':root/'nearby-probe/build/outputs/apk/debug/nearby-probe-debug.apk',f'FloatClip-Share-Windows-{version}.zip':root/f'analysis/share-p1/FloatClip-Share-Windows-{version}.zip'}
rows=[]
for name,source in inputs.items():
    data=source.read_bytes(); target=folder/name
    if target.exists():
        if target.read_bytes()!=data: raise SystemExit('Immutable release conflict: '+name+'; bump version before republishing')
    else:
        with target.open('xb') as output: output.write(data)
    rows.append({'name':name,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()})
manifest={'version':version,'artifacts':rows}
(folder/'checksums.json').write_text(json.dumps(manifest,indent=2)+'\n')
print(json.dumps(manifest))
