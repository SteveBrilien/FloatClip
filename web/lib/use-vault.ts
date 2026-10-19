import {useCallback,useEffect,useMemo,useRef,useState} from 'react';
import {Vault} from './vault.ts';
import type {Clip,Device,VaultSnapshot} from './clipboard.ts';
export function useVault(account:string,device:Device|null){
 const vault=useMemo(()=>device?new Vault(account,device):null,[account,device?.id]);
 const [entries,setEntries]=useState<Clip[]>([]),[loaded,setLoaded]=useState(false),[error,setError]=useState(''),[pending,setPending]=useState(0);
 const queue=useRef<Promise<unknown>>(Promise.resolve()),epoch=useRef(0);
 const refresh=useCallback(async()=>{if(!vault)return;const generation=epoch.current;try{await queue.current;const snap=await vault.load();if(generation!==epoch.current)return;setEntries(snap.entries);setLoaded(true);setError('');}catch(e){if(generation===epoch.current){setError(e instanceof Error?e.message:'无法打开本机剪贴板');setLoaded(false);}}},[vault]);
 useEffect(()=>{epoch.current++;setLoaded(false);setEntries([]);void refresh();const visible=()=>{if(document.visibilityState==='visible')void refresh();};addEventListener('focus',visible);addEventListener('floatclip-vault-changed',visible);document.addEventListener('visibilitychange',visible);return()=>{epoch.current++;removeEventListener('focus',visible);removeEventListener('floatclip-vault-changed',visible);document.removeEventListener('visibilitychange',visible);};},[refresh]);
 const mutate=useCallback((transform:(entries:Clip[])=>Clip[])=>{
  if(!vault)return Promise.reject(Error('本机剪贴板尚未就绪'));
  const generation=epoch.current;setPending(n=>n+1);
  const action=queue.current.catch(()=>{}).then(async()=>{
   let result:VaultSnapshot|null=null;
   for(let retry=0;retry<4;retry++){const snap=await vault.load();try{result=await vault.commit(snap.revision,transform(snap.entries));break;}catch(e){if(!(e instanceof Error)||e.message!=='VAULT_CONFLICT'||retry===3)throw e;}}
   if(generation===epoch.current&&result){setEntries(result.entries);setLoaded(true);setError('');}
  });
  queue.current=action.catch(()=>{});
  return action.catch(e=>{if(generation===epoch.current)setError(e instanceof Error?e.message:'保存未完成');throw e;}).finally(()=>{if(generation===epoch.current)setPending(n=>Math.max(0,n-1));});
 },[vault]);
 return {entries,loaded,error,pending:pending>0,mutate,refresh};
}
