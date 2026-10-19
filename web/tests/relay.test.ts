import {test} from 'node:test';
import assert from 'node:assert/strict';
import {identity,keys,Cipher} from '../lib/relay-crypto.ts';
const pair=async()=>{const a=await identity(),b=await identity();const left=await keys(a,a.publicKey,b.publicKey,'host','12345678'),right=await keys(b,a.publicKey,b.publicKey,'guest','12345678');return{a,b,left,right,tx:new Cipher(left.send,left.receive),rx:new Cipher(right.send,right.receive)};};
test('ephemeral ECDH identities produce the same SAS and directional AES-GCM keys',async()=>{
 const p=await pair();assert.equal(p.left.sas,p.right.sas);assert.match(p.left.sas,/^\d{6}$/);
 assert.equal(p.a.privateKey.extractable,false);const packet=await p.tx.encrypt('机密文字');assert.equal(await p.rx.decrypt(packet),'机密文字');
 const bytes=new Uint8Array([0,1,255]).buffer;assert.deepEqual(await p.tx.decrypt(await p.rx.encrypt(bytes)),bytes);
 assert.equal(new TextDecoder().decode(packet).includes('机密文字'),false);
});
test('cipher rejects tampering, reflection, replay and out-of-order packets',async()=>{
 const p=await pair();const one=await p.tx.encrypt('one'),two=await p.tx.encrypt('two');
 await assert.rejects(()=>p.rx.decrypt(two));const bad=one.slice(0);new Uint8Array(bad)[15]^=1;
 await assert.rejects(()=>p.rx.decrypt(bad));await assert.rejects(()=>p.tx.decrypt(one));
 assert.equal(await p.rx.decrypt(one),'one');await assert.rejects(()=>p.rx.decrypt(one));assert.equal(await p.rx.decrypt(two),'two');
});
test('room identity and peer substitution change the comparison code',async()=>{
 const p=await pair(),other=await identity();const changed=await keys(p.a,p.a.publicKey,other.publicKey,'host','12345678');
 assert.notEqual(p.left.sas,changed.sas);await assert.rejects(()=>keys(p.a,p.b.publicKey,p.a.publicKey,'host','12345678'));
 await assert.rejects(()=>keys(p.a,p.a.publicKey,p.a.publicKey,'host','12345678'));
 const different=await keys(p.a,p.a.publicKey,p.b.publicKey,'host','87654321');assert.notEqual(p.left.sas,different.sas);
});
test('bounded encryption supports full text fragments and file blocks',async()=>{
 const p=await pair();for(let i=0;i<8;i++){const body=crypto.getRandomValues(new Uint8Array(16384)).buffer;assert.deepEqual(await p.rx.decrypt(await p.tx.encrypt(body)),body);}
 await assert.rejects(()=>p.tx.encrypt(new ArrayBuffer(32769)));await assert.rejects(()=>p.rx.decrypt(new ArrayBuffer(8)));
});
