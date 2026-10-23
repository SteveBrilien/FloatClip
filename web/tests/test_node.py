import asyncio
import base64
import json
import tempfile
import unittest
from pathlib import Path
from http.cookies import SimpleCookie

from aiohttp.test_utils import TestClient, TestServer
from aiohttp import WSMsgType
from server.app import create_app, password_hash, COOKIE

ORIGIN = 'https://clip.test'


class NodeTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.static = self.root / 'static'
        self.static.mkdir()
        (self.static / 'index.html').write_text('<html>login</html>')
        (self.static / 'secret.txt').write_text('must not serve')
        self.config = {'origin': ORIGIN, 'username': 'test-user', 'password': password_hash('test-password'), 'test': True}
        self.app = create_app(self.config, self.root / 'state', self.static)
        self.client = TestClient(TestServer(self.app))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()
        self.tmp.cleanup()

    async def login(self, remember=True):
        r = await self.client.post('/api/auth', json={'username': 'test-user', 'password': 'test-password', 'remember': remember}, headers={'Origin': ORIGIN})
        self.assertEqual(r.status, 200)
        data = await r.json()
        return { 'Cookie': f'{COOKIE}={r.cookies[COOKIE].value}', 'X-CSRF-Token': data['csrf'], 'Origin': ORIGIN }, r

    async def create(self, headers, mode='relay'):
        key = base64.b64encode(b'\x04' + b'\x01' * 64).decode()
        r = await self.client.post('/api/pair', json={'op': 'create', 'mode': mode, 'key': key}, headers=headers)
        self.assertEqual(r.status, 200)
        return await r.json()

    async def test_authentication_cookie_and_no_plaintext_secret(self):
        self.assertEqual((await self.client.get('/api/auth')).status, 401)
        headers, r = await self.login()
        c = r.cookies[COOKIE]
        self.assertTrue(c['secure'])
        self.assertTrue(c['httponly'])
        self.assertEqual(c['samesite'], 'Strict')
        self.assertEqual(int(c['max-age']), 30 * 86400)
        self.assertEqual((await self.client.get('/api/auth', headers=headers)).status, 200)
        store = self.app['store']
        token = headers['Cookie'].split('=', 1)[1]
        self.assertNotIn(token, str([tuple(row) for row in store.db.execute('SELECT * FROM sessions')]))
        self.assertEqual((await self.client.get('/api/pair')).status, 401)

    async def test_csrf_origin_checks_and_logout_revocation(self):
        headers, _ = await self.login()
        missing = {k:v for k,v in headers.items() if k != 'X-CSRF-Token'}
        self.assertEqual((await self.client.post('/api/logout', json={}, headers=missing)).status, 403)
        evil = {**headers, 'Origin': 'https://other.test'}
        self.assertEqual((await self.client.post('/api/logout', json={}, headers=evil)).status, 403)
        self.assertEqual((await self.client.post('/api/logout', json={}, headers=headers)).status, 200)
        self.assertEqual((await self.client.get('/api/auth', headers=headers)).status, 401)

    async def test_session_expiration_and_restart_persistence(self):
        headers, _ = await self.login()
        cookie = headers['Cookie'].split('=', 1)[1]
        from server.app import Store, digest
        another = Store(self.root / 'state' / 'node.sqlite3')
        self.assertIsNotNone(another.session(cookie))
        another.db.close()
        self.app['store'].db.execute('UPDATE sessions SET expires=0')
        self.assertEqual((await self.client.get('/api/auth', headers=headers)).status, 401)

    async def test_login_throttling_and_bad_credentials(self):
        for _ in range(12):
            r = await self.client.post('/api/auth', json={'username':'test-user','password':'wrong'}, headers={'Origin':ORIGIN})
            self.assertEqual(r.status, 401)
        r = await self.client.post('/api/auth', json={'username':'test-user','password':'wrong'}, headers={'Origin':ORIGIN})
        self.assertEqual(r.status, 429)

    async def test_pairing_single_claim_role_mode_and_expiry(self):
        headers, _ = await self.login()
        host = await self.create(headers)
        body = {'op':'join','code':host['code'],'mode':'relay','key':base64.b64encode(b'\x04'+b'\x02'*64).decode()}
        wrong_mode = await self.client.post('/api/pair', json={**body,'mode':'direct'}, headers=headers)
        self.assertEqual(wrong_mode.status, 409)
        results = await asyncio.gather(*[self.client.post('/api/pair', json=body, headers=headers) for _ in range(2)])
        self.assertEqual(sorted(r.status for r in results), [200,409])
        bad = {**headers,'Authorization':'Bearer '+'0'*64}
        self.assertEqual((await self.client.get('/api/pair?code='+host['code'],headers=bad)).status,403)
        self.app['store'].db.execute('UPDATE rooms SET expires=0')
        self.assertEqual((await self.client.post('/api/pair',json=body,headers=headers)).status,404)

    async def test_relay_requires_authentication_and_transfers_only_opaque_binary(self):
        headers, _ = await self.login()
        host = await self.create(headers)
        guest_resp = await self.client.post('/api/pair', json={'op':'join','code':host['code'],'mode':'relay','key':base64.b64encode(b'\x04'+b'\x02'*64).decode()},headers=headers)
        guest = await guest_resp.json()
        a = await self.client.ws_connect('/api/relay', headers=headers)
        await a.send_json({'code':host['code'],'token':host['token']})
        self.assertEqual((await a.receive_json())['t'],'waiting')
        b = await self.client.ws_connect('/api/relay', headers=headers)
        await b.send_json({'code':host['code'],'token':guest['token']})
        self.assertEqual((await a.receive_json())['t'],'paired')
        self.assertEqual((await b.receive_json())['t'],'paired')
        payload = b'\x01\x02'+b'\x00'*8+b'opaque-encrypted-body'
        await a.send_bytes(payload)
        msg = await asyncio.wait_for(b.receive(),2)
        self.assertEqual(msg.type,WSMsgType.BINARY)
        self.assertEqual(msg.data,payload)
        self.assertEqual((await self.client.post('/api/logout',json={},headers=headers)).status,200)
        self.assertIn((await asyncio.wait_for(a.receive(),2)).type,(WSMsgType.CLOSE,WSMsgType.CLOSED))
        await a.close()
        await b.close()

    async def test_remembered_pair_persists_hashed_grants_and_revokes_both_roles(self):
        headers,_=await self.login()
        host=await self.create(headers)
        key=base64.b64encode(b'\x04'+b'\x02'*64).decode()
        response=await self.client.post('/api/pair',json={'op':'join','mode':'relay','code':host['code'],'key':key},headers=headers)
        guest=await response.json()
        tag='a'*64
        for who,token in ((host,'b'*64),(guest,'c'*64)):
            result=await self.client.post('/api/pair',json={'op':'remember','code':host['code'],'tag':tag,'resumeToken':token},headers={**headers,'Authorization':'Bearer '+who['token']})
            self.assertEqual(result.status,200)
        from server.app import Store,digest
        restored=Store(self.root/'state'/'node.sqlite3')
        saved=restored.db.execute('SELECT * FROM trusted WHERE tag=?',(tag,)).fetchone()
        self.assertEqual(saved['host_hash'],digest('b'*64));self.assertEqual(saved['guest_hash'],digest('c'*64))
        self.assertNotIn('b'*64,str(tuple(saved)));self.assertNotIn('c'*64,str(tuple(saved)));restored.db.close()
        wrong=await self.client.post('/api/pair',json={'op':'remember','code':host['code'],'tag':tag,'resumeToken':'d'*64},headers={**headers,'Authorization':'Bearer '+host['token']})
        self.assertEqual(wrong.status,409)
        a=await self.client.ws_connect('/api/relay',headers=headers)
        await a.send_json({'resume':tag,'token':'b'*64,'key':key})
        self.assertEqual((await a.receive_json())['t'],'waiting')
        b=await self.client.ws_connect('/api/relay',headers=headers)
        await b.send_json({'resume':tag,'token':'c'*64,'key':base64.b64encode(b'\x04'+b'\x03'*64).decode()})
        first,second=await asyncio.gather(a.receive_json(),b.receive_json())
        self.assertEqual(first,second);self.assertEqual(len(first['nonce']),32)
        packet=b'\x01'*26;await a.send_bytes(packet);self.assertEqual((await b.receive()).data,packet)
        revoked=await self.client.post('/api/pair',json={'op':'resume-forget','tag':tag},headers={**headers,'Authorization':'Bearer '+'b'*64})
        self.assertEqual(revoked.status,200)
        self.assertEqual(self.app['store'].db.execute('SELECT count(*) FROM trusted').fetchone()[0],0)
        await a.close();await b.close()

    async def test_static_sandbox_headers_and_body_limits(self):
        r = await self.client.get('/')
        self.assertEqual(r.status,200)
        self.assertIn("frame-ancestors 'none'",r.headers['Content-Security-Policy'])
        self.assertEqual((await self.client.get('/secret.txt')).status,404)
        self.assertEqual((await self.client.get('/.env')).status,404)
        r=await self.client.post('/api/auth',data='x'*72001,headers={'Origin':ORIGIN,'Content-Type':'application/json'})
        self.assertEqual(r.status,413)

    async def test_authenticated_devices_do_not_consume_pending_ip_slots(self):
        headers, _ = await self.login()
        connections = []
        try:
            for _ in range(6):
                host = await self.create(headers)
                ws = await self.client.ws_connect('/api/relay', headers=headers)
                connections.append(ws)
                await ws.send_json({'code':host['code'],'token':host['token']})
                self.assertEqual((await ws.receive_json())['t'],'waiting')
        finally:
            for ws in connections:
                await ws.close()


if __name__ == '__main__':
    unittest.main()
