"""Owner-run HTTPS protocol probe; never prints cookies, keys or passwords."""
import asyncio
import base64
import hashlib
import json
import uuid

import aiohttp
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


async def probe(origin, username, password=None, local_tokens=None):
    headers = {'Origin':origin}
    async with aiohttp.ClientSession() as a, aiohttp.ClientSession() as b:
        clients = [a,b]
        accounts = []
        for index, client in enumerate(clients):
            if local_tokens:
                from yarl import URL
                client.cookie_jar.update_cookies({'__Host-floatclip':local_tokens[index]}, response_url=URL(origin))
                async with client.get(origin+'/api/auth') as r:
                    assert r.status==200, 'Local probe session failed'
                    accounts.append(await r.json())
                continue
            async with client.post(origin+'/api/auth',json={'username':username,'password':password,'remember':True},headers=headers) as r:
                assert r.status==200, 'Login failed'
                account = await r.json()
                cookie = r.cookies['__Host-floatclip']
                assert cookie['secure'] and cookie['httponly'] and cookie['samesite']=='Strict'
                assert int(cookie['max-age'])==30*86400
                accounts.append(account)
        ah={**headers,'X-CSRF-Token':accounts[0]['csrf']}
        bh={**headers,'X-CSRF-Token':accounts[1]['csrf']}
        for path in ('/','/manifest.webmanifest','/sw.js','/icons/apple-touch-icon.png'):
            async with a.get(origin+path) as r:
                assert r.status==200, 'Public application resource failed'
        async with a.get(origin+'/api/auth') as r:
            assert r.status==200 and (await r.json())['user']==username
        async with a.get(origin+'/api/version') as r:
            assert r.status==200
            release=await r.json()
            assert release['versions']['web']==accounts[0]['version']
            if release.get('distribution',{}).get('android')=='awaiting-original-signature':
                assert 'android' not in release.get('downloads',{})
        private=[ec.generate_private_key(ec.SECP256R1()) for _ in range(2)]
        public=[base64.b64encode(k.public_key().public_bytes(serialization.Encoding.X962,serialization.PublicFormat.UncompressedPoint)).decode() for k in private]
        async with a.post(origin+'/api/pair',json={'op':'create','mode':'relay','key':public[0]},headers=ah) as r:
            assert r.status==200
            host=await r.json()
        async with b.post(origin+'/api/pair',json={'op':'join','mode':'relay','code':host['code'],'key':public[1]},headers=bh) as r:
            assert r.status==200
            guest=await r.json()
        ws_url=origin.replace('https://','wss://')+'/api/relay'
        wa=await a.ws_connect(ws_url,headers=headers)
        await wa.send_json({'code':host['code'],'token':host['token']})
        assert (await wa.receive_json())['t']=='waiting'
        wb=await b.ws_connect(ws_url,headers=headers)
        await wb.send_json({'code':host['code'],'token':guest['token']})
        pa,pb=await asyncio.gather(wa.receive_json(),wb.receive_json())
        assert pa==pb and pa['hostKey']==public[0] and pa['guestKey']==public[1]
        shared=private[0].exchange(ec.ECDH(),private[1].public_key())
        assert shared==private[1].exchange(ec.ECDH(),private[0].public_key())
        transcript=hashlib.sha256(f"FloatClip-Relay-v1|{host['code']}|{public[0]}|{public[1]}".encode()).digest()
        keys=[HKDF(algorithm=hashes.SHA256(),length=32,salt=transcript,info=direction).derive(shared) for direction in (b'host-to-guest',b'guest-to-host')]
        sequence=[0,0]

        async def send(which,value,binary=False):
            raw=value if binary else json.dumps(value,ensure_ascii=False,separators=(',',':')).encode()
            n=sequence[which]
            header=bytes([1,2 if binary else 1])+n.to_bytes(8,'big')
            cipher=AESGCM(keys[which]).encrypt(bytes(4)+n.to_bytes(8,'big'),raw,header)
            sender,receiver=(wa,wb) if which==0 else (wb,wa)
            await sender.send_bytes(header+cipher)
            packet=(await asyncio.wait_for(receiver.receive(),5)).data
            assert isinstance(packet,bytes) and packet[:10]==header
            clear=AESGCM(keys[which]).decrypt(bytes(4)+n.to_bytes(8,'big'),packet[10:],packet[:10])
            assert clear==raw
            sequence[which]+=1
            return clear

        for i in range(2):
            await send(i,{'t':'hello','version':1,'label':'protocol-check'})
            await send(i,{'t':'verified'})
        event=uuid.uuid4().hex
        await send(0,{'t':'text','id':event,'text':'FloatClip HTTPS encrypted relay acceptance','part':0,'total':1})
        await send(1,{'t':'ack','id':event})
        data=bytes(range(256))*160
        file_id=uuid.uuid4().hex
        checksum=hashlib.sha256(data).hexdigest()
        await send(0,{'t':'offer','id':file_id,'name':'protocol-check.bin','size':len(data),'hash':checksum})
        await send(1,{'t':'accept','id':file_id})
        received=b''
        for offset in range(0,len(data),16384):
            received+=await send(0,data[offset:offset+16384],binary=True)
        assert hashlib.sha256(received).hexdigest()==checksum
        await send(0,{'t':'end','id':file_id})
        await send(1,{'t':'ack','id':file_id})
        await wa.close()
        await wb.close()
        for client,csrf in zip(clients,(ah,bh)):
            async with client.post(origin+'/api/logout',json={},headers=csrf) as r:
                assert r.status==200
            async with client.get(origin+'/api/auth') as r:
                assert r.status==401
    return {'https':True,'account_login':not bool(local_tokens),'authenticated_api':True,
            'local_probe_sessions':2 if local_tokens else 0,'remember_days':None if local_tokens else 30,'install_resources':True,
            'wss_pairing':True,'encrypted_text':True,'encrypted_file_bytes':len(data),'file_sha256':True,
            'logout_revoked':True,'browser_ui_tested':False}


if __name__=='__main__':
    import getpass, argparse, sys, time, secrets
    from pathlib import Path
    parser=argparse.ArgumentParser(description='Owner-operated HTTPS acceptance; never prints credentials')
    parser.add_argument('--local-state',help='Owner node state directory: create two 5-minute probe sessions and always remove them; does not test password login')
    args=parser.parse_args()
    if not args.local_state:
        print(json.dumps(asyncio.run(probe('https://clip.wmy-cloud.cn',input('Account: '),getpass.getpass())),ensure_ascii=False))
    else:
        sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
        from server.app import Store, digest
        state=Path(args.local_state).resolve()
        config=json.loads((state/'node-config.json').read_text())
        tokens=[secrets.token_hex(32) for _ in range(2)]
        store=Store(state/'node.sqlite3')
        try:
            for token in tokens:
                store.db.execute('INSERT INTO sessions VALUES (?,?,?,?)',(digest(token),secrets.token_hex(32),time.time()+300,time.time()))
            print(json.dumps(asyncio.run(probe(config['origin'],config['username'],local_tokens=tokens)),ensure_ascii=False))
        finally:
            for token in tokens:store.db.execute('DELETE FROM sessions WHERE token=?',(digest(token),))
            store.db.close()
