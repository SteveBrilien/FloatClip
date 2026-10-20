import {test} from 'node:test';
import assert from 'node:assert/strict';
import {RelayPeer} from '../lib/relay.ts';
import type {TrustedPair,TrustRepository} from '../lib/trusted-store.ts';
import {identity,keys,Cipher,resumeProof,verifyResumeProof} from '../lib/relay-crypto.ts';
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

test('remembered root authenticates fresh ephemeral keys and rejects substitution, role reflection and replay',async()=>{
 const original=await pair();assert.equal(original.left.tag,original.right.tag);assert.equal(original.left.resume.extractable,false);
 const a=await identity(),b=await identity(),nonce='f'.repeat(32),tag=original.left.tag;
 const proof=await resumeProof(original.left.resume,tag,nonce,a.publicKey,b.publicKey,'host');
 assert.equal(await verifyResumeProof(original.right.resume,proof,tag,nonce,a.publicKey,b.publicKey,'host'),true);
 assert.equal(await verifyResumeProof(original.right.resume,proof,tag,'e'.repeat(32),a.publicKey,b.publicKey,'host'),false);
 assert.equal(await verifyResumeProof(original.right.resume,proof,tag,nonce,a.publicKey,b.publicKey,'guest'),false);
 const attacker=await pair();assert.equal(await verifyResumeProof(attacker.left.resume,proof,tag,nonce,a.publicKey,b.publicKey,'host'),false);
 const freshA=await keys(a,a.publicKey,b.publicKey,'host',tag,nonce),freshB=await keys(b,a.publicKey,b.publicKey,'guest',tag,nonce);
 const freshTx=new Cipher(freshA.send,freshA.receive),freshRx=new Cipher(freshB.send,freshB.receive);
 assert.equal(await freshRx.decrypt(await freshTx.encrypt('after resume')),'after resume');
 const old=await original.tx.encrypt('old session');await assert.rejects(()=>new Cipher(freshB.send,freshB.receive).decrypt(old));
});

test('closing while remembered credentials load cannot resurrect an old connection',async()=>{
 const original=await pair();let complete!:(records:TrustedPair[])=>void;
 const store:TrustRepository={all:()=>new Promise(r=>{complete=r;}),put:async()=>{},remove:async()=>{}};
 const peer=new RelayPeer('test',()=>{},{ready:()=>{},item:()=>{},offer:()=>{},progress:()=>{},error:()=>{},closed:()=>{}},undefined,store);
 const pending=peer.resume();assert.equal(peer.state.phase,'joining');peer.close(false);
 complete([{tag:original.left.tag,root:original.left.resume,token:'a'.repeat(64),role:'host',peer:{id:'ipad',name:'iPad',platform:'ipad'},scope:'default',updated:1}]);
 assert.equal(await pending,false);assert.equal(peer.session,null);assert.equal(peer.state.phase,'idle');
});
