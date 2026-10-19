import {useEffect,useState} from 'react';
import {createRoot} from 'react-dom/client';
import {LockKeyhole,LogIn,ShieldCheck,LoaderCircle} from 'lucide-react';
import Home from './app/page';
import {apiFetch,setCsrf} from './lib/auth';
import './app/globals.css';

type Account={user:string;csrf:string;expires:number;version:string};
function App(){
 const [account,setAccount]=useState<Account|null>(null),[checking,setChecking]=useState(true),[error,setError]=useState('');
 const [username,setUsername]=useState('wmy'),[password,setPassword]=useState(''),[remember,setRemember]=useState(true),[pending,setPending]=useState(false);
 useEffect(()=>{
  try{document.documentElement.dataset.theme=localStorage.getItem('floatclip-theme')||'system';}catch{document.documentElement.dataset.theme='system';}
  if('serviceWorker' in navigator)void navigator.serviceWorker.register('/sw.js').catch(()=>{});
  void apiFetch('/api/auth').then(async r=>{if(r.ok){const a=await r.json();setCsrf(a.csrf);setAccount(a);}}).catch(()=>setError('无法连接小主机，请联网后重试')).finally(()=>setChecking(false));
  const lost=()=>{setAccount(null);setCsrf('');setError('登录已过期，请重新登录');};
  addEventListener('floatclip-auth-lost',lost);return()=>removeEventListener('floatclip-auth-lost',lost);
 },[]);
 const login=async(e:React.FormEvent)=>{
  e.preventDefault();if(pending)return;setPending(true);setError('');
  try{const r=await apiFetch('/api/auth',{method:'POST',body:JSON.stringify({username,password,remember})});const a=await r.json();
   if(!r.ok)throw Error(a.error||'登录未完成');setCsrf(a.csrf);setPassword('');setAccount(a);
  }catch(e){setError(e instanceof Error?e.message:'登录未完成，请稍后重试');}finally{setPending(false);}
 };
 const logout=async()=>{const r=await apiFetch('/api/logout',{method:'POST',body:'{}'});if(!r.ok&&r.status!==401)throw Error('退出未完成，请重试');setAccount(null);setCsrf('');};
 if(account)return <Home account={account.user} onLogout={logout}/>;
 return <main className="auth-page"><div className="auth-brand"><span className="logo" aria-hidden="true"><i/><b/></span><span>FloatClip</span></div><section className="auth-card card">
  <span className="auth-symbol"><LockKeyhole size={26}/></span><h1>连接你的另一块屏幕</h1><p className="auth-description">登录后，在 iPad、手机与电脑之间共享</p>
  {checking?<div className="auth-check" role="status"><LoaderCircle className="spinning" size={22}/>正在恢复登录…</div>:<form onSubmit={login}>
   <label htmlFor="username">账号</label><input id="username" name="username" autoComplete="username" autoCapitalize="none" spellCheck={false} maxLength={80} value={username} onChange={e=>setUsername(e.target.value)} required/>
   <label htmlFor="password">密码</label><input id="password" name="password" type="password" autoComplete="current-password" maxLength={256} value={password} onChange={e=>setPassword(e.target.value)} required/>
   <label className="remember"><input type="checkbox" checked={remember} onChange={e=>setRemember(e.target.checked)}/><span>在此设备保持登录 30 天</span></label>
   {error&&<p className="auth-error" role="alert">{error}</p>}<button type="submit" className="primary auth-submit" disabled={pending}>{pending?<LoaderCircle className="spinning" size={19}/>:<LogIn size={19}/>} {pending?'正在登录':'登录'}</button>
  </form>}
  <div className="auth-foot"><ShieldCheck size={16}/><span>私人共享空间 · 仅授权设备可访问</span></div>
 </section><p className="auth-caption">随手分享，自在连接</p></main>;
}
createRoot(document.getElementById('root')!).render(<App/>);
