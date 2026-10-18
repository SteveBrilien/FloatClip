#!/usr/bin/env python3
"""Project-local official SDK installation, verified against official SHA-512 metadata."""
import hashlib
import json
import platform
import subprocess
import tarfile
import urllib.parse
import urllib.request
from pathlib import Path

root = Path(__file__).resolve().parents[1]
dest = root / '.toolchains' / 'nearby-dotnet'
if (dest / 'dotnet').exists():
    subprocess.run([str(dest / 'dotnet'), '--version'], check=True)
    raise SystemExit(0)
rid = {'aarch64': 'linux-arm64', 'x86_64': 'linux-x64'}[platform.machine()]
with urllib.request.urlopen('https://builds.dotnet.microsoft.com/dotnet/release-metadata/10.0/releases.json', timeout=30) as r:
    metadata = json.load(r)
sdk = next(sdk for release in metadata['releases'] for sdk in release.get('sdks', [release['sdk']])
           if sdk['version'] == metadata['latest-sdk'])
item = next(f for f in sdk['files'] if f['rid'] == rid and f['name'].endswith('.tar.gz'))
url = urllib.parse.urlparse(item['url'])
if url.scheme != 'https' or url.hostname not in ('builds.dotnet.microsoft.com', 'download.visualstudio.microsoft.com', 'ci.dot.net'):
    raise RuntimeError('Unexpected SDK origin')
cache = root / '.toolchains' / 'downloads'
cache.mkdir(parents=True, exist_ok=True)
archive = cache / ('nearby-dotnet-' + sdk['version'] + '-' + rid + '.tar.gz')
digest = hashlib.sha512()
print('Downloading official SDK', sdk['version'], rid, flush=True)
try:
    with urllib.request.urlopen(item['url'], timeout=60) as r, archive.open('wb') as out:
        size = 0
        while chunk := r.read(1024 * 1024):
            size += len(chunk)
            if size > 700 * 1024 * 1024:
                raise RuntimeError('SDK download exceeded size budget')
            digest.update(chunk)
            out.write(chunk)
    if digest.hexdigest().lower() != item['hash'].lower():
        raise RuntimeError('SDK checksum mismatch')
    dest.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive) as tar:
        # Requires Python's safe extraction filter; never extract a mismatched archive.
        tar.extractall(dest, filter='data')
    (dest / 'floatclip-sdk-provenance.json').write_text(json.dumps({'version': sdk['version'], 'rid': rid, 'sha512': item['hash']}))
finally:
    archive.unlink(missing_ok=True)
subprocess.run([str(dest / 'dotnet'), '--version'], check=True)
