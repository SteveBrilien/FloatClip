"""Generate a complete public shell cache without caching authentication or content."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
dist = root / 'dist'
assets = ['/', '/index.html', '/manifest.webmanifest', '/favicon.svg', '/icons/icon-192.png', '/icons/icon-512.png', '/icons/apple-touch-icon.png']
assets += ['/' + str(p.relative_to(dist)) for p in sorted((dist / 'assets').glob('*')) if p.is_file()]
revision = hashlib.sha256(b''.join((dist / p.lstrip('/')).read_bytes() for p in assets if p != '/')).hexdigest()[:12]
worker = """const CACHE='floatclip-public-REVISION';
const ASSETS=ASSET_LIST;
self.addEventListener('install',e=>e.waitUntil(caches.open(CACHE).then(c=>c.addAll(ASSETS))));
self.addEventListener('activate',e=>e.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(k=>k.startsWith('floatclip-')&&k!==CACHE).map(k=>caches.delete(k)))).then(()=>self.clients.claim())));
self.addEventListener('message',e=>{if(e.data?.type==='SKIP_WAITING')self.skipWaiting();});
self.addEventListener('fetch',e=>{const u=new URL(e.request.url);if(e.request.method!=='GET'||u.origin!==self.location.origin||u.pathname.startsWith('/api/')||u.pathname==='/_health')return;
 if(e.request.mode==='navigate'){e.respondWith(fetch(e.request).catch(()=>caches.match('/index.html')));return;}
 if(ASSETS.includes(u.pathname))e.respondWith(caches.match(e.request).then(c=>c||fetch(e.request)));
});
""".replace('REVISION', revision).replace('ASSET_LIST', json.dumps(assets))
(dist / 'sw.js').write_text(worker)
print('Public shell revision:', revision, 'resources:', len(assets))
