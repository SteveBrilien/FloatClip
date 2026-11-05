from pathlib import Path
import xml.etree.ElementTree as ET
import json,hashlib,shutil,subprocess
metadata=json.loads(Path('app/build/outputs/apk/debug/output-metadata.json').read_text())['elements'][0]
assert metadata['versionCode']==42 and metadata['versionName']=='0.7.9'
tests=failures=errors=0
for file in Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
 root=ET.parse(file).getroot()
 tests+=int(root.attrib.get('tests',0));failures+=int(root.attrib.get('failures',0));errors+=int(root.attrib.get('errors',0))
assert tests>=118 and failures==errors==0,(tests,failures,errors)
lint=Path('app/build/reports/lint-results-debug.txt').read_text()
assert '0 errors' in lint,lint[-300:]
source=Path('app/build/outputs/apk/debug/app-debug.apk')
target=Path('analysis/deliveries/FloatClip-Android-0.7.9-build42-final.apk')
assert not target.exists(),'Keep immutable candidates'
target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
report={'version':'0.7.9','build':42,'tests':tests,'failures':failures,'errors':errors,
        'apk':target.name,'bytes':target.stat().st_size,'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),
        'sourceCommit':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
        'nativeDeviceVerifiedThisRound':False}
out=Path('analysis/build42');out.mkdir(parents=True,exist_ok=True)
(out/'android-verification.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report))
