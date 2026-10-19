import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createRequire} from 'node:module';
import {RelayPeer} from '../lib/relay.ts';
import {setCsrf} from '../lib/auth.ts';
import type {SessionEvents,SharedItem} from '../lib/session.ts';

// A protocol integration test over real HTTP/WebSocket sockets; no browser or UI.
test('actual RelayPeer exchanges verified text and files through the actual node',async()=>{
 const require=createRequire(import.meta.url);
 const wsPath=process.env.FLOATCLIP_TEST_WS;
 
 const WS=require(wsPath||'ws'),dir=await mkdtemp(join(tmpdir(),'floatclip-net-'));
 const python=`import asyncio,json\nfrom server.app import create_app,password_hash\nfrom aiohttp import web\nasync def main():\n app=create_app({'origin':'https://clip.test','username':'fixture','password':password_hash('fixture-password'),'test':True},${JSON.stringify(dir)},'dist')\n runner=web.AppRunner(app,access_log=None);await runner.setup();site=web.TCPSite(runner,'127.0.0.1',0);await site.start();print(json.dumps({'port':site._server.sockets[0].getsockname()[1]}),flush=True);await asyncio.Event().wait()\nasyncio.run(main())`;
 const child=spawn('python3',['-u','-c',python],{stdio:['ignore','pipe','pipe']});
 let diagnostics='';child.stderr.on('data',b=>{diagnostics+=b.toString();});
 const started=new Promise<number>((resolve,reject)=>{let s='';child.stdout.on('data',b=>{s+=b.toString();const i=s.indexOf('\n');if(i>=0){try{resolve(JSON.parse(s.slice(0,i)).port);}catch(e){reject(e);}}});child.once('exit',()=>reject(Error(diagnostics)));});
 const nativeFetch=globalThis.fetch,originalWS=globalThis.WebSocket,originalWindow=(globalThis as any).window,originalLocation=globalThis.location;
 let a:RelayPeer|undefined,b:RelayPeer|undefined;
 const until=async(fn:()=>boolean)=>{for(let i=0;i<500;i++){if(fn())return;await new Promise(r=>setTimeout(r,10));}throw Error('Relay handshake timed out');};
 try{
  const port=await started,base=`http://127.0.0.1:${port}`;
  const response=await nativeFetch(base+'/api/auth',{method:'POST',headers:{Origin:'https://clip.test','Content-Type':'application/json'},body:JSON.stringify({username:'fixture',password:'fixture-password'})});
  assert.equal(response.status,200);const account=await response.json() as {csrf:string};setCsrf(account.csrf);
  const cookie=response.headers.get('set-cookie')!.split(';')[0];
  globalThis.fetch=(path:any,init:any={})=>{const headers=new Headers(init.headers);headers.set('Origin','https://clip.test');headers.set('Cookie',cookie);return nativeFetch(new URL(path,base),{...init,headers});};
  (globalThis as any).window=new EventTarget();(globalThis as any).location=new URL(base);
  (globalThis as any).WebSocket=class extends WS {constructor(url:string){super(url,{headers:{Origin:'https://clip.test',Cookie:cookie}});}};
  const left:SharedItem[]=[],right:SharedItem[]=[],errors:string[]=[];
  const events=(list:SharedItem[],accept=false):SessionEvents=>({ready:()=>{},item:x=>list.push(x),offer:x=>{if(x&&accept)queueMicrotask(()=>b?.session?.acceptFile());},progress:()=>{},error:e=>errors.push(e),closed:()=>{}});
  a=new RelayPeer('Windows test',()=>{},events(left),{id:'windows-native-fixture',name:'Windows test',platform:'windows'});b=new RelayPeer('iPad test',()=>{},events(right,true),{id:'ipad-web-fixture',name:'iPad test',platform:'ipad'});
  await a.create();await b.join(a.state.code);await until(()=>a!.state.phase==='verifying'&&b!.state.phase==='verifying');
  assert.equal(a.state.sas,b.state.sas);a.confirm();b.confirm();await until(()=>a!.state.phase==='ready'&&b!.state.phase==='ready');
  a.session!.sendText('真实协议通道 · 🦋');await until(()=>left.length===1&&right.length===1);assert.equal(right[0].text,'真实协议通道 · 🦋');assert.deepEqual(right[0].source,{id:'windows-native-fixture',name:'Windows test',platform:'windows'});assert.equal(left[0].source?.id,'windows-native-fixture');
  const bytes=crypto.getRandomValues(new Uint8Array(40000));await a.session!.offerFile(new File([bytes],'network-check.bin'));
  await until(()=>left.length===2&&right.length===2);assert.deepEqual(new Uint8Array(await right[1].blob!.arrayBuffer()),bytes);assert.equal(right[1].source?.id,'windows-native-fixture');assert.deepEqual(errors,[]);
 }finally{
  a?.close();b?.close();await new Promise(r=>setTimeout(r,50));globalThis.fetch=nativeFetch;globalThis.WebSocket=originalWS;
  (globalThis as any).window=originalWindow;(globalThis as any).location=originalLocation;setCsrf('');if(child.exitCode===null){const ended=new Promise(r=>child.once('exit',r));child.kill('SIGTERM');await ended;}
  await rm(dir,{recursive:true,force:true});
 }
});
