from pathlib import Path
import json,hashlib,shutil,re,xml.etree.ElementTree as E

counts={k:0 for k in ['tests','failures','errors','skipped']}
for p in Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    root=E.parse(p).getroot()
    for k in counts:counts[k]+=int(root.attrib.get(k,0))
assert counts==dict(tests=86,failures=0,errors=0,skipped=0),counts
lint=Path('app/build/reports/lint-results-debug.txt').read_text()
summary=re.findall(r'(\d+) errors?, (\d+) warnings?',lint)[-1]
assert int(summary[0])==0,summary
source=Path('app/build/outputs/apk/debug/app-debug.apk')
meta=json.loads(source.with_name('output-metadata.json').read_text())
assert meta['applicationId']=='com.floatclip.probe'
assert meta['elements'][0]['versionCode']==33 and meta['elements'][0]['versionName']=='0.7.9'
target=Path('analysis/deliveries/FloatClip-Android-0.7.9-build33-recovery-candidate.apk')
target.parent.mkdir(exist_ok=True)
assert not target.exists(),'Keep immutable artifacts'
with source.open('rb') as src,target.open('xb') as dst:shutil.copyfileobj(src,dst)
sha=hashlib.sha256(target.read_bytes()).hexdigest()
assert sha==hashlib.sha256(source.read_bytes()).hexdigest()
report={
    'androidBuild':33,'version':'0.7.9','kind':'recovery-candidate',
    **counts,'lintErrors':int(summary[0]),'lintWarnings':int(summary[1]),
    'file':target.name,'bytes':target.stat().st_size,'sha256':sha,
    'signerSha256':'0368593f82ab885737b99d78c939111a1ec8ea3d88e8a67b2c3c9a4bd90c3f2c',
    'nativeWindowMorphEnabled':False,'synchronousBallRestoration':True,
    'realDeviceVerified':False,'phoneCrashStackCaptured':False,
    'publishedFeedUpdated':False,'artifactSourceImmutable':True,
}
Path('docs/evidence/RECOVERY_20261028.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report))
