"""Restore the pinned offline emoji font. The installed app never downloads UI assets."""
import hashlib,json,urllib.request
from pathlib import Path
root=Path(__file__).resolve().parents[1];folder=root/'windows/FloatClip.Next/UI'
manifest=json.loads((folder/'asset-lock.json').read_text(encoding='utf-8'))
for item in manifest['downloads']:
 target=folder/item['file']
 if target.is_file() and hashlib.sha256(target.read_bytes()).hexdigest()==item['sha256']:continue
 with urllib.request.urlopen(item['url'],timeout=60) as response: data=response.read(32*1024*1024)
 assert hashlib.sha256(data).hexdigest()==item['sha256'],'Asset hash mismatch: '+item['file']
 target.write_bytes(data)
print('PASS: pinned desktop assets available for offline packaging')
