import {test} from 'node:test';
import assert from 'node:assert/strict';
import {MAX_FILE,MAX_TEXT,CHUNK,parseControl,filename,sha256,encoder,verificationCode,validateSdp,nextSize} from '../lib/protocol.ts';
import {DataSession,type Channel,type SharedItem,type SessionEvents} from '../lib/session.ts';
const fp=(n:number)=>Array(32).fill(n.toString(16).padStart(2,'0')).join(':');
const sdp=(n:number,type='actpass')=>`v=0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=fingerprint:sha-256 ${fp(n)}\r\na=setup:${type}\r\n`;
test('connection identity is symmetric and changes when the other certificate changes',async()=>{assert.equal(await verificationCode(sdp(1),sdp(2)),await verificationCode(sdp(2),sdp(1)));assert.notEqual(await verificationCode(sdp(1),sdp(2)),await verificationCode(sdp(1),sdp(3)));assert.match(await verificationCode(sdp(1),sdp(2)),/^\d{6}$/);});
test('reject malformed identities and media offers',async()=>{assert.throws(()=>validateSdp('v=0','offer'));assert.throws(()=>validateSdp(sdp(1)+'m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n','offer'));await assert.rejects(()=>verificationCode('bad',sdp(1)));});
test('filename traversal, bidi and control characters cannot become download paths',()=>{assert.equal(filename('../../报告\u202e.txt'),'.._.._报告.txt');assert.throws(()=>filename('.'));assert.throws(()=>filename('测'.repeat(90)));assert.equal(filename('项目\u0000.txt'),'项目_.txt');});
test('control messages enforce file size, integer size, hashes and known message types',()=>{const valid={t:'offer',id:'abc',name:'a.txt',size:MAX_FILE,hash:'f'.repeat(64)};assert.equal(parseControl(JSON.stringify(valid)).size,MAX_FILE);for(const changed of [{size:MAX_FILE+1},{size:-1},{size:1.5},{hash:'bad'},{id:'../x'},{t:'execute'}])assert.throws(()=>parseControl(JSON.stringify({...valid,...changed})));});
test('file chunks cannot overrun declared size or frame limit',()=>{assert.equal(nextSize(0,CHUNK,CHUNK),CHUNK);assert.throws(()=>nextSize(0,CHUNK+1,MAX_FILE));assert.throws(()=>nextSize(15,2,16));assert.throws(()=>nextSize(0,0,0));});
class Wire implements Channel{
 readyState='open';bufferedAmount=0;binaryType='arraybuffer';other!:Wire;onmessage:Channel['onmessage']=null;onclose:Channel['onclose']=null;queue:unknown[]=[];maxFrame=0;
 send(data:string|ArrayBuffer){if(this.readyState!=='open')throw Error('Closed');this.maxFrame=Math.max(this.maxFrame,typeof data==='string'?encoder.encode(data).length:data.byteLength);this.other.queue.push(data);queueMicrotask(()=>this.other.flush());}
 flush(){while(this.onmessage&&this.queue.length&&this.readyState==='open')this.onmessage({data:this.queue.shift()});}
 close(){if(this.readyState==='closed')return;this.readyState='closed';const cb=this.onclose;this.onclose=null;cb?.();}
}
const sleep=(ms=10)=>new Promise(r=>setTimeout(r,ms));
async function until(fn:()=>boolean){for(let i=0;i<300;i++){if(fn())return;await sleep(5);}throw new Error('Timed out');}
function endpoints(){const a=new Wire,b=new Wire;a.other=b;b.other=a;const left:SharedItem[]=[],right:SharedItem[]=[],errors:string[]=[],offers:any[]=[];
 const events=(list:SharedItem[]):SessionEvents=>({ready:()=>{},item:i=>list.push(i),offer:o=>{if(o)offers.push(o);},progress:()=>{},error:e=>errors.push(e),closed:()=>{}});
 const sender=new DataSession(a,events(left),'手机'),receiver=new DataSession(b,events(right),'iPad');a.flush();b.flush();
 return{a,b,sender,receiver,left,right,errors,offers,dispose(){sender.dispose();receiver.dispose();}};
}
test('both confirmations are required before content can be sent',async()=>{const p=endpoints();try{await sleep();assert.throws(()=>p.sender.sendText('private'));p.sender.confirm();await sleep();assert.equal(p.sender.ready,false);p.receiver.confirm();await until(()=>p.sender.ready&&p.receiver.ready);p.sender.sendText('你好，iPad');await until(()=>p.right.length===1&&p.left.length===1);assert.equal(p.right[0].text,'你好，iPad');assert.equal(p.left[0].direction,'sent');}finally{p.dispose();}});
test('64 KiB text and Unicode use bounded frames and arrive exactly once',async()=>{const p=endpoints();try{p.sender.confirm();p.receiver.confirm();await until(()=>p.sender.ready);const text='a'.repeat(MAX_TEXT);p.sender.sendText(text);await until(()=>p.right.length===1&&p.left.length===1);assert.equal(p.right[0].text,text);assert.ok(p.a.maxFrame<=16384);const unicode='🦋汉字\n'.repeat(2400);p.sender.sendText(unicode);await until(()=>p.right.length===2);assert.equal(p.right[1].text,unicode);assert.throws(()=>p.sender.sendText('汉'.repeat(30000)));}finally{p.dispose();}});
test('file content is sent only after acceptance and matching SHA-256 is acknowledged',async()=>{const p=endpoints();try{p.sender.confirm();p.receiver.confirm();await until(()=>p.sender.ready);const bytes=crypto.getRandomValues(new Uint8Array(40000));const file=new File([bytes],'fixture.bin');await p.sender.offerFile(file);await until(()=>p.offers.length===1);assert.equal(p.right.length,0);await sleep();assert.equal(p.right.length,0);p.receiver.acceptFile();await until(()=>p.left.length===1&&p.right.length===1);assert.deepEqual(new Uint8Array(await p.right[0].blob!.arrayBuffer()),bytes);assert.ok(p.a.maxFrame<=CHUNK);assert.equal(p.errors.length,0);}finally{p.dispose();}});
test('rejected file has no delivered item, and empty files transfer correctly',async()=>{const p=endpoints();try{p.sender.confirm();p.receiver.confirm();await until(()=>p.sender.ready);await p.sender.offerFile(new File(['no'],'reject.txt'));await until(()=>p.offers.length===1);p.receiver.rejectFile();await sleep(30);assert.equal(p.right.length,0);await p.sender.offerFile(new File([],'empty.txt'));await until(()=>p.offers.length===2);p.receiver.acceptFile();await until(()=>p.right.length===1);assert.equal(p.right[0].blob!.size,0);}finally{p.dispose();}});
test('binary before consent is rejected without exposing content',async()=>{const p=endpoints();try{p.sender.confirm();p.receiver.confirm();await until(()=>p.sender.ready);p.a.send(new Uint8Array([1,2,3]).buffer);await until(()=>p.errors.length>0);assert.equal(p.right.length,0);}finally{p.dispose();}});
test('corrupted file hash never exposes a received file',async()=>{const p=endpoints();try{p.sender.confirm();p.receiver.confirm();await until(()=>p.sender.ready);const original=p.a.send.bind(p.a);p.a.send=data=>{if(data instanceof ArrayBuffer){const modified=data.slice(0);new Uint8Array(modified)[0]^=255;original(modified);}else original(data);};await p.sender.offerFile(new File([new Uint8Array([1,2,3])],'corrupt.bin'));await until(()=>p.offers.length===1);p.receiver.acceptFile();await until(()=>p.errors.length>0);assert.equal(p.right.length,0);}finally{p.dispose();}});


