import {validDevice,type Device} from './clipboard.ts';
export type TrustedPair={tag:string;token:string;role:'host'|'guest';root:CryptoKey;peer:Device;scope:string;updated:number};
export interface TrustRepository {all():Promise<TrustedPair[]>;put(record:TrustedPair):Promise<void>;remove(tag:string):Promise<void>}
export class BrowserTrustStore implements TrustRepository {
 constructor(readonly scope:string){}
 private open(){return new Promise<IDBDatabase>((resolve,reject)=>{const r=indexedDB.open('floatclip-trusted-v1',1);r.onupgradeneeded=()=>r.result.createObjectStore('pairs');r.onerror=()=>reject(Error('浏览器未允许保存配对'));r.onsuccess=()=>resolve(r.result);});}
 private valid(r:TrustedPair){if(r.scope!==this.scope||!/^[a-f0-9]{64}$/.test(r.tag)||!/^[a-f0-9]{64}$/.test(r.token)||!['host','guest'].includes(r.role)||r.root?.type!=='secret'||r.root.algorithm.name!=='HMAC'||r.root.extractable)throw Error('设备授权数据无效');validDevice(r.peer);return r;}
 async all(){const db=await this.open();try{return await new Promise<TrustedPair[]>((resolve,reject)=>{const r=db.transaction('pairs').objectStore('pairs').getAll();r.onerror=()=>reject(r.error);r.onsuccess=()=>{try{resolve(r.result.filter((r:TrustedPair)=>r.scope===this.scope).map(r=>this.valid(r)));}catch(e){reject(e);}};});}finally{db.close();}}
 async put(record:TrustedPair){this.valid(record);if((await this.all()).filter(r=>r.tag!==record.tag).length>=8)throw Error('最多记住 8 台设备');await this.change(store=>store.put(record,this.scope+'|'+record.tag));}
 async remove(tag:string){await this.change(store=>store.delete(this.scope+'|'+tag));}
 private async change(action:(store:IDBObjectStore)=>void){const db=await this.open();try{await new Promise<void>((resolve,reject)=>{const t=db.transaction('pairs','readwrite');t.oncomplete=()=>resolve();t.onabort=()=>reject(t.error||Error('配对授权未保存'));t.onerror=()=>reject(t.error);action(t.objectStore('pairs'));});}finally{db.close();}}
}
