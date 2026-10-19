/** Shared clipboard model. A source is provenance, not a remembered trust grant. */
export type Device = {id:string;name:string;platform:'android'|'windows'|'ipad'|'web'};
export type Clip = {id:string;text:string;pinned:boolean;category:string;createdAt:number;updatedAt:number;source:Device};
export type VaultSnapshot = {revision:string;entries:Clip[]};
export const MAX_CLIPS=500, MAX_VAULT_BYTES=16*1024*1024;
const platforms=new Set(['android','windows','ipad','web']);
export function validDevice(value:unknown,fallback?:Device):Device {
 const d=value as Partial<Device>|null;
 if(!d||typeof d.id!=='string'||!/^[-a-zA-Z0-9_:]{1,100}$/.test(d.id)||typeof d.name!=='string'||!d.name.trim()||d.name.length>80||!platforms.has(d.platform!)){
  if(fallback)return {...fallback};throw Error('设备来源信息无效');
 }
 return {id:d.id,name:d.name,platform:d.platform!};
}
export function normalizeClips(value:unknown,local:Device):Clip[]{
 if(!Array.isArray(value)||value.length>MAX_CLIPS)throw Error('剪贴板备份最多 500 条');
 const ids=new Set<string>();
 return value.map(raw=>{
  if(!raw||typeof raw!=='object'||typeof raw.text!=='string'||!raw.text.trim()||raw.text.length>1000000)throw Error('剪贴板记录格式无效');
  const id=String(raw.key??raw.id??'');
  if(!/^[-a-zA-Z0-9_:]{1,128}$/.test(id)||ids.has(id))throw Error('剪贴板记录编号重复或无效');ids.add(id);
  const createdAt=Number(raw.createdAt),updatedAt=Number(raw.updatedAt??raw.createdAt);
  if(!Number.isSafeInteger(createdAt)||createdAt<0||!Number.isSafeInteger(updatedAt)||updatedAt<0)throw Error('剪贴板时间无效');
  const category=raw.category??'未分类';if(typeof category!=='string'||category.length>80)throw Error('分类名称过长');
  const source=raw.source?validDevice(raw.source):{...local};if(source.id==='local')Object.assign(source,local);
  return {id,text:raw.text,pinned:raw.pinned===true,category:category.trim()||'未分类',createdAt,updatedAt,source};
 });
}
export function ordered(entries:Clip[]):Clip[]{return [...entries].sort((a,b)=>Number(b.pinned)-Number(a.pinned)||b.updatedAt-a.updatedAt||a.id.localeCompare(b.id));}
export function putClip(entries:Clip[],text:string,source:Device,id:string=crypto.randomUUID()):Clip[]{
 if(!text.trim())throw Error('请输入要保存的文字');
 const duplicate=entries.find(c=>c.source.id===source.id&&c.text===text);
 const now=Date.now(),key=entries.some(c=>c.id===id)?crypto.randomUUID():id;
 const entry:Clip=duplicate?{...duplicate,updatedAt:now,source:{...source}}:{id:key,text,pinned:false,category:'未分类',createdAt:now,updatedAt:now,source:{...source}};
 let next=ordered([entry,...entries.filter(c=>c.id!==entry.id)]);
 if(next.length>MAX_CLIPS){const removable=[...next].reverse().find(c=>!c.pinned&&c.id!==entry.id);if(!removable)throw Error('剪贴板已满，请先取消置顶或删除记录');next=next.filter(c=>c.id!==removable.id);}
 return next;
}
export function mergeClips(current:Clip[],incoming:Clip[]):Clip[]{
 const map=new Map(current.map(c=>[c.id,c]));
 for(const c of incoming){const old=map.get(c.id);if(old&&old.source.id!==c.source.id)throw Error('备份中存在冲突的设备来源，请保留原备份');if(!old||c.updatedAt>old.updatedAt)map.set(c.id,c);}
 if(map.size>MAX_CLIPS)throw Error('合并后超过 500 条，请先整理剪贴板');return ordered([...map.values()]);
}
export function parseBackup(text:string,local:Device):Clip[]{
 if(new TextEncoder().encode(text).length>MAX_VAULT_BYTES)throw Error('备份最大 16 MiB');
 const value=JSON.parse(text);if(Array.isArray(value))return normalizeClips(value,local);
 if(value?.format!=='FloatClip.clipboard'||value?.schema!==1)throw Error('备份格式不受支持');return normalizeClips(value.entries,local);
}
export function deviceGroups(entries:Clip[],local:Device):{device:Device;count:number}[]{
 const groups=new Map<string,{device:Device;count:number;updated:number}>([[local.id,{device:local,count:0,updated:Infinity}]]);
 for(const c of entries){let g=groups.get(c.source.id);if(!g){g={device:c.source,count:0,updated:-1};groups.set(c.source.id,g);}g.count++;if(c.source.id!==local.id&&c.updatedAt>g.updated){g.device=c.source;g.updated=c.updatedAt;}}
 return [...groups.values()].sort((a,b)=>a.device.id===local.id?-1:b.device.id===local.id?1:b.updated-a.updated).map(({device,count})=>({device,count}));
}