test('receive pause and a new consent epoch discard already queued text without delivery ACK',async()=>{
 const p=endpoints();try{
  p.sender.confirm();p.receiver.confirm();await until(()=>p.sender.ready&&p.receiver.ready);
  const original=p.a.send.bind(p.a),held:(string|ArrayBuffer)[]=[];let hold=true;
  p.a.send=data=>{if(hold&&typeof data==='string'&&JSON.parse(data).t==='text')held.push(data);else original(data);};
  p.sender.sendText('before pause');p.receiver.configure(true,false);await sleep();p.receiver.configure(true,true);await sleep();
  hold=false;for(const data of held)original(data);await sleep(30);assert.equal(p.left.length,0);assert.equal(p.right.length,0);
  p.sender.sendText('after new grant');await until(()=>p.left.length===1&&p.right.length===1);assert.equal(p.right[0].text,'after new grant');assert.deepEqual(p.errors,[]);
 }finally{p.dispose();}
});
test('failed durable history save never acknowledges delivered text',async()=>{
 const a=new Wire,b=new Wire;a.other=b;b.other=a;const delivered:SharedItem[]=[],errors:string[]=[];
 const events=(item:SessionEvents['item']):SessionEvents=>({ready:()=>{},item,offer:()=>{},progress:()=>{},error:e=>errors.push(e),closed:()=>{}});
 const sender=new DataSession(a,events(i=>delivered.push(i)),'sender'),receiver=new DataSession(b,events(async()=>{throw Error('disk full');}),'receiver');
 try{a.flush();b.flush();sender.confirm();receiver.confirm();await until(()=>sender.ready&&receiver.ready);sender.sendText('must be durable');await until(()=>errors.length>0);assert.equal(delivered.length,0);}finally{sender.dispose();receiver.dispose();}
});
