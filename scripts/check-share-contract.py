"""Keep the three released clients on one declared product and colour contract."""
import json
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
web = root / 'web'
tokens = json.loads((web / 'shared/design-tokens.json').read_text())
release = json.loads((web / 'public/release.json').read_text())
feed = json.loads((root / 'updates/share-preview.json').read_text())
package = json.loads((web / 'package.json').read_text())
platforms = {'android', 'windows', 'web'}
assert set(release['versions']) == platforms
assert release['versions']['android'] == release['versions']['windows'] == feed['version']
assert release['versions']['web'] == package['version']
assert tokens['revision'] == release['designRevision']
assert tokens['navigation'] == ['共享', '设备', '设置']
for feature, implementations in release['featureContract'].items():
    assert set(implementations) == platforms, f'{feature}: every platform needs an implementation status'
    assert set(implementations.values()) == {'implemented'}, f'{feature}: common features must ship together'

def colours(source):
    values = set()
    for value in re.findall(r'#[0-9a-fA-F]{3,8}\b', source):
        value = value.upper()
        if len(value) == 4:
            value = '#' + ''.join(c*2 for c in value[1:])
        values.add(value)
    if 'Color.WHITE' in source:
        values.add('#FFFFFF')
    return values

required = {tokens['primary']}
for mode in ('light', 'dark'):
    required.update(tokens[mode].values())
for name, path in {
    'android': root / 'nearby-probe/src/main/java/com/floatclip/probe/ShareDesign.kt',
    'windows': root / 'windows/FloatClip.Desktop/App.cs',
    'web': web / 'app/globals.css',
}.items():
    missing = required - colours(path.read_text())
    assert not missing, f'{name}: inconsistent theme tokens {sorted(missing)}'
print('Share product contract: three platform versions, common features and light/dark colours match')
