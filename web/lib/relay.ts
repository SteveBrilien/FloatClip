import type {Device} from './clipboard.ts';
import {DataSession,type Channel,type SessionEvents} from './session.ts';
import {initialPeer,type PeerState} from './peer.ts';
import {identity,keys,Cipher,resumeProof,verifyResumeProof,type Identity} from './relay-crypto.ts';
import {apiFetch} from './auth.ts';
import {BrowserTrustStore,type TrustedPair,type TrustRepository} from './trusted-store.ts';

class EncryptedChannel implements Channel {
 readyState='open';binaryType='arraybuffer';private queued=0;private sending=Promise.resolve();private reading=Promise.resolve();
 onmessage:((event:{data:unknown})=>void)|null=null;onclose:(()=>void)|null=null;
 constructor(private socket:WebSocket,private cipher:Cipher,private fail:()=>void){
  socket.onmessage=e=>{this.reading=this.reading.then(async()=>{if(!(e.data instanceof ArrayBuffer))throw Error();const data=await cipher.decrypt(e.data);if(this.readyState==='open')this.onmessage?.({data});}).catch(()=>fail());};
  socket.onclose=()=>{this.readyState='closed';this.onclose?.();};socket.onerror=fail;
 }
 get bufferedAmount(){return this.queued+this.socket.bufferedAmount;}
 send(value:string|ArrayBuffer){
  if(this.readyState!=='open')throw Error('连接已结束');const n=typeof value==='string'?new TextEncoder().encode(value).length:value.byteLength;
  if(this.bufferedAmount+n>1024*1024){this.fail();throw Error('发送队列已满');}this.queued+=n;
  this.sending=this.sending.then(async()=>{if(this.readyState!=='open')return;const packet=await this.cipher.encrypt(value);if(this.readyState==='open')this.socket.send(packet);}).catch(()=>this.fail()).finally(()=>{this.queued-=n;});
 }
 close(){if(this.readyState==='closed')return;this.readyState='closed';this.socket.close();}
}

class PairApiError extends Error {constructor(message:string,readonly status:number){super(message);}}

