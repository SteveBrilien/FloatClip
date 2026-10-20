const enc=new TextEncoder();
const subtle=crypto.subtle;
export const base64=(bytes:Uint8Array)=>btoa(String.fromCharCode(...bytes));
const decode=(value:string)=>Uint8Array.from(atob(value),c=>c.charCodeAt(0));
const hash=async(value:Uint8Array)=>new Uint8Array(await subtle.digest('SHA-256',value as BufferSource));
export type Identity={privateKey:CryptoKey;publicKey:string};
export async function identity():Promise<Identity>{
 const pair=await subtle.generateKey({name:'ECDH',namedCurve:'P-256'},false,['deriveBits']);
 return {privateKey:pair.privateKey,publicKey:base64(new Uint8Array(await subtle.exportKey('raw',pair.publicKey)))};
}
export async function keys(local:Identity,hostKey:string,guestKey:string,role:'host'|'guest',code:string,context=''){
 if(!(/^(?:\d{8}|[a-f0-9]{64})$/.test(code))||local.publicKey!==(role==='host'?hostKey:guestKey)||hostKey===guestKey)throw Error('连接身份无效');
 const remote=decode(role==='host'?guestKey:hostKey);
 if(remote.length!==65||remote[0]!==4)throw Error('连接身份无效');
 const publicKey=await subtle.importKey('raw',remote,{name:'ECDH',namedCurve:'P-256'},false,[]);
 const bits=await subtle.deriveBits({name:'ECDH',public:publicKey},local.privateKey,256);
 const material=await subtle.importKey('raw',bits,'HKDF',false,['deriveKey']);
 const transcript=await hash(enc.encode(`FloatClip-Relay-v1|${code}|${hostKey}|${guestKey}${context?'|'+context:''}`));
 const key=(direction:string)=>subtle.deriveKey({name:'HKDF',hash:'SHA-256',salt:transcript as BufferSource,info:enc.encode(direction)},material,{name:'AES-GCM',length:256},false,['encrypt','decrypt']);
 const h=await key('host-to-guest'),g=await key('guest-to-host');
 // The comparison binds the ECDH secret as well as both public keys and the room.
 const proof=new Uint8Array([...new Uint8Array(bits),...transcript]);
 const fingerprint=await hash(proof),sas=(new DataView(fingerprint.buffer).getUint32(0)%1000000).toString().padStart(6,'0');
 const resume=await subtle.deriveKey({name:'HKDF',hash:'SHA-256',salt:transcript as BufferSource,info:enc.encode('FloatClip/resume/root/v1')},material,{name:'HMAC',hash:'SHA-256',length:256},false,['sign','verify']);
 const tag=Array.from(new Uint8Array(await subtle.sign('HMAC',resume,enc.encode('FloatClip/resume/tag/v1'))),b=>b.toString(16).padStart(2,'0')).join('');
 return {send:role==='host'?h:g,receive:role==='host'?g:h,sas,resume,tag};
}
export class Cipher {
 private outgoing=0n;private incoming=0n;
 constructor(private sendKey:CryptoKey,private receiveKey:CryptoKey){}
 private nonce(sequence:bigint){const nonce=new Uint8Array(12);new DataView(nonce.buffer).setBigUint64(4,sequence);return nonce;}
 async encrypt(value:string|ArrayBuffer){
  const raw=typeof value==='string'?enc.encode(value):new Uint8Array(value);
  if(raw.length>32768||this.outgoing>=2n**63n)throw Error('消息过大');
  const sequence=this.outgoing++,header=new Uint8Array(10);header[0]=1;header[1]=typeof value==='string'?1:2;
  new DataView(header.buffer).setBigUint64(2,sequence);
  const encrypted=await subtle.encrypt({name:'AES-GCM',iv:this.nonce(sequence),additionalData:header,tagLength:128},this.sendKey,raw as BufferSource);
  const packet=new Uint8Array(header.length+encrypted.byteLength);packet.set(header);packet.set(new Uint8Array(encrypted),10);return packet.buffer;
 }
 async decrypt(packet:ArrayBuffer):Promise<string|ArrayBuffer>{
  const bytes=new Uint8Array(packet);if(bytes.length<26||bytes.length>32794||bytes[0]!==1||![1,2].includes(bytes[1]))throw Error('加密消息无效');
  const sequence=new DataView(packet).getBigUint64(2);if(sequence!==this.incoming)throw Error('消息次序无效');
  const raw=await subtle.decrypt({name:'AES-GCM',iv:this.nonce(sequence),additionalData:bytes.slice(0,10),tagLength:128},this.receiveKey,bytes.slice(10));
  this.incoming++;return bytes[1]===1?new TextDecoder('utf-8',{fatal:true}).decode(raw):raw;
 }
}

export async function resumeProof(key:CryptoKey,tag:string,nonce:string,host:string,guest:string,role:'host'|'guest'){
 if(!/^[a-f0-9]{64}$/.test(tag)||!/^[a-f0-9]{32}$/.test(nonce)||host===guest)throw Error('恢复握手无效');
 return base64(new Uint8Array(await subtle.sign('HMAC',key,enc.encode(`FloatClip/resume/proof/v1|${tag}|${nonce}|${host}|${guest}|${role}`))));
}
export async function verifyResumeProof(key:CryptoKey,value:string,tag:string,nonce:string,host:string,guest:string,role:'host'|'guest'){
 if(!/^[A-Za-z0-9+/]{43}=$/.test(value))return false;
 await resumeProof(key,tag,nonce,host,guest,role);
 // WebCrypto verifies the MAC in constant time; no key extraction is needed.
 return subtle.verify('HMAC',key,decode(value),enc.encode(`FloatClip/resume/proof/v1|${tag}|${nonce}|${host}|${guest}|${role}`));
}
