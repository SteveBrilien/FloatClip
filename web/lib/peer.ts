import { DataSession, type SessionEvents, type Channel } from './session.ts';
import { verificationCode } from './protocol.ts';
import {apiFetch} from './auth.ts';
export type PeerState={phase:'idle'|'making'|'waiting'|'joining'|'verifying'|'ready'|'failed';message:string;code:string;expires:number;sas:string;peer:string;confirmed:boolean};
export const initialPeer:PeerState={phase:'idle',message:'连接后即可共享',code:'',expires:0,sas:'',peer:'',confirmed:false};
export class WebPeer {
 state={...initialPeer}; session:DataSession|null=null;private pc:RTCPeerConnection|null=null;private active=0;
 private token='';private room='';private timer:ReturnType<typeof setTimeout>|null=null;private lifetime:ReturnType<typeof setTimeout>|null=null;
 constructor(private label:string,private onState:(state:PeerState)=>void,private events:SessionEvents){}
 private patch(change:Partial<PeerState>){this.state={...this.state,...change};this.onState(this.state);}
 private async api(data:Record<string,unknown>,method='POST'){
  const controller=new AbortController(),timeout=setTimeout(()=>controller.abort(),15000);let result:Response;
  try{result=await apiFetch('/api/pair'+(method==='GET'?'?code='+this.room:''),{method,cache:'no-store',credentials:'same-origin',headers:{'Content-Type':'application/json',...(this.token?{Authorization:'Bearer '+this.token}:{})},body:method==='POST'?JSON.stringify(data):undefined,signal:controller.signal});}finally{clearTimeout(timeout);}

  let value:{error?:string;code:string;token:string;expires:number;offer:string;answer:string|null};try{value=await result.json() as typeof value;}catch{throw new Error('请在 Safari 或 Chrome 中打开，并登录后重试');}
  if(!result.ok)throw new Error(value.error||'配对服务暂不可用');return value;
 }
 private make(){
  if(typeof RTCPeerConnection==='undefined')throw new Error('当前浏览器不支持设备直连，请使用 Safari 或 Chrome');
  const pc=new RTCPeerConnection({iceServers:[{urls:'stun:stun.cloudflare.com:3478'}],bundlePolicy:'max-bundle'});this.pc=pc;const epoch=this.active;
  pc.onconnectionstatechange=()=>{if(epoch!==this.active)return;if(['closed','failed'].includes(pc.connectionState))this.fail('连接未建立或已结束，请确认同一 Wi-Fi 或热点');};
  return pc;
 }
 private async gather(pc:RTCPeerConnection){
  if(pc.iceGatheringState==='complete')return;
  await new Promise<void>((resolve,reject)=>{
   const timeout=setTimeout(()=>{clean();if(pc.localDescription?.sdp.includes('a=candidate:'))resolve();else reject(new Error('没有获取到可用地址，请检查 Wi-Fi 或热点'));},7000);
   const check=()=>{if(pc.iceGatheringState==='complete'){clean();resolve();}};
   const clean=()=>{clearTimeout(timeout);pc.removeEventListener('icegatheringstatechange',check);};
   pc.addEventListener('icegatheringstatechange',check);check();
  });
 }
 private channel(channel:RTCDataChannel,epoch:number){
  channel.binaryType='arraybuffer';const queued:unknown[]=[];channel.onmessage=e=>{if(queued.length>=8||(typeof e.data==='string'&&e.data.length>100000)||(e.data instanceof ArrayBuffer&&e.data.byteLength>16384)){channel.close();if(epoch===this.active)this.fail('连接握手异常，请重新配对');return;}queued.push(e.data);};
  channel.onopen=async()=>{
   if(epoch!==this.active){channel.close();return;}
   try{
    const pc=this.pc!;const sas=await verificationCode(pc.localDescription!.sdp,pc.remoteDescription!.sdp);
    if(epoch!==this.active){channel.close();return;}
    this.patch({phase:'verifying',sas,message:'核对两端识别码'});
    this.session=new DataSession(channel as unknown as Channel,{
     ...this.events,
     ready:(ready,peer)=>{if(epoch!==this.active)return;this.patch({peer,phase:ready?'ready':'verifying',message:ready?'已加密直连':this.state.confirmed?'等待另一端确认':'核对两端识别码'});if(ready&&this.lifetime===null)this.lifetime=setTimeout(()=>this.fail('连接已满 10 分钟，请重新配对'),600000);},
     closed:()=>{if(epoch!==this.active)return;this.close(false);this.events.closed();},
     error:message=>{if(epoch!==this.active)return;this.events.error(message);},
    },this.label);
    for(const data of queued)channel.onmessage?.({data} as MessageEvent);queued.length=0;
    if(this.timer)clearTimeout(this.timer);this.timer=null;void this.cleanup();
   }catch{this.fail('无法核对连接证书，请重新配对');}
  };
  channel.onerror=()=>{if(epoch===this.active)this.fail('传输连接不可用，请重新配对');};
 }
 private watch(epoch:number){
  if(['verifying','ready'].includes(this.state.phase))return;
  this.timer=setTimeout(()=>{if(epoch===this.active)this.fail('未能直连，请让两端连接同一 Wi-Fi 或手机热点');},35000);
 }
 async create(){
  this.close(false);const epoch=this.active;this.patch({...initialPeer,phase:'making',message:'正在生成配对码'});
  try{
   const pc=this.make();this.channel(pc.createDataChannel('floatclip-v1',{ordered:true}),epoch);
   await pc.setLocalDescription(await pc.createOffer());await this.gather(pc);if(epoch!==this.active)return;
   const room=await this.api({op:'create',sdp:pc.localDescription!.sdp});if(epoch!==this.active)return;
   this.room=room.code;this.token=room.token;this.patch({phase:'waiting',code:room.code,expires:room.expires,message:'在另一台设备输入此配对码'});
   const poll=async()=>{
    if(epoch!==this.active)return;
    try{if(Date.now()>=room.expires)throw new Error('配对码已过期，请重新生成');
     const state=await this.api({},'GET');if(epoch!==this.active)return;
     if(state.answer){await pc.setRemoteDescription({type:'answer',sdp:state.answer});if(epoch!==this.active)return;this.patch({phase:'joining',message:'正在建立设备直连'});this.watch(epoch);}
     else this.timer=setTimeout(poll,1800);
    }catch(e){if(epoch===this.active)this.fail(e instanceof Error?e.message:'配对失败');}
   };void poll();
  }catch(e){if(epoch===this.active)this.fail(e instanceof Error?e.message:'无法生成配对码');}
 }
 async join(code:string){
  this.close(false);const epoch=this.active;this.patch({...initialPeer,phase:'joining',message:'正在连接另一台设备'});
  try{
   const pc=this.make();pc.ondatachannel=e=>this.channel(e.channel,epoch);
   const room=await this.api({op:'join',code});if(epoch!==this.active)return;this.room=room.code;this.token=room.token;
   await pc.setRemoteDescription({type:'offer',sdp:room.offer});await pc.setLocalDescription(await pc.createAnswer());await this.gather(pc);if(epoch!==this.active)return;
   await this.api({op:'answer',code,sdp:pc.localDescription!.sdp});if(epoch!==this.active)return;this.watch(epoch);
  }catch(e){if(epoch===this.active)this.fail(e instanceof Error?e.message:'无法连接设备');}
 }
 confirm(){if(this.state.phase!=='verifying'||!this.session)return;this.patch({confirmed:true,message:'等待另一端确认'});this.session.confirm();}
 private async cleanup(){const code=this.room,token=this.token;this.room='';this.token='';if(!code||!token)return;try{await apiFetch('/api/pair',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json',Authorization:'Bearer '+token},body:JSON.stringify({op:'close',code}),keepalive:true});}catch{/* Expires logically after 120 seconds. */}}
 private fail(message:string){this.close(false);this.patch({phase:'failed',message});}
 close(notify=true){
  this.active++;if(this.timer)clearTimeout(this.timer);if(this.lifetime)clearTimeout(this.lifetime);this.timer=null;this.lifetime=null;void this.cleanup();
  const s=this.session;this.session=null;s?.dispose();if(this.pc){this.pc.onconnectionstatechange=null;this.pc.ondatachannel=null;this.pc.close();this.pc=null;}
  this.patch({...initialPeer,message:notify?'连接已结束':'连接后即可共享'});
 }
}
