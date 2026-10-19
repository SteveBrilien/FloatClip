import type {Device} from './clipboard.ts';
import {DataSession,type Channel,type SessionEvents} from './session.ts';
import {initialPeer,type PeerState} from './peer.ts';
import {identity,keys,Cipher,type Identity} from './relay-crypto.ts';
import {apiFetch} from './auth.ts';

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

export class RelayPeer {
 state={...initialPeer};session:DataSession|null=null;private socket:WebSocket|null=null;private epoch=0;
 private room='';private token='';private timer:ReturnType<typeof setTimeout>|null=null;
 constructor(private label:string,private onState:(s:PeerState)=>void,private events:SessionEvents,private device?:Device){}
 private patch(change:Partial<PeerState>){this.state={...this.state,...change};this.onState(this.state);}
 private async api(value:Record<string,unknown>){
  const response=await apiFetch('/api/pair',{method:'POST',body:JSON.stringify({...value,mode:'relay'})});
  const data=await response.json();if(!response.ok)throw Error(data.error||'连接未完成');return data;
 }
 private fail(message:string){this.close(false);this.patch({phase:'failed',message});}
 private connect(local:Identity,role:'host'|'guest',epoch:number){
  const url=new URL('/api/relay',location.href);url.protocol=url.protocol==='https:'?'wss:':'ws:';
  const socket=new WebSocket(url);socket.binaryType='arraybuffer';this.socket=socket;let started=false;
  socket.onopen=()=>{if(epoch!==this.epoch)return socket.close();socket.send(JSON.stringify({code:this.room,token:this.token}));};
  socket.onerror=()=>{if(epoch===this.epoch)this.fail('小主机连接不可用，请稍后重试');};
  socket.onclose=()=>{if(epoch===this.epoch)this.fail('连接已结束，请重新配对');};
  socket.onmessage=async e=>{
   if(epoch!==this.epoch||started)return;
   try{
    if(typeof e.data!=='string'||e.data.length>1024)throw Error();const message=JSON.parse(e.data);
    if(message.t==='waiting')return;if(message.t!=='paired')throw Error();started=true;
    // Buffer peer packets while WebCrypto computes keys, then drain in wire order.
    const pending:ArrayBuffer[]=[];socket.onmessage=e=>{if(!(e.data instanceof ArrayBuffer)||pending.length>=8)return this.fail('连接握手异常');pending.push(e.data);};
    const derived=await keys(local,message.hostKey,message.guestKey,role,this.room);
    if(epoch!==this.epoch)return socket.close();
    const channel=new EncryptedChannel(socket,new Cipher(derived.send,derived.receive),()=>{if(epoch===this.epoch)this.fail('加密校验失败，连接已结束');});
    this.patch({phase:'verifying',sas:derived.sas,message:'核对两端识别码'});
    this.session=new DataSession(channel,{
     ...this.events,
     ready:(ready,peer)=>{if(epoch!==this.epoch)return;this.patch({phase:ready?'ready':'verifying',peer,message:ready?'经小主机加密转发':'核对两端识别码'});},
     closed:()=>{if(epoch!==this.epoch)return;this.close(false);this.events.closed();}
    },this.label,this.device);
    for(const data of pending)socket.onmessage?.({data} as MessageEvent);
    if(this.timer)clearTimeout(this.timer);this.timer=setTimeout(()=>{if(epoch===this.epoch)this.fail('连接已满 10 分钟，请重新配对');},600000);
   }catch{if(epoch===this.epoch)this.fail('无法核对连接身份，请重新配对');}
  };
 }
 async create(){
  this.close(false);const epoch=this.epoch;this.patch({...initialPeer,phase:'making',message:'正在生成配对码'});
  try{const local=await identity(),room=await this.api({op:'create',key:local.publicKey});if(epoch!==this.epoch)return;
   this.room=room.code;this.token=room.token;this.patch({phase:'waiting',code:room.code,expires:room.expires,message:'在另一端输入配对码'});this.connect(local,'host',epoch);
   this.timer=setTimeout(()=>{if(epoch===this.epoch)this.fail('配对码已过期，请重新生成');},120000);
  }catch(e){if(epoch===this.epoch)this.fail(e instanceof Error?e.message:'无法生成配对码');}
 }
 async join(code:string){
  this.close(false);const epoch=this.epoch;this.patch({...initialPeer,phase:'joining',message:'正在连接小主机'});
  try{const local=await identity(),room=await this.api({op:'join',code,key:local.publicKey});if(epoch!==this.epoch)return;
   this.room=room.code;this.token=room.token;this.connect(local,'guest',epoch);this.timer=setTimeout(()=>{if(epoch===this.epoch)this.fail('连接超时，请重新生成配对码');},30000);
  }catch(e){if(epoch===this.epoch)this.fail(e instanceof Error?e.message:'连接未完成');}
 }
 confirm(){if(this.state.phase!=='verifying'||!this.session)return;this.patch({confirmed:true,message:'等待另一端确认'});this.session.confirm();}
 close(notify=true){
  this.epoch++;if(this.timer)clearTimeout(this.timer);this.timer=null;
  const session=this.session;this.session=null;session?.dispose();
  if(this.socket){this.socket.onclose=null;this.socket.onerror=null;this.socket.close();this.socket=null;}
  if(this.room&&this.token){void apiFetch('/api/pair',{method:'POST',headers:{Authorization:'Bearer '+this.token},body:JSON.stringify({op:'close',code:this.room}),keepalive:true}).catch(()=>{});}
  this.room='';this.token='';this.patch({...initialPeer,message:notify?'连接已结束':'连接后即可共享'});
 }
}
