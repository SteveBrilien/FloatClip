"""FloatClip node. Secrets and opaque relay packets never enter application logs."""
import asyncio
import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import time
from pathlib import Path

from aiohttp import web, WSMsgType

VERSION = '0.2.0'
COOKIE = '__Host-floatclip'
MAX_BODY = 72000
TTL = 120


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def password_hash(password, salt=None):
    salt = salt or secrets.token_bytes(16)
    return {'algorithm': 'pbkdf2-sha256', 'iterations': 600000,
            'salt': salt.hex(),
            'hash': hashlib.pbkdf2_hmac('sha256', password.encode(), salt, 600000).hex()}


def check_password(password, config):
    record = config['password']
    calculated = hashlib.pbkdf2_hmac('sha256', password.encode(), bytes.fromhex(record['salt']), record['iterations'])
    return hmac.compare_digest(calculated, bytes.fromhex(record['hash']))


class Store:
    def __init__(self, path):
        self.db = sqlite3.connect(path, isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.db.executescript('''
          PRAGMA journal_mode=WAL;
          CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY, csrf TEXT NOT NULL,
            expires REAL NOT NULL, created REAL NOT NULL);
          CREATE TABLE IF NOT EXISTS limits(key TEXT PRIMARY KEY, count INTEGER NOT NULL, expires REAL NOT NULL);
          CREATE TABLE IF NOT EXISTS rooms(code TEXT PRIMARY KEY, mode TEXT NOT NULL,
            host_hash TEXT NOT NULL, guest_hash TEXT, offer TEXT NOT NULL, answer TEXT,
            expires REAL NOT NULL);
        ''')

    def cleanup(self):
        now = time.time()
        for table in ('sessions', 'limits', 'rooms'):
            self.db.execute(f'DELETE FROM {table} WHERE expires < ?', (now,))

    def limit(self, key, maximum, interval=60):
        now = time.time()
        key = digest(f'{key}:{int(now // interval)}')
        self.db.execute('INSERT INTO limits VALUES (?,1,?) ON CONFLICT(key) DO UPDATE SET count=count+1', (key, now + interval * 2))
        count = self.db.execute('SELECT count FROM limits WHERE key=?', (key,)).fetchone()[0]
        if count > maximum:
            raise web.HTTPTooManyRequests(text='操作太频繁，请稍后重试')

    def session(self, token):
        if not re.fullmatch(r'[a-f0-9]{64}', token or ''):
            return None
        return self.db.execute('SELECT * FROM sessions WHERE token=? AND expires>?', (digest(token), time.time())).fetchone()


def create_app(config, state_path, static_path):
    origin = config['origin'].rstrip('/')
    root = Path(static_path).resolve()
    state = Path(state_path)
    state.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(state, 0o700)
    store = Store(state / 'node.sqlite3')
    os.chmod(state / 'node.sqlite3', 0o600)
    slots = {}
    sockets = {}
    pending = {}
    relay_deadlines = {}
    kdf_slots = asyncio.Semaphore(2)

    def client_ip(req):
        # Caddy always overwrites this header; the origin listens only on loopback.
        return req.headers.get('X-Real-IP', req.remote or 'local')[:80]

    def need_origin(req):
        if req.headers.get('Origin') != origin:
            raise web.HTTPForbidden(text='请求来源无效')

    def session(req):
        value = store.session(req.cookies.get(COOKIE))
        if value is None:
            raise web.HTTPUnauthorized(text='登录已过期，请重新登录')
        return value

    async def body(req, csrf=True):
        need_origin(req)
        if csrf and not hmac.compare_digest(req.headers.get('X-CSRF-Token', ''), session(req)['csrf']):
            raise web.HTTPForbidden(text='页面凭据已过期，请刷新')
        if req.content_type != 'application/json':
            raise web.HTTPUnsupportedMediaType(text='请求类型无效')
        value = await req.json()
        if not isinstance(value, dict):
            raise web.HTTPBadRequest(text='请求格式无效')
        return value

    @web.middleware
    async def safety(req, handler):
        if req.host != origin.split('://', 1)[1] and not config.get('test'):
            return web.json_response({'error': '请求地址无效'}, status=400)
        try:
            result = await handler(req)
        except web.HTTPException as e:
            result = web.json_response({'error': e.text if e.status < 500 else '服务暂不可用'}, status=e.status)
        except (ValueError, TypeError, KeyError, UnicodeError):
            result = web.json_response({'error': '请求格式无效'}, status=400)
        except Exception:
            result = web.json_response({'error': '服务暂不可用，请重试'}, status=503)
        if not isinstance(result, web.WebSocketResponse):
            result.headers.update({
                'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer',
                'X-Frame-Options': 'DENY', 'Cross-Origin-Resource-Policy': 'same-origin',
                'Permissions-Policy': 'camera=(), microphone=(), geolocation=(), clipboard-read=(self), clipboard-write=(self)',
                'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; connect-src 'self' " + origin.replace('https://', 'wss://') + "; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'",
            })
            if req.path.startswith('/api/') or result.status >= 400:
                result.headers['Cache-Control'] = 'no-store'
        return result

    app = web.Application(middlewares=[safety], client_max_size=MAX_BODY)
    app['store'] = store

    async def health(req):
        return web.json_response({'ok': True, 'product': 'FloatClip', 'version': VERSION})

    async def auth(req):
        if req.method == 'GET':
            s = session(req)
            return web.json_response({'user': config['username'], 'csrf': s['csrf'], 'expires': int(s['expires'] * 1000), 'version': VERSION})
        data = await body(req, csrf=False)
        store.cleanup()
        store.limit('login-ip:' + client_ip(req), 12, 600)
        store.limit('login-global', 120, 600)
        name, password = data.get('username'), data.get('password')
        if not isinstance(name, str) or not isinstance(password, str) or len(name) > 80 or len(password) > 256:
            raise web.HTTPUnauthorized(text='账号或密码错误')
        async with kdf_slots:
            correct = await asyncio.to_thread(check_password, password, config)
        if not correct or not hmac.compare_digest(name.encode(), config['username'].encode()):
            raise web.HTTPUnauthorized(text='账号或密码错误')
        prior = req.cookies.get(COOKIE, '')
        if prior:
            store.db.execute('DELETE FROM sessions WHERE token=?', (digest(prior),))
        token, csrf = secrets.token_hex(32), secrets.token_hex(32)
        duration = 30 * 86400 if data.get('remember', True) is True else 12 * 3600
        now = time.time()
        store.db.execute('INSERT INTO sessions VALUES (?,?,?,?)', (digest(token), csrf, now + duration, now))
        # Limit retained devices, without keeping a reusable cookie in the database.
        store.db.execute('DELETE FROM sessions WHERE token NOT IN (SELECT token FROM sessions ORDER BY created DESC LIMIT 12)')
        result = web.json_response({'user': config['username'], 'csrf': csrf, 'expires': int((now + duration) * 1000), 'version': VERSION})
        result.set_cookie(COOKIE, token, max_age=duration if data.get('remember', True) is True else None,
                          secure=True, httponly=True, samesite='Strict', path='/')
        return result

    async def logout(req):
        s = session(req)
        await body(req)
        store.db.execute('DELETE FROM sessions WHERE token=?', (s['token'],))
        for ws in list(sockets.get(s['token'], set())):
            await ws.close(code=1008, message=b'Logged out')
        result = web.json_response({'ok': True})
        result.del_cookie(COOKIE, secure=True, httponly=True, samesite='Strict', path='/')
        return result

    def public_key(value):
        if not isinstance(value, str) or len(value) > 100:
            raise ValueError()
        raw = base64.b64decode(value, validate=True)
        if len(raw) != 65 or raw[0] != 4:
            raise ValueError()
        return value

    def sdp(value, role):
        if not isinstance(value, str) or len(value.encode()) > 64000 or not value.startswith('v=0'):
            raise ValueError()
        if not re.search(r'^a=fingerprint:sha-256 (?:[a-fA-F0-9]{2}:){31}[a-fA-F0-9]{2}\r?$', value, re.M):
            raise ValueError()
        if 'm=application ' not in value or re.search(r'^m=(?:audio|video) ', value, re.M):
            raise ValueError()
        return value

    def room(code):
        if not isinstance(code, str) or not re.fullmatch(r'\d{8}', code):
            raise web.HTTPBadRequest(text='请输入 8 位配对码')
        value = store.db.execute('SELECT * FROM rooms WHERE code=? AND expires>?', (code, time.time())).fetchone()
        if value is None:
            raise web.HTTPNotFound(text='配对码不存在或已过期')
        return value

    def role(r, token):
        if not isinstance(token, str) or not re.fullmatch(r'[a-f0-9]{64}', token):
            raise web.HTTPForbidden(text='配对凭据无效')
        hashed = digest(token)
        if hmac.compare_digest(r['host_hash'], hashed):
            return 'host'
        if r['guest_hash'] and hmac.compare_digest(r['guest_hash'], hashed):
            return 'guest'
        raise web.HTTPForbidden(text='配对凭据无效')

    async def pair(req):
        s = session(req)
        if req.method == 'GET':
            store.limit('poll:' + s['token'], 180)
            r = room(req.query.get('code'))
            who = role(r, req.headers.get('Authorization', '').removeprefix('Bearer '))
            return web.json_response({'expires': int(r['expires'] * 1000), 'answer': r['answer'] if who == 'host' else None})
        data = await body(req)
        op = data.get('op')
        if op == 'create':
            store.cleanup()
            store.limit('create:' + s['token'], 12)
            if store.db.execute('SELECT count(*) FROM rooms').fetchone()[0] >= 32:
                raise web.HTTPTooManyRequests(text='请等待之前的邀请结束')
            mode = data.get('mode', 'direct')
            if mode not in ('direct', 'relay'):
                raise ValueError()
            offer = public_key(data.get('key')) if mode == 'relay' else sdp(data.get('sdp'), 'offer')
            token, code, expires = secrets.token_hex(32), f'{secrets.randbelow(100000000):08d}', time.time() + TTL
            for _ in range(5):
                try:
                    store.db.execute('INSERT INTO rooms VALUES (?,?,?,NULL,?,NULL,?)', (code, mode, digest(token), offer, expires))
                    break
                except sqlite3.IntegrityError:
                    code = f'{secrets.randbelow(100000000):08d}'
            else:
                raise web.HTTPServiceUnavailable(text='请重新生成配对码')
            return web.json_response({'code': code, 'mode': mode, 'token': token, 'expires': int(expires * 1000)})
        if op == 'join':
            store.limit('join:' + s['token'], 20)
            r = room(data.get('code'))
            if data.get('mode', 'direct') != r['mode']:
                raise web.HTTPConflict(text='请在两端选择相同的连接方式')
            answer = public_key(data.get('key')) if r['mode'] == 'relay' else None
            token = secrets.token_hex(32)
            changed = store.db.execute('UPDATE rooms SET guest_hash=?,answer=? WHERE code=? AND guest_hash IS NULL AND expires>?',
                                      (digest(token), answer, r['code'], time.time())).rowcount
            if not changed:
                raise web.HTTPConflict(text='配对码已被使用，请生成新的配对码')
            return web.json_response({'code': r['code'], 'mode': r['mode'], 'token': token, 'offer': r['offer'], 'expires': int(r['expires'] * 1000)})
        r = room(data.get('code'))
        who = role(r, req.headers.get('Authorization', '').removeprefix('Bearer '))
        if op == 'answer' and who == 'guest' and r['mode'] == 'direct':
            answer = sdp(data.get('sdp'), 'answer')
            changed = store.db.execute('UPDATE rooms SET answer=? WHERE code=? AND answer IS NULL AND expires>?', (answer, r['code'], time.time())).rowcount
            if not changed:
                raise web.HTTPConflict(text='邀请已失效')
        elif op == 'close':
            store.db.execute('DELETE FROM rooms WHERE code=?', (r['code'],))
        else:
            raise web.HTTPForbidden(text='配对操作无效')
        return web.json_response({'ok': True})

    async def relay(req):
        s = session(req)
        need_origin(req)
        ip = client_ip(req)
        if pending.get(ip, 0) >= 4 or sum(map(len, sockets.values())) >= 32:
            raise web.HTTPTooManyRequests(text='连接太多，请稍后重试')
        ws = web.WebSocketResponse(heartbeat=20, max_msg_size=40000, receive_timeout=70)
        pending[ip] = pending.get(ip, 0) + 1
        code = who = None
        admitted = False
        try:
            await ws.prepare(req)
            first = await asyncio.wait_for(ws.receive_json(), 5)
            r = room(first.get('code'))
            if r['mode'] != 'relay':
                raise ValueError()
            who = role(r, first.get('token'))
            code = r['code']
            peers = slots.setdefault(code, {})
            if who in peers:
                raise ValueError()
            peers[who] = ws
            sockets.setdefault(s['token'], set()).add(ws)
            pending[ip] = max(0, pending.get(ip, 1) - 1)
            admitted = True
            if len(peers) == 2:
                if not r['answer']:
                    raise ValueError()
                message = {'t': 'paired', 'hostKey': r['offer'], 'guestKey': r['answer']}
                relay_deadlines[code] = time.time() + 600
                for peer in list(peers.values()):
                    await peer.send_json(message)
            else:
                await ws.send_json({'t': 'waiting'})
            deadline = time.time() + (600 if len(peers) == 2 else TTL)
            window, transferred = time.monotonic(), 0
            while not ws.closed:
                remaining = relay_deadlines.get(code, deadline) - time.time()
                if remaining <= 0 or not store.session(req.cookies.get(COOKIE)):
                    break
                try:
                    message = await asyncio.wait_for(ws.receive(), min(remaining, 30))
                except asyncio.TimeoutError:
                    continue
                if message.type in (WSMsgType.CLOSE, WSMsgType.CLOSED, WSMsgType.ERROR):
                    break
                if message.type != WSMsgType.BINARY or len(message.data) < 26:
                    raise ValueError()
                other = slots.get(code, {}).get('guest' if who == 'host' else 'host')
                if other is None or other.closed:
                    raise ValueError()
                now = time.monotonic()
                if now - window >= 1:
                    window, transferred = now, 0
                transferred += len(message.data)
                if transferred > 2 * 1024 * 1024:
                    raise ValueError()
                # Only opaque, already encrypted packets. No packet queue or database write.
                await other.send_bytes(message.data)
        except (ValueError, TypeError, KeyError, web.HTTPException, asyncio.TimeoutError, ConnectionError):
            pass
        finally:
            if not admitted:
                pending[ip] = max(0, pending.get(ip, 1) - 1)
            if pending.get(ip) == 0:
                pending.pop(ip, None)
            sockets.get(s['token'], set()).discard(ws)
            if code and slots.get(code, {}).get(who) is ws:
                group = slots.pop(code, {})
                relay_deadlines.pop(code, None)
                for peer in group.values():
                    if peer is not ws:
                        await peer.close(code=1000, message=b'Peer disconnected')
                store.db.execute('DELETE FROM rooms WHERE code=?', (code,))
            await ws.close(code=1000)
        return ws

    async def version(req):
        session(req)
        path = root / 'release.json'
        return web.json_response(json.loads(path.read_text()) if path.exists() else {'web': VERSION})

    async def static(req):
        name = req.match_info.get('path', '') or 'index.html'
        if name.startswith('.') or any(p.startswith('.') for p in Path(name).parts):
            raise web.HTTPNotFound()
        p = (root / name).resolve()
        if not p.is_relative_to(root) or not p.is_file():
            raise web.HTTPNotFound()
        allowed = name in ('index.html', 'sw.js', 'manifest.webmanifest', 'favicon.svg', 'offline.html', 'release.json') or name.startswith(('assets/', 'icons/'))
        if not allowed:
            raise web.HTTPNotFound()
        result = web.FileResponse(p)
        result.headers['Cache-Control'] = 'public, max-age=31536000, immutable' if name.startswith('assets/') else 'no-cache'
        return result

    async def cleanup_context(app):
        store.cleanup()
        async def sweep():
            while True:
                await asyncio.sleep(60)
                store.cleanup()
                for token, group in list(sockets.items()):
                    if store.db.execute('SELECT 1 FROM sessions WHERE token=? AND expires>?', (token, time.time())).fetchone() is None:
                        for ws in list(group):
                            await ws.close(code=1008, message=b'Session expired')
                    if not group:
                        sockets.pop(token, None)
        task = asyncio.create_task(sweep())
        yield
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
        for group in list(slots.values()):
            for ws in list(group.values()):
                await ws.close(code=1001, message=b'Node restarting')
        store.db.close()

    app.cleanup_ctx.append(cleanup_context)
    app.router.add_get('/_health', health)
    app.router.add_get('/api/auth', auth)
    app.router.add_post('/api/auth', auth)
    app.router.add_post('/api/logout', logout)
    app.router.add_get('/api/pair', pair)
    app.router.add_post('/api/pair', pair)
    app.router.add_get('/api/relay', relay)
    app.router.add_get('/api/version', version)
    app.router.add_get('/{path:.*}', static)
    return app


if __name__ == '__main__':
    import argparse
    os.umask(0o077)
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', required=True)
    parser.add_argument('--state', required=True)
    parser.add_argument('--static', required=True)
    parser.add_argument('--port', type=int, default=18391)
    args = parser.parse_args()
    config_path = Path(args.config)
    if config_path.stat().st_mode & 0o077:
        raise SystemExit('Config must be readable only by its owner')
    config = json.loads(config_path.read_text())
    if not config['origin'].startswith('https://'):
        raise SystemExit('HTTPS public origin required')
    web.run_app(create_app(config, args.state, args.static), host='127.0.0.1', port=args.port,
                access_log=None, print=None)
