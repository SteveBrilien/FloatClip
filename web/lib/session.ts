import type {Device} from './clipboard.ts';
import { MAX_FILE,MAX_TEXT,CHUNK,RATE,encoder,parseControl,sha256,filename,nextSize,type Control } from './protocol.ts';
export interface Channel {
 readyState:string; bufferedAmount:number; binaryType:string;
 send(data:string|ArrayBuffer):void; close():void;
 onmessage:((event:{data:unknown})=>void)|null; onclose:(()=>void)|null;
}
export type SharedItem={id:string;kind:'text'|'file';direction:'sent'|'received';text?:string;name?:string;blob?:Blob;bytes?:number;source?:Device};
export type SessionEvents={ready:(ready:boolean,peer:string)=>void;item:(item:SharedItem)=>unknown;offer:(offer:Control|null)=>void;progress:(label:string,percent:number|null)=>void;error:(message:string)=>void;closed:()=>void};
type Out={id:string;file:File;hash:string;phase:string};
type Incoming={id:string;name:string;size:number;hash:string;chunks:ArrayBuffer[];received:number;accepted:boolean};
export class DataSession {
 private local=false;private remote=false;private hello=false;private dead=false;private peer='另一台设备';
 private sending=true;private receiving=true;private receiveEpoch=crypto.randomUUID();private remoteEpoch='';private remoteSend=true;private remoteReceive=true;private syncPeer=false;
 private peerDevice:Device={id:'legacy-'+crypto.randomUUID(),name:'另一台设备',platform:'web'};
 private async emit(item:SharedItem){await this.events.item({...item,source:item.direction==='received'?this.peerDevice:this.device??{id:'local',name:'本机',platform:'web'}});}
 private out:Out|null=null;private incoming:Incoming|null=null;private chain=Promise.resolve();
 private textIn:{id:string;parts:string[];total:number;bytes:number}|null=null;
 private pending=new Map<string,SharedItem>();private received=new Set<string>();
 private timer:ReturnType<typeof setTimeout>|null=null;
 constructor(private channel:Channel,private events:SessionEvents,label:string,private device?:Device,private trusted?:Device){
  channel.binaryType='arraybuffer';
  channel.onmessage=e=>{this.chain=this.chain.then(()=>this.read(e.data)).catch(()=>this.fail('传输校验失败，连接已结束'));};
  channel.onclose=()=>this.dispose();
  this.send({t:'hello',version:1,label:label.slice(0,40),deviceId:device?.id,platform:device?.platform,sync:1});
 }
 configure(sending:boolean,receiving:boolean){if(this.receiving!==receiving){this.receiveEpoch=crypto.randomUUID();this.textIn=null;}this.sending=sending;this.receiving=receiving;if(!sending)this.pending.clear();if(this.hello&&this.syncPeer)this.sendPolicy();}
 private sendPolicy(){this.send({t:'policy',epoch:this.receiveEpoch,sending:this.sending,receiving:this.receiving});}
 get remoteDevice(){return {...this.peerDevice};}
 get ready(){return this.local&&this.remote&&this.hello&&(!this.syncPeer||!!this.remoteEpoch)&&!this.dead;}
 confirm(){if(this.dead)return;this.local=true;this.send({t:'verified'});this.notifyReady();}
 private notifyReady(){this.events.ready(this.ready,this.peer);}
 private send(value:Control){if(this.dead||this.channel.readyState!=='open')throw new Error('连接已断开');const raw=JSON.stringify(value);if(encoder.encode(raw).length>32768)throw new Error('消息过大');this.channel.send(raw);}
 private deadline(ms=60000){if(this.timer)clearTimeout(this.timer);this.timer=setTimeout(()=>this.fail('等待超时，请重新连接'),ms);}
 private finishTransfer(){if(this.timer)clearTimeout(this.timer);this.timer=null;this.events.offer(null);this.events.progress('',null);}
 sendText(value:string){
  if(!this.ready)throw new Error('请先核对两端识别码');if(!this.sending||!this.remoteReceive)throw Error('发送或对方接收已暂停');if(this.syncPeer&&!this.remoteEpoch)throw Error('正在核对共享开关');if(!value.trim()||encoder.encode(value).length>MAX_TEXT)throw new Error('请输入文字，最多 64 KiB');
  const id=crypto.randomUUID(),chars=Array.from(value),total=Math.ceil(chars.length/2048);
  if(this.pending.size>=20)throw new Error('请等待之前的文字送达');this.pending.set(id,{id,kind:'text',direction:'sent',text:value});
  for(let part=0;part<total;part++)this.send({t:'text',id,text:chars.slice(part*2048,(part+1)*2048).join(''),part,total,epoch:this.syncPeer?this.remoteEpoch:undefined});
 }
 async offerFile(file:File){
  if(!this.ready)throw new Error('请先连接设备');if(this.out||this.incoming)throw new Error('请先完成当前文件传输');
  filename(file.name);if(file.size>MAX_FILE)throw new Error('单个文件最大 64 MiB');
  const out:Out={id:crypto.randomUUID(),file,hash:'',phase:'hashing'};this.out=out;this.events.progress('正在准备文件',null);this.deadline();
  try{out.hash=await sha256(await file.arrayBuffer());if(this.dead||this.out!==out)return;out.phase='offered';this.send({t:'offer',id:out.id,name:file.name,size:file.size,hash:out.hash});this.events.progress('等待对方接收',null);}
  catch(e){if(this.out===out){this.out=null;this.finishTransfer();}throw e;}
 }
 acceptFile(){const f=this.incoming;if(!this.ready||!f||f.accepted)return;f.accepted=true;this.send({t:'accept',id:f.id});this.events.offer(null);this.events.progress('正在接收 '+f.name,0);this.deadline(45000);}
 rejectFile(){const f=this.incoming;if(!f)return;this.send({t:'reject',id:f.id});this.incoming=null;this.finishTransfer();}
 private async read(data:unknown){
  if(this.dead)return;
  if(typeof data!=='string'){
   if(!this.ready)throw new Error('Unverified');const f=this.incoming;
   if(!(data instanceof ArrayBuffer)||!f?.accepted)throw new Error('Unexpected binary');
   f.received=nextSize(f.received,data.byteLength,f.size);f.chunks.push(data);this.deadline(45000);this.events.progress('正在接收 '+f.name,f.size?f.received/f.size:1);return;
  }
  const m=parseControl(data);
  if(m.t==='hello'){if(this.hello)throw new Error('Duplicate hello');if(this.trusted&&(m.deviceId!==this.trusted.id||m.platform!==this.trusted.platform))throw Error('设备身份已变化，请重新配对');this.hello=true;this.syncPeer=m.sync===1;this.peer=m.label!;this.peerDevice={id:m.deviceId??this.peerDevice.id,name:this.peer,platform:(m.platform??'web') as Device['platform']};if(this.syncPeer)this.sendPolicy();this.notifyReady();return;}
  if(m.t==='policy'){if(!this.hello||!this.syncPeer)throw Error('权限协商无效');if(this.remoteEpoch&&this.remoteEpoch!==m.epoch)this.pending.clear();this.remoteEpoch=m.epoch!;this.remoteSend=m.sending!;this.remoteReceive=m.receiving!;this.notifyReady();return;}
  if(m.t==='verified'){this.remote=true;this.notifyReady();return;}
  if(!this.ready)throw new Error('Unverified');
  const id=m.id!;
  if(m.t==='text'){
   if(!this.receiving||!this.remoteSend||this.syncPeer&&m.epoch!==this.receiveEpoch)return;const consent=this.receiveEpoch;
   if(this.received.has(id)){this.send({t:'ack',id});return;}
   if(!this.textIn){if(m.part!==0)throw new Error('Out of order');this.textIn={id,parts:[],total:m.total!,bytes:0};}
   const t=this.textIn;if(t.id!==id||t.total!==m.total||t.parts.length!==m.part)throw new Error('Invalid text sequence');
   t.bytes+=encoder.encode(m.text!).length;if(t.bytes>MAX_TEXT)throw new Error('Text limit');t.parts.push(m.text!);
   if(t.parts.length===t.total){const text=t.parts.join('');if(!text.trim())throw new Error('Empty text');this.textIn=null;await this.emit({id,kind:'text',direction:'received',text});if(this.dead||!this.receiving||consent!==this.receiveEpoch)return;this.received.add(id);if(this.received.size>100)this.received.delete(this.received.values().next().value!);this.send({t:'ack',id});}return;
  }
  if(m.t==='ack'){
   const item=this.pending.get(id);if(item){this.pending.delete(id);await this.emit(item);}
   if(this.out?.id===id&&this.out.phase==='ack'){const f=this.out;this.out=null;this.finishTransfer();await this.emit({id,kind:'file',direction:'sent',name:f.file.name,bytes:f.file.size});}return;
  }
  if(m.t==='offer'){
   if(this.out||this.incoming){this.send({t:'reject',id});return;}
   this.incoming={id,name:m.name!,size:m.size!,hash:m.hash!,chunks:[],received:0,accepted:false};this.events.offer(m);this.deadline();return;
  }
  if(m.t==='accept'){
   if(this.out?.id!==id||this.out.phase!=='offered')throw new Error('Invalid file acceptance');this.out.phase='sending';void this.pump(this.out).catch(()=>this.fail('文件未送达，请重新连接后再试'));return;
  }
  if(m.t==='reject'){
   if(this.out?.id===id){this.out=null;this.finishTransfer();this.events.progress('对方暂未接收此文件',null);}return;
  }
  if(m.t==='end'){
   const f=this.incoming;if(!f?.accepted||f.id!==id||f.received!==f.size)throw new Error('Incomplete file');
   this.events.progress('正在校验 '+f.name,1);const blob=new Blob(f.chunks,{type:'application/octet-stream'});
   if(await sha256(await blob.arrayBuffer())!==f.hash)throw new Error('Hash mismatch');
   if(this.dead)return;this.incoming=null;this.finishTransfer();this.send({t:'ack',id});await this.emit({id,kind:'file',direction:'received',name:f.name,bytes:f.size,blob});return;
  }
  if(m.t==='cancel')this.dispose();
 }
 private async pump(out:Out){
  const start=performance.now();let offset=0;
  while(offset<out.file.size){
   if(this.dead||this.out!==out)throw new Error('Closed');
   while(this.channel.bufferedAmount>256*1024){if(this.dead)throw new Error('Closed');await new Promise(r=>setTimeout(r,20));}
   const data=await out.file.slice(offset,offset+CHUNK).arrayBuffer();if(this.dead)throw new Error('Closed');this.channel.send(data);offset+=data.byteLength;
   this.deadline(45000);this.events.progress('正在发送 '+out.file.name,offset/out.file.size);
   const pause=offset/RATE*1000-(performance.now()-start);if(pause>0)await new Promise(r=>setTimeout(r,pause));
  }
  if(this.dead)return;out.phase='ack';this.send({t:'end',id:out.id});this.events.progress('等待对方完成校验',1);this.deadline(45000);
 }
 private fail(message:string){if(this.dead)return;this.events.error(message);this.dispose();}
 dispose(){if(this.dead)return;this.dead=true;if(this.timer)clearTimeout(this.timer);this.pending.clear();this.textIn=null;this.incoming=null;this.out=null;this.channel.onmessage=null;this.channel.onclose=null;this.channel.close();this.events.offer(null);this.events.closed();}
}
