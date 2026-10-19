"""Validate the released unified hosts, common UI, storage and update contract."""
import json,re
from pathlib import Path
root=Path(__file__).resolve().parents[1];web=root/'web'
tokens=json.loads((web/'shared/design-tokens.json').read_text());release=json.loads((web/'public/release.json').read_text());feed=json.loads((root/'updates/share-preview.json').read_text());package=json.loads((web/'package.json').read_text())
platforms={'android','windows','web'}
assert set(release['versions'])==platforms
assert len(set(release['versions'].values()))==1,'Unified clients must share a release version'
version=release['versions']['web']
assert version==package['version']==feed['version']
assert tokens['revision']==release['designRevision']
assert tokens['navigation']==['剪贴板','共享','设备','设置']
android=(root/'app/build.gradle.kts').read_text();windows=(root/'windows/FloatClip.Desktop/FloatClip.Desktop.csproj').read_text()
assert re.search(r'versionName = "([0-9.]+)"',android).group(1)==version
assert re.search(r'<Version>([^<]+)</Version>',windows).group(1)==version
assert re.search(r"VERSION = '([^']+)'",(web/'lib/version.ts').read_text()).group(1)==version
assert re.search(r"VERSION = '([^']+)'",(web/'server/app.py').read_text()).group(1)==version
assert re.search(r'Version="([0-9.]+)"',(root/'windows/FloatClip.Desktop/UnifiedWindow.cs').read_text()).group(1)==version
assert release['nativeHost']['androidPackage']=='com.floatclip.app'
build=int(re.search(r'versionCode = (\d+)',android).group(1))
assert feed['windows']['build']==build
for platform in ['android','windows']:
 status=release['distribution'][platform]
 if status=='available':
  assert feed[platform]['build']==build and feed[platform]['version']==version
  assert release['downloads'][platform]==feed[platform]
  assert re.fullmatch('[0-9a-f]{64}',feed[platform]['sha256']) and feed[platform]['bytes']>0
 else:
  assert platform=='android' and status=='awaiting-original-signature'
  assert platform not in release['downloads'],'Unsigned Android candidate must not be offered as an upgrade'
  assert feed[platform]['build']<build and feed[platform]['package']=='com.floatclip.probe'
assert feed['web']['nativeInterop'] is True
for feature,implementations in release['featureContract'].items():
 assert set(implementations)==platforms,f'{feature}: missing platform status'
 assert set(implementations.values())=={'implemented'},f'{feature}: common features must ship together'
for feature in ['clipboard-library','source-device-boards','search-pin-category','clipboard-backup','web-v1-interoperability']:
 assert feature in release['featureContract'],feature
# The main host UI and transport are the same code on all clients. Old native LAN
# screens intentionally remain a distinct compatibility mode.
for file in ['app/src/main/java/com/floatclip/app/UnifiedActivity.kt','windows/FloatClip.Desktop/UnifiedWindow.cs']:
 assert 'https://clip.wmy-cloud.cn' in (root/file).read_text()
assert '.UnifiedActivity' in (root/'app/src/main/AndroidManifest.xml').read_text()
assert 'new UnifiedWindow()' in (root/'windows/FloatClip.Desktop/App.cs').read_text()
assert 'LocalVaultCrypto' in (root/'app/src/main/java/com/floatclip/app/clipboard/ClipStore.kt').read_text()
assert 'DataProtectionScope.CurrentUser' in (root/'windows/FloatClip.Desktop/ClipboardVault.cs').read_text()
assert "indexedDB.open('floatclip-clipboard-v1'" in (web/'lib/vault.ts').read_text()
colours={c.upper() for c in re.findall(r'#[0-9a-fA-F]{3,8}\b',(web/'app/globals.css').read_text())}
colours|={'#'+''.join(c*2 for c in v[1:]) for v in list(colours) if len(v)==4}
required={tokens['primary']}
for mode in ['light','dark']:required.update(tokens[mode].values())
assert not required-colours,'Shared UI colours do not match the design contract'
lock=json.loads((web/'package-lock.json').read_text())
assert not any(p.get('link') or 'sites/floatclip' in p.get('resolved','') for p in lock['packages'].values()),'Dependency lock contains a workstation path'
print('FloatClip '+version+': versions, hosts, clipboard features, provenance, storage and theme contract match')
