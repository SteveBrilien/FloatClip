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


async def probe(origin, username, password):
    headers = {'Origin':origin}
    async with aiohttp.ClientSession() as a, aiohttp.ClientSession() as b:
        clients = [a,b]
        accounts = []
        for client in clients:
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
    return {'https':True,'account_login':True,'remember_days':30,'install_resources':True,
            'wss_pairing':True,'encrypted_text':True,'encrypted_file_bytes':len(data),'file_sha256':True,
            'logout_revoked':True,'browser_ui_tested':False}


if __name__=='__main__':
    import getpass
    print(json.dumps(asyncio.run(probe('https://clip.wmy-cloud.cn',input('Account: '),getpass.getpass())),ensure_ascii=False))
