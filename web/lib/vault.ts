import {MAX_VAULT_BYTES,normalizeClips,type Clip,type Device,type VaultSnapshot} from './clipboard.ts';
import {hasNative,nativeVault} from './native.ts';
type Stored={revision:string;iv:Uint8Array;cipher:ArrayBuffer};
const encoder=new TextEncoder();
function request<T>(r:IDBRequest<T>){return new Promise<T>((resolve,reject)=>{r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error||Error('无法访问本机剪贴板'));});}
function finished(t:IDBTransaction){return new Promise<void>((resolve,reject)=>{t.oncomplete=()=>resolve();t.onabort=()=>reject(t.error||Error('本机剪贴板写入未完成'));t.onerror=()=>reject(t.error||Error('本机剪贴板写入未完成'));});}
let database:Promise<IDBDatabase>|null=null;
function open(){return database??=new Promise((resolve,reject)=>{const r=indexedDB.open('floatclip-clipboard-v1',1);r.onupgradeneeded=()=>{r.result.createObjectStore('keys');r.result.createObjectStore('vaults');};r.onsuccess=()=>{r.result.onversionchange=()=>{r.result.close();database=null;};resolve(r.result);};r.onerror=()=>{database=null;reject(Error('浏览器禁止本机存储，请使用常规浏览模式'));};r.onblocked=()=>reject(Error('请关闭旧的 FloatClip 页面后重试'));});}
export class Vault {
 private key:CryptoKey|null=null;
 constructor(private account:string,private device:Device){}
 private async getKey(){
  if(this.key)return this.key;
  const db=await open(),candidate=await crypto.subtle.generateKey({name:'AES-GCM',length:256},false,['encrypt','decrypt']);
  const t=db.transaction('keys','readwrite'),done=finished(t),store=t.objectStore('keys');
  const key=await request(store.get(this.account)) as CryptoKey|undefined;
  if(!key)store.put(candidate,this.account);await done;this.key=key??candidate;return this.key;
 }
 async load():Promise<VaultSnapshot>{
  if(hasNative()){const snap=await nativeVault.load();return {revision:snap.revision,entries:normalizeClips(snap.entries,this.device)};}
  const db=await open(),key=await this.getKey(),t=db.transaction('vaults','readonly');
  const stored=await request(t.objectStore('vaults').get(this.account)) as Stored|undefined;
  if(!stored)return {revision:'0',entries:[]};
  try{const plain=await crypto.subtle.decrypt({name:'AES-GCM',iv:stored.iv as BufferSource,additionalData:encoder.encode('FloatClip/vault/v1|'+this.account)},key,stored.cipher);return {revision:stored.revision,entries:normalizeClips(JSON.parse(new TextDecoder().decode(plain)),this.device)};}
  catch{throw Error('本机剪贴板无法解密，请保留当前浏览器数据并从备份恢复');}
 }
 async commit(revision:string,entries:Clip[]):Promise<VaultSnapshot>{
  entries=normalizeClips(entries,this.device);const raw=encoder.encode(JSON.stringify(entries));if(raw.length>MAX_VAULT_BYTES)throw Error('剪贴板库超过 16 MiB，请先导出并整理');
  if(hasNative()){const snap=await nativeVault.commit(revision,entries);return {revision:snap.revision,entries:normalizeClips(snap.entries,this.device)};}
  const key=await this.getKey(),iv=crypto.getRandomValues(new Uint8Array(12)),cipher=await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:encoder.encode('FloatClip/vault/v1|'+this.account)},key,raw);
  const db=await open(),t=db.transaction('vaults','readwrite'),done=finished(t),store=t.objectStore('vaults');
  const current=await request(store.get(this.account)) as Stored|undefined;
  if((current?.revision??'0')!==revision){await done;throw Error('VAULT_CONFLICT');}
  const next=crypto.randomUUID();store.put({revision:next,iv,cipher},this.account);await done;return {revision:next,entries};
 }
}
