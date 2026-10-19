/** FloatClip Web v1. Ordered, reliable data channel; one file per session at a time. */
export const MAX_TEXT = 64 * 1024;
export const MAX_FILE = 64 * 1024 * 1024;
export const CHUNK = 16 * 1024;
export const RATE = 1024 * 1024;
export const encoder = new TextEncoder();
export type Control = { t: string; id?: string; text?: string; name?: string; size?: number; hash?: string; label?: string; version?: number; part?: number; total?: number; deviceId?:string; platform?:string };
export function filename(value: unknown): string {
  if (typeof value !== 'string') throw new Error('文件名称无效');
  const clean = value.replace(/[\\/\u0000-\u001f\u007f]/g, '_').replace(/[\u202a-\u202e\u2066-\u2069]/g, '').trim();
  if (!clean || clean==='.' || clean==='..' || encoder.encode(clean).length>240) throw new Error('文件名称过长或无效');
  return clean;
}
export function parseControl(input: string): Control {
  if (encoder.encode(input).length > 100000) throw new Error('消息过大');
  const m = JSON.parse(input) as Control;
  if (!m || typeof m !== 'object' || !['hello','verified','text','ack','offer','accept','reject','end','cancel'].includes(m.t)) throw new Error('消息格式不受支持');
  if (['text','ack','offer','accept','reject','end','cancel'].includes(m.t) && (typeof m.id!=='string' || !/^[a-zA-Z0-9-]{1,64}$/.test(m.id))) throw new Error('消息编号无效');
  if (m.t==='hello' && (m.version!==1 || typeof m.label!=='string' || m.label.length>40)) throw new Error('设备版本不兼容');
  if(m.t==='hello' && ((m.deviceId!==undefined&&(typeof m.deviceId!=='string'||!/^[-a-zA-Z0-9_:]{1,100}$/.test(m.deviceId))) || (m.platform!==undefined&&!['android','windows','ipad','web'].includes(m.platform))))throw Error('设备来源信息无效');
  if (m.t==='text' && (typeof m.text!=='string' || encoder.encode(m.text).length>8192 || !Number.isInteger(m.part) || !Number.isInteger(m.total) || m.total!<1 || m.total!>32 || m.part!<0 || m.part!>=m.total!)) throw new Error('文字分片无效');
  if (m.t==='offer') {
    m.name=filename(m.name);
    if (!Number.isSafeInteger(m.size) || m.size!<0 || m.size!>MAX_FILE || typeof m.hash!=='string' || !/^[a-f0-9]{64}$/.test(m.hash)) throw new Error('文件信息无效或超过 64 MiB');
  }
  return m;
}
export async function sha256(data: BufferSource): Promise<string> {
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', data)), b=>b.toString(16).padStart(2,'0')).join('');
}
export function fingerprint(sdp: string): string {
  const all = Array.from(sdp.matchAll(/^a=fingerprint:sha-256 ([a-f0-9:]+)\r?$/gmi), m=>m[1].toUpperCase());
  if (!all.length || all.some(x=>!/^([A-F0-9]{2}:){31}[A-F0-9]{2}$/.test(x)) || new Set(all).size!==1) throw new Error('连接证书信息无效');
  return all[0];
}
export async function verificationCode(local: string, remote: string): Promise<string> {
  const hash=await sha256(encoder.encode('FloatClip/Web/v1\n'+[fingerprint(local),fingerprint(remote)].sort().join('\n')));
  return (Number.parseInt(hash.slice(0,12),16)%1000000).toString().padStart(6,'0');
}
export function validateSdp(value: unknown, type: 'offer'|'answer'): string {
  if (typeof value!=='string' || value.length>65000 || !value.startsWith('v=0') || !/^m=application /m.test(value) || /^m=(audio|video) /m.test(value)) throw new Error('配对信息无效');
  fingerprint(value);
  if (type==='offer' && !/^a=setup:actpass\r?$/m.test(value)) throw new Error('配对邀请无效');
  return value;
}
export function nextSize(received: number, length: number, expected: number): number {
  if (!Number.isSafeInteger(length) || length<=0 || length>CHUNK || received+length>expected) throw new Error('接收数据长度异常');
  return received+length;
}
export function size(n: number): string {
  if(n<1024) return `${n} B`;
  if(n<1024*1024) return `${(n/1024).toFixed(1)} KiB`;
  return `${(n/1024/1024).toFixed(1)} MiB`;
}
