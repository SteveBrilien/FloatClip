"""Validate published artifacts separately from the native development candidates."""
import json,re
from pathlib import Path
root=Path(__file__).resolve().parents[1];web=root/'web'
def read(path): return path.read_text(encoding='utf-8')
tokens=json.loads((web/'shared/design-tokens.json').read_text(encoding='utf-8'));release=json.loads((web/'public/release.json').read_text(encoding='utf-8'));feed=json.loads((root/'updates/share-preview.json').read_text(encoding='utf-8'));package=json.loads((web/'package.json').read_text(encoding='utf-8'))
platforms={'android','windows','web'}
assert set(release['versions'])==platforms
version=release['versions']['web']
assert version==package['version']==feed['web']['version'],'Web metadata must match the actual Web build'
native_version=feed['version']
assert release['versions']['android']==release['versions']['windows']==native_version,'Native publications must share their release version'
assert tokens['revision']==release['designRevision']
assert tokens['navigation']==['剪贴板']
android=(root/'app/build.gradle.kts').read_text(encoding='utf-8');windows=(root/'windows/FloatClip.Desktop/FloatClip.Desktop.csproj').read_text(encoding='utf-8')
development=json.loads(read(root/'updates/development-versions.json'))
assert development['channel']=='development' and set(development['platforms'])=={'android','windows'}
native_versions={'android':re.search(r'versionName = "([0-9.]+)"',android).group(1),
                 'windows':re.search(r'<Version>([^<]+)</Version>',windows).group(1)}
def semver(value):
 assert re.fullmatch(r'\d+\.\d+\.\d+',value),value
 return tuple(map(int,value.split('.')))
for platform,candidate in development['platforms'].items():
 assert candidate['version']==native_versions[platform],f'{platform}: development version differs from source'
 assert semver(candidate['version'])>=semver(release['versions'][platform]),f'{platform}: source predates published artifacts'
 assert (root/candidate['review']).is_file(),f'{platform}: missing candidate validation notes'
windows_updates=read(root/'windows/FloatClip.Desktop/ShareUpdates.cs')
assert re.search(r'Version="([0-9.]+)"',windows_updates).group(1)==native_versions['windows']
assert int(re.search(r'Build=(\d+)',windows_updates).group(1))==development['platforms']['windows']['build']
assert re.search(r"VERSION = '([^']+)'",(web/'lib/version.ts').read_text(encoding='utf-8')).group(1)==version
assert re.search(r"VERSION = '([^']+)'",(web/'server/app.py').read_text(encoding='utf-8')).group(1)==version
identity=json.loads((root/'updates/android-identity.json').read_text(encoding='utf-8'))
assert identity['package']=='com.floatclip.probe'
assert identity['signerSha256']=='0368593f82ab885737b99d78c939111a1ec8ea3d88e8a67b2c3c9a4bd90c3f2c'
assert re.search(r'applicationId = "([^"]+)"',android).group(1)==identity['package']
assert '.toolchains/nearby-probe/debug.keystore' in android
assert '${applicationId}.permission.INTERNAL' in (root/'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
assert release['nativeHost']['androidPackage']==identity['package']
assert release['nativeHost']['androidSignerSha256']==identity['signerSha256']
build=int(re.search(r'versionCode = (\d+)',android).group(1))
assert build==development['platforms']['android']['build']
for platform in ['android','windows']:
 assert release['distribution'][platform]=='available'
 assert feed[platform]['build']>0 and feed[platform]['version']==native_version
 assert development['platforms'][platform]['build']>=feed[platform]['build']
 assert release['downloads'][platform]==feed[platform]
 assert re.fullmatch('[0-9a-f]{64}',feed[platform]['sha256']) and feed[platform]['bytes']>0
assert feed['android']['package']==identity['package'] and feed['android']['signerSha256']==identity['signerSha256']
assert feed['web']['nativeInterop'] is True
for feature,implementations in release['featureContract'].items():
 assert set(implementations)==platforms,f'{feature}: missing platform status'
 assert set(implementations.values())=={'implemented'},f'{feature}: common features must ship together'
for feature in ['clipboard-library','source-device-boards','search-pin-category','clipboard-backup','web-v1-interoperability']:
 assert feature in release['featureContract'],feature
# Native launchers are local-first; the optional node route retains the shared web bridge.
for file in ['app/src/main/java/com/floatclip/app/NodeActivity.kt','windows/FloatClip.Desktop/CrossNetworkPortal.cs']:
 assert 'https://clip.wmy-cloud.cn' in (root/file).read_text(encoding='utf-8')
assert '.UnifiedActivity' in (root/'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
assert 'new ClipboardHost()' in read(root/'windows/FloatClip.Desktop/App.cs')
assert not (root/'windows/FloatClip.Desktop/UnifiedWindow.cs').exists(),'Duplicate desktop shell must not return'
assert 'LocalVaultCrypto' in (root/'app/src/main/java/com/floatclip/app/clipboard/ClipStore.kt').read_text(encoding='utf-8')
assert 'DataProtectionScope.CurrentUser' in (root/'windows/FloatClip.Desktop/ClipboardVault.cs').read_text(encoding='utf-8')
assert "indexedDB.open('floatclip-clipboard-v1'" in (web/'lib/vault.ts').read_text(encoding='utf-8')
colours={c.upper() for c in re.findall(r'#[0-9a-fA-F]{3,8}\b',(web/'app/globals.css').read_text(encoding='utf-8'))}
colours|={'#'+''.join(c*2 for c in v[1:]) for v in list(colours) if len(v)==4}
required={tokens['primary']}
for mode in ['light','dark']:required.update(tokens[mode].values())
assert not required-colours,'Shared UI colours do not match the design contract'
lock=json.loads((web/'package-lock.json').read_text(encoding='utf-8'))
assert not any(p.get('link') or 'sites/floatclip' in p.get('resolved','') for p in lock['packages'].values()),'Dependency lock contains a workstation path'
print('FloatClip published Web '+version+' / native '+native_version+'; native candidates '+str(native_versions)+': versions, hosts, provenance, storage and theme contract match')
