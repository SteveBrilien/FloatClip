"""Exact clip-only Caddy route and Docker-private socket bridge. Run as root."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request

CADDY = Path('/home/wechat/WeChat-Assistant_Apple/server/Caddyfile')
CONTAINER = 'wechat-assistant_apple-caddy-1'
SOCKET = Path('/etc/systemd/system/floatclip-web-bridge.socket')
SERVICE = Path('/etc/systemd/system/floatclip-web-bridge.service')
SOCKET_TEXT = '''[Unit]
Description=FloatClip Docker-private ingress socket
[Socket]
ListenStream=172.18.0.1:18789
NoDelay=true
[Install]
WantedBy=sockets.target
'''
SERVICE_TEXT = '''[Unit]
Description=FloatClip Docker-private socket proxy
Requires=floatclip-web-bridge.socket
After=floatclip-web-bridge.socket
[Service]
User=DHSP
ExecStart=/usr/lib/systemd/systemd-socket-proxyd 127.0.0.1:18788
NoNewPrivileges=yes
PrivateTmp=yes
ProtectSystem=strict
ProtectHome=yes
MemoryMax=64M
'''
BLOCK = '''
# BEGIN FLOATCLIP CLIP MANAGED
clip.wmy-cloud.cn {
    encode zstd gzip
    header Strict-Transport-Security "max-age=31536000"
    request_body {
        max_size 80KB
    }
    reverse_proxy 172.18.0.1:18789 {
        header_up X-Real-IP {remote_host}
    }
}
# END FLOATCLIP CLIP MANAGED
'''
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def sha(value):
    return hashlib.sha256(value).hexdigest()


def run(args, data=None):
    r = subprocess.run(args, input=data, capture_output=True, timeout=25)
    if r.returncode:
        raise RuntimeError('Command failed: ' + ' '.join(args[:4]) + ': ' + r.stderr.decode(errors='replace')[-500:])
    return r.stdout


def health(port):
    req = urllib.request.Request(f'http://127.0.0.1:{port}/_health', headers={'Host':'clip.wmy-cloud.cn'})
    with OPENER.open(req, timeout=4) as r:
        value = json.load(r)
    if value.get('product') != 'FloatClip' or value.get('ok') is not True:
        raise RuntimeError('Unexpected node response')
    return value


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--apply', action='store_true')
    p.add_argument('--expected-sha')
    args = p.parse_args()
    if os.geteuid() != 0:
        raise SystemExit('Root-owned fixed deployment script required')
    if not Path('/usr/lib/systemd/systemd-socket-proxyd').is_file():
        raise SystemExit('Socket proxy executable unavailable')
    before = CADDY.read_bytes()
    status = {'caddy_sha256': sha(before), 'clip_present': b'clip.wmy-cloud.cn' in before,
              'node': health(18788), 'bridge_exists': SOCKET.exists()}
    if not args.apply:
        print(json.dumps(status))
        return
    if args.expected_sha != sha(before) or status['clip_present']:
        raise SystemExit('Caddy changed or clip already configured; inspect before applying')
    if SOCKET.exists() or SERVICE.exists():
        raise SystemExit('Bridge unit already exists; inspect before applying')
    ports = run(['ss','-H','-ltn','sport = :18789']).decode().strip()
    if ports:
        raise SystemExit('Bridge port already occupied')
    proposed = before.rstrip() + b'\n' + BLOCK.encode()
    run(['docker','exec','-i',CONTAINER,'caddy','validate','--config','/dev/stdin','--adapter','caddyfile'], proposed)
    backups = Path('/root/floatclip-route-backups')
    backups.mkdir(mode=0o700, exist_ok=True)
    backup = backups / ('Caddyfile-' + str(int(time.time())) + '-' + sha(before)[:12])
    backup.write_bytes(before)
    backup.chmod(0o600)
    activated = False
    try:
        SOCKET.write_text(SOCKET_TEXT)
        SERVICE.write_text(SERVICE_TEXT)
        run(['systemctl','daemon-reload'])
        run(['systemctl','enable','--now','floatclip-web-bridge.socket'])
        # Verify the actual Docker bridge address before publishing its route.
        req = urllib.request.Request('http://172.18.0.1:18789/_health',headers={'Host':'clip.wmy-cloud.cn'})
        with OPENER.open(req,timeout=4) as r:
            if json.load(r).get('product') != 'FloatClip':
                raise RuntimeError('Bridge verification failed')
        with CADDY.open('r+b') as f:
            fcntl.flock(f, fcntl.LOCK_EX)
            if f.read() != before:
                raise RuntimeError('Concurrent Caddy edit detected')
            f.seek(0)
            f.write(proposed)
            f.truncate()
            f.flush()
            os.fsync(f.fileno())
        activated = True
        observed = run(['docker','exec',CONTAINER,'cat','/etc/caddy/Caddyfile'])
        if sha(observed) != sha(proposed):
            raise RuntimeError('Caddy bind mount does not expose the expected inode')
        run(['docker','exec',CONTAINER,'caddy','reload','--config','/etc/caddy/Caddyfile','--adapter','caddyfile'])
        print(json.dumps({'applied':True,'caddy_sha256':sha(proposed),'node':status['node'],
                          'domain':'clip.wmy-cloud.cn','backup':str(backup)}))
    except Exception:
        if activated and CADDY.read_bytes() == proposed:
            with CADDY.open('r+b') as f:
                f.write(before)
                f.truncate()
                f.flush()
                os.fsync(f.fileno())
            run(['docker','exec',CONTAINER,'caddy','reload','--config','/etc/caddy/Caddyfile','--adapter','caddyfile'])
        run(['systemctl','disable','--now','floatclip-web-bridge.socket'])
        run(['systemctl','stop','floatclip-web-bridge.service'])
        SOCKET.unlink(missing_ok=True)
        SERVICE.unlink(missing_ok=True)
        run(['systemctl','daemon-reload'])
        raise


if __name__ == '__main__':
    main()
