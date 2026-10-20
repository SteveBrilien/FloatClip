import {useEffect,useRef,useState,type CSSProperties} from 'react';
import {Pin,PinOff,Trash2,Monitor,Smartphone,Tablet} from 'lucide-react';
import type {Clip} from '../lib/clipboard';

type Props={clip:Clip;localId?:string;open:boolean;pending:boolean;reveal:(id:string)=>void;copy:()=>void;pin:()=>void;remove:()=>void;menu:()=>void};
export default function ClipboardRow({clip,localId,open,pending,reveal,copy,pin,remove,menu}:Props){
 const [drag,setDrag]=useState<number|null>(null);
 const gesture=useRef<{id:number;x:number;y:number;start:number;axis:''|'x'|'y';offset:number;held:boolean}|null>(null);
 const hold=useRef<ReturnType<typeof setTimeout>|null>(null),blocked=useRef(0);
 const front=useRef<HTMLButtonElement>(null),keyboardReveal=useRef(false);
 const clear=()=>{if(hold.current)clearTimeout(hold.current);hold.current=null;};
 useEffect(()=>()=>clear(),[]);
 useEffect(()=>{if(open&&keyboardReveal.current){keyboardReveal.current=false;front.current?.closest('.clip-row')?.querySelector<HTMLButtonElement>('.clip-swipe-actions button')?.focus();}},[open]);
 const isLocal=clip.source.id===localId,Device=clip.source.platform==='windows'?Monitor:clip.source.platform==='ipad'?Tablet:Smartphone;
 const label=(isLocal?'本机':clip.source.name)+(clip.pinned?' · 已置顶':'');
 const finish=(e:React.PointerEvent<HTMLButtonElement>,cancel=false)=>{
  const g=gesture.current;if(!g||g.id!==e.pointerId)return;clear();
  if(g.axis==='x'){blocked.current=Date.now()+450;reveal((cancel?g.start<0:g.offset < -52)?clip.id:'');e.preventDefault();}
  else if(g.held||g.axis==='y')blocked.current=Date.now()+450;
  setDrag(null);gesture.current=null;if(e.currentTarget.hasPointerCapture(e.pointerId))e.currentTarget.releasePointerCapture(e.pointerId);
 };
 return <article className={'clip-row platform-'+clip.source.platform+(drag!==null?' clip-dragging':'')} style={{'--clip-offset':(drag??(open?-148:0))+'px'} as CSSProperties} onContextMenu={e=>{e.preventDefault();clear();blocked.current=Date.now()+450;menu();}}>
  <div className="clip-swipe-actions" aria-hidden={!open&&drag===null}><button type="button" disabled={!open||pending} onClick={pin}>{clip.pinned?<PinOff size={24}/>:<Pin size={24}/>}<span>{clip.pinned?'取消置顶':'置顶'}</span></button><button type="button" className="delete" disabled={!open||pending} onClick={remove}><Trash2 size={24}/><span>删除</span></button></div>
  <div className="clip-slide"><button ref={front} type="button" className="clip-front" aria-label={clip.text+'，'+label} aria-expanded={open}
   onPointerDown={e=>{if(e.button!==0||!e.isPrimary)return;clear();gesture.current={id:e.pointerId,x:e.clientX,y:e.clientY,start:open?-148:0,offset:open?-148:0,axis:'',held:false};hold.current=setTimeout(()=>{if(!gesture.current||gesture.current.axis)return;gesture.current.held=true;blocked.current=Date.now()+800;menu();},520);}}
   onPointerMove={e=>{const g=gesture.current;if(!g||g.id!==e.pointerId)return;const dx=e.clientX-g.x,dy=e.clientY-g.y;if(Math.hypot(dx,dy)>8)clear();if(g.held)return;if(!g.axis&&Math.max(Math.abs(dx),Math.abs(dy))>9){if(Math.abs(dx)>Math.abs(dy)*1.2){g.axis='x';e.currentTarget.setPointerCapture(e.pointerId);}else if(Math.abs(dy)>Math.abs(dx))g.axis='y';}if(g.axis==='x'){e.preventDefault();g.offset=Math.max(-148,Math.min(0,g.start+dx));setDrag(g.offset);}}}
   onPointerUp={e=>finish(e)} onPointerCancel={e=>finish(e,true)} onLostPointerCapture={clear} onPointerLeave={()=>{if(!gesture.current?.axis)clear();}}
   onClick={e=>{if(Date.now()<blocked.current){e.preventDefault();return;}if(open){reveal('');return;}reveal('');copy();}}
   onKeyDown={e=>{if(e.key==='ArrowLeft'){e.preventDefault();keyboardReveal.current=true;reveal(clip.id);}else if(e.key==='Escape'||e.key==='ArrowRight'){e.preventDefault();reveal('');}else if(e.key==='ContextMenu'||e.shiftKey&&e.key==='F10'){e.preventDefault();menu();}}}>
   <span className="clip-content">{clip.text}</span><span className="clip-corner" aria-hidden="true">{clip.pinned&&<Pin size={14}/>} {!isLocal&&<Device size={14}/>}</span>
  </button></div>
 </article>;
}
