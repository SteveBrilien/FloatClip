import type {Device,VaultSnapshot} from './clipboard.ts';
export type HostInfo={bridge:number;version:string;device:Device;platform:'android'|'windows';legacy:boolean;theme?:string;systemDark?:boolean};
type Reply={id:string;ok:boolean;data?:unknown;error?:string};
type WebView={postMessage:(v:unknown)=>void;addEventListener:(t:string,f:(e:{data:Reply})=>void)=>void};
declare global {interface Window {chrome?:{webview?:WebView}}}
const pending=new Map<string,{resolve:(v:any)=>void;reject:(e:Error)=>void;timer:ReturnType<typeof setTimeout>}>();
let listening=false;
export function hasNative(){return /FloatClipNative\/1/.test(navigator.userAgent);}
export async function nativeCall<T=unknown>(op:string,args:Record<string,unknown>={}):Promise<T>{
 if(location.origin!=='https://clip.wmy-cloud.cn'||!hasNative())throw Error('此功能需要 FloatClip 原生应用');
 const request={id:crypto.randomUUID(),op,args};
 const wv=window.chrome?.webview;
 if(wv){
  if(!listening){wv.addEventListener('message',e=>{const r=e.data;if(!r||typeof r.id!=='string')return;const p=pending.get(r.id);if(!p)return;clearTimeout(p.timer);pending.delete(r.id);r.ok?p.resolve(r.data):p.reject(Error(r.error||'本机操作未完成'));});listening=true;}
  return new Promise<T>((resolve,reject)=>{const timer=setTimeout(()=>{pending.delete(request.id);reject(Error('本机操作超时，请重试'));},120000);pending.set(request.id,{resolve,reject,timer});try{wv.postMessage(request);}catch(e){clearTimeout(timer);pending.delete(request.id);reject(e);}});
 }
 const raw=window.prompt('FloatClip/Native/v1:'+JSON.stringify(request));
 if(!raw)throw Error('本机操作未完成');const reply=JSON.parse(raw) as Reply;
 if(reply.id!==request.id||!reply.ok)throw Error(reply.error||'本机操作未完成');return reply.data as T;
}
export const nativeInfo=()=>nativeCall<HostInfo>('info');
export async function readClipboard(){return hasNative()?nativeCall<string>('clipboard.read'):navigator.clipboard.readText();}
export async function writeClipboard(text:string){if(hasNative())await nativeCall('clipboard.write',{text});else await navigator.clipboard.writeText(text);}
export async function saveBlob(blob:Blob,name:string){
 if(!hasNative()){const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),60000);return;}
 const {token}=await nativeCall<{token:string}>('file.begin',{name,size:blob.size});
 try{
  for(let offset=0;offset<blob.size;offset+=16384){const bytes=new Uint8Array(await blob.slice(offset,offset+16384).arrayBuffer());let value='';for(const n of bytes)value+=String.fromCharCode(n);await nativeCall('file.chunk',{token,offset,base64:btoa(value)});}
  await nativeCall('file.finish',{token});
 }catch(e){void nativeCall('file.cancel',{token}).catch(()=>{});throw e;}
}
export const nativeVault={load:()=>nativeCall<VaultSnapshot>('vault.load'),commit:(revision:string,entries:unknown)=>nativeCall<VaultSnapshot>('vault.commit',{revision,entries})};