export class RelayPeer {
 state={...initialPeer};session:DataSession|null=null;private socket:WebSocket|null=null;private epoch=0;
 private room='';private token='';private timer:ReturnType<typeof setTimeout>|null=null;private retry:ReturnType<typeof setTimeout>|null=null;
 private sending=true;private receiving=true;
 configure(sending:boolean,receiving:boolean){this.sending=sending;this.receiving=receiving;this.session?.configure(sending,receiving);}
 private trust:TrustedPair|null=null;private repository:TrustRepository|null;private automatic=true;private wanted=false;private failures=0;
 constructor(private label:string,private onState:(s:PeerState)=>void,private events:SessionEvents,private device?:Device,repository?:TrustRepository,private scope='default'){
  this.repository=repository??(typeof indexedDB!=='undefined'?new BrowserTrustStore(scope):null);
 }
 private patch(change:Partial<PeerState>){this.state={...this.state,...change};this.onState(this.state);}
 private async api(value:Record<string,unknown>,token=this.token){
  const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),15000);
  try{const response=await apiFetch('/api/pair',{method:'POST',headers:token?{Authorization:'Bearer '+token}:{},body:JSON.stringify({...value,mode:'relay'}),signal:controller.signal});
   const data=await response.json();if(!response.ok)throw new PairApiError(data.error||'连接未完成',response.status);return data;
  }finally{clearTimeout(timer);}
 }
 async trustedPeers(){return this.repository?this.repository.all():[];}
 setAutomatic(value:boolean){this.automatic=value;if(!value&&this.retry){clearTimeout(this.retry);this.retry=null;}else if(value&&this.wanted&&this.trust&&!this.session)this.scheduleResume();}
 async forget(tag:string){
  const record=(await this.trustedPeers()).find(x=>x.tag===tag);if(!record)return;
  if(this.trust?.tag===tag)this.close();
  try{await this.api({op:'resume-forget',tag},record.token);}catch(e){if(!(e instanceof PairApiError)||e.status!==404)throw e;}await this.repository!.remove(tag);
 }
 private fail(message:string,retry=true){
  const trusted=this.trust,wanted=this.wanted;this.teardown();this.trust=trusted;this.wanted=wanted;
  this.patch({phase:'failed',message});this.events.closed();if(retry)this.scheduleResume();
 }
 private scheduleResume(){
  if(!this.wanted||!this.automatic||!this.trust||this.retry)return;
  const delay=Math.min(30000,1000*2**Math.min(this.failures++,5))+Math.random()*500;
  this.retry=setTimeout(()=>{this.retry=null;if(this.wanted&&this.automatic&&this.trust)void this.resumeRecord(this.trust);},delay);
 }
 async resume(tag?:string){
  const epoch=this.epoch;this.patch({phase:'joining',message:'正在读取已配对设备'});
  const peers=await this.trustedPeers();if(epoch!==this.epoch)return false;const record=tag?peers.find(x=>x.tag===tag):peers.sort((a,b)=>b.updated-a.updated)[0];
  if(!record){this.patch({...initialPeer});return false;}this.wanted=true;await this.resumeRecord(record);return true;
 }
 private async resumeRecord(record:TrustedPair){
  this.teardown();this.trust=record;this.wanted=true;const epoch=this.epoch;
  this.patch({...initialPeer,phase:'joining',peer:record.peer.name,message:'正在恢复已配对设备'});
  try{const local=await identity();if(epoch===this.epoch)this.connect(local,record.role,epoch,record);}
  catch{if(epoch===this.epoch)this.fail('暂时无法恢复设备连接');}
 }
 private async remember(derived:Awaited<ReturnType<typeof keys>>,role:'host'|'guest',epoch:number){
  if(!this.repository||!this.session||this.trust)return;
  const record:TrustedPair={tag:derived.tag,token:Array.from(crypto.getRandomValues(new Uint8Array(32)),b=>b.toString(16).padStart(2,'0')).join(''),role,root:derived.resume,peer:this.session.remoteDevice,scope:this.scope,updated:Date.now()};
  try{await this.api({op:'remember',code:this.room,tag:record.tag,resumeToken:record.token});if(epoch!==this.epoch)return;await this.repository.put(record);if(epoch!==this.epoch)return;this.trust=record;this.patch({message:'设备已记住 · 断线后自动恢复'});}
  catch{if(epoch===this.epoch)this.patch({message:'当前已连接，保存配对未完成，请保留页面并重试配对'});}
 }
 private connect(local:Identity,role:'host'|'guest',epoch:number,trusted?:TrustedPair){
  const url=new URL('/api/relay',location.href);url.protocol=url.protocol==='https:'?'wss:':'ws:';
  const socket=new WebSocket(url);socket.binaryType='arraybuffer';this.socket=socket;let started=false;
  socket.onopen=()=>{if(epoch!==this.epoch)return socket.close();socket.send(JSON.stringify(trusted?{resume:trusted.tag,token:trusted.token,key:local.publicKey}:{code:this.room,token:this.token}));};
  socket.onerror=()=>{if(epoch===this.epoch)this.fail('小主机连接不可用，等待恢复');};
  socket.onclose=()=>{if(epoch===this.epoch)this.fail(trusted?'设备暂时离线，等待自动恢复':'连接已结束');};
  socket.onmessage=async e=>{
   if(epoch!==this.epoch||started)return;
   try{
    if(typeof e.data!=='string'||e.data.length>1024)throw Error();const message=JSON.parse(e.data);
    if(message.t==='waiting')return;if(message.t!=='paired')throw Error();started=true;
    const pending:ArrayBuffer[]=[];socket.onmessage=e=>{if(!(e.data instanceof ArrayBuffer)||pending.length>=8)return this.fail('连接握手异常',false);pending.push(e.data);};
    const derived=await keys(local,message.hostKey,message.guestKey,role,trusted?.tag??this.room,trusted?message.nonce:'');
    if(epoch!==this.epoch)return socket.close();
    const channel=new EncryptedChannel(socket,new Cipher(derived.send,derived.receive),()=>{if(epoch===this.epoch)this.fail('加密校验失败，连接已结束',false);});
    const buffered:unknown[]=[];
    if(trusted){
     await new Promise<void>((resolve,reject)=>{
      let proofSeen=false;const timeout=setTimeout(()=>reject(Error('验证超时')),10000);
      channel.onmessage=e=>{
       if(proofSeen){if(buffered.length>=8){clearTimeout(timeout);reject(Error('握手数据过多'));}else buffered.push(e.data);return;}
       proofSeen=true;
       void (async()=>{try{
        if(typeof e.data!=='string')throw Error();const proof=JSON.parse(e.data);
        if(proof.t!=='resume-proof'||typeof proof.mac!=='string'||!await verifyResumeProof(trusted.root,proof.mac,trusted.tag,message.nonce,message.hostKey,message.guestKey,role==='host'?'guest':'host'))throw Error();
        resolve();
       }catch{reject(Error('设备身份验证失败'));}finally{clearTimeout(timeout);}})();
      };
      void resumeProof(trusted.root,trusted.tag,message.nonce,message.hostKey,message.guestKey,role).then(mac=>{if(epoch===this.epoch)channel.send(JSON.stringify({t:'resume-proof',mac}));else reject(Error('连接已取消'));}).catch(reject);
      for(const data of pending)socket.onmessage?.({data} as MessageEvent);pending.length=0;
     });
     if(epoch!==this.epoch)return channel.close();
    }
    this.patch({phase:trusted?'joining':'verifying',sas:trusted?'':derived.sas,message:trusted?'已验证记住的设备':'核对两端识别码'});
    let saving=false;
    this.session=new DataSession(channel,{
     ...this.events,
     ready:(ready,peer)=>{if(epoch!==this.epoch)return;this.patch({phase:ready?'ready':trusted?'joining':'verifying',peer,message:ready?'经小主机加密共享':trusted?'正在恢复共享':'核对两端识别码'});
      if(ready){this.failures=0;if(this.timer)clearTimeout(this.timer);this.timer=null;if(!trusted&&!saving){saving=true;void this.remember(derived,role,epoch);}}},
     closed:()=>{if(epoch===this.epoch)this.fail('设备暂时离线，等待自动恢复');}
    },this.label,this.device,trusted?.peer);
    this.session.configure(this.sending,this.receiving);
    if(trusted)this.session.confirm();
    for(const data of buffered)channel.onmessage?.({data});
    for(const data of pending)socket.onmessage?.({data} as MessageEvent);
    if(this.timer)clearTimeout(this.timer);this.timer=null;
    if(!trusted)this.timer=setTimeout(()=>{if(epoch===this.epoch&&this.state.phase!=='ready')this.fail('核对识别码超时');},60000);
   }catch{if(epoch===this.epoch)this.fail(trusted?'设备身份校验未通过，请移除旧配对后重新核对':'无法核对连接身份，请重新配对',false);}
  };
  if(trusted)this.timer=setTimeout(()=>{if(epoch===this.epoch&&this.state.phase!=='ready')this.fail('设备尚未上线，稍后自动重试');},125000);
 }
 async create(){
  this.close(false);this.wanted=true;const epoch=this.epoch;this.patch({...initialPeer,phase:'making',message:'正在生成配对码'});
  try{const local=await identity(),room=await this.api({op:'create',key:local.publicKey});if(epoch!==this.epoch)return;
   this.room=room.code;this.token=room.token;this.patch({phase:'waiting',code:room.code,expires:room.expires,message:'在另一端输入配对码'});this.connect(local,'host',epoch);
   this.timer=setTimeout(()=>{if(epoch===this.epoch)this.fail('配对码已过期，请重新生成');},120000);
  }catch(e){if(epoch===this.epoch)this.fail(e instanceof Error?e.message:'无法生成配对码');}
 }
 async join(code:string){
  this.close(false);this.wanted=true;const epoch=this.epoch;this.patch({...initialPeer,phase:'joining',message:'正在连接小主机'});
  try{const local=await identity(),room=await this.api({op:'join',code,key:local.publicKey});if(epoch!==this.epoch)return;
   this.room=room.code;this.token=room.token;this.connect(local,'guest',epoch);this.timer=setTimeout(()=>{if(epoch===this.epoch)this.fail('连接超时，请重新生成配对码');},30000);
  }catch(e){if(epoch===this.epoch)this.fail(e instanceof Error?e.message:'连接未完成');}
 }
 confirm(){if(this.state.phase!=='verifying'||!this.session)return;this.patch({confirmed:true,message:'等待另一端确认'});this.session.confirm();}
 private teardown(){
  this.epoch++;if(this.timer)clearTimeout(this.timer);this.timer=null;if(this.retry)clearTimeout(this.retry);this.retry=null;
  if(this.socket){this.socket.onclose=null;this.socket.onerror=null;this.socket.close();this.socket=null;}
  const session=this.session;this.session=null;session?.dispose();
  this.room='';this.token='';
 }
 close(notify=true){
  const room=this.room,token=this.token;this.wanted=false;this.teardown();this.trust=null;
  if(room&&token)void this.api({op:'close',code:room},token).catch(()=>{});
  this.patch({...initialPeer,message:notify?'共享已暂停，设备授权已保留':'连接后即可共享'});
 }
}
