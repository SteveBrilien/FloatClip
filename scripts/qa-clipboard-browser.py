"""Exercise the built PWA in an isolated Firefox profile with fixture-only local storage.
No production service, account, real clipboard or external network is used.
"""
from pathlib import Path
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse,parse_qs
import base64,json,re,socket,subprocess,threading,time,shutil,sys

root=Path(__file__).resolve().parents[1]
VERSION=json.loads((root/'web/package.json').read_text())['version']
out=root/('analysis/clipboard-'+VERSION.replace('.','')+'-browser');out.mkdir(parents=True,exist_ok=True)
dist=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else root/'web/dist'
index=(dist/'index.html').read_text()
entry=re.search(r'<script type="module"[^>]*src="([^"]+)"[^>]*></script>',index).group(1)
seed=r'''
localStorage.setItem('floatclip-device-id','qa-local');localStorage.setItem('floatclip-device','本机');
localStorage.setItem('floatclip-theme','light');localStorage.setItem('floatclip-card-tint','off');
const fixture=[
{id:'qa-pinned',text:'取用更顺手，界面更简单。',pinned:true,category:'未分类',createdAt:100,updatedAt:100,source:{id:'qa-local',name:'本机',platform:'web'}},
{id:'qa-win',text:'明天下午 3 点，带上记录表和数据线。',pinned:false,category:'工作',createdAt:400,updatedAt:400,source:{id:'qa-windows',name:'电脑',platform:'windows'}},
{id:'qa-local-url',text:'https://clip.wmy-cloud.cn',pinned:false,category:'未分类',createdAt:300,updatedAt:300,source:{id:'qa-local',name:'本机',platform:'web'}},
{id:'qa-android',text:'手机上的剪贴板内容，继续在这里使用。',pinned:false,category:'未分类',createdAt:200,updatedAt:200,source:{id:'qa-phone',name:'手机',platform:'android'}},
{id:'qa-ipad',text:'草稿：让复制的内容，在设备间接着用。',pinned:false,category:'研究',createdAt:150,updatedAt:150,source:{id:'qa-tablet',name:'iPad',platform:'ipad'}}];
const db=await new Promise((resolve,reject)=>{const r=indexedDB.open('floatclip-clipboard-v1',1);r.onupgradeneeded=()=>{r.result.createObjectStore('keys');r.result.createObjectStore('vaults');};r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error);});
const key=await crypto.subtle.generateKey({name:'AES-GCM',length:256},false,['encrypt','decrypt']);
const iv=crypto.getRandomValues(new Uint8Array(12)),cipher=await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:new TextEncoder().encode('FloatClip/vault/v1|ui-review')},key,new TextEncoder().encode(JSON.stringify(fixture)));
await new Promise((resolve,reject)=>{const t=db.transaction(['keys','vaults'],'readwrite');t.objectStore('keys').put(key,'ui-review');t.objectStore('vaults').put({revision:'qa-seed',iv,cipher},'ui-review');t.oncomplete=resolve;t.onerror=()=>reject(t.error);});db.close();
'''
index=index.replace(re.search(r'<script type="module"[^>]*src="[^"]+"[^>]*></script>',index).group(),'<script type="module">'+seed+'\nawait import('+json.dumps(entry)+');</script>')
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_GET(self):
  path=urlparse(self.path).path
  if path=='/qa':
   width=int(parse_qs(urlparse(self.path).query).get('width',['390'])[0]);data=('<!doctype html><style>body{margin:0;background:#e7eaf0}iframe{border:0;display:block;width:'+str(width)+'px;height:1100px}</style><iframe id="review" src="/app"></iframe>').encode();mime='text/html'
  elif path=='/api/auth':data=json.dumps({'user':'ui-review','csrf':'fixture','expires':int(time.time()*1000)+86400000,'version':VERSION}).encode();mime='application/json'
  elif path=='/app':data=index.encode();mime='text/html'
  else:
   p=(dist/path.lstrip('/')).resolve()
   if not p.is_relative_to(dist.resolve()) or not p.is_file():self.send_error(404);return
   data=p.read_bytes();mime={'.js':'application/javascript','.css':'text/css','.html':'text/html','.svg':'image/svg+xml','.json':'application/json','.png':'image/png','.webmanifest':'application/manifest+json'}.get(p.suffix,'application/octet-stream')
  self.send_response(200);self.send_header('Content-Type',mime);self.send_header('Cache-Control','no-store');self.end_headers();
  try:self.wfile.write(data)
  except (BrokenPipeError,ConnectionResetError):pass
server=ThreadingHTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
probe=socket.socket();probe.bind(('127.0.0.1',0));port=probe.getsockname()[1];probe.close()
profile=out/'profile';shutil.rmtree(profile,ignore_errors=True);profile.mkdir()
(profile/'user.js').write_text('user_pref("marionette.port",'+str(port)+');\nuser_pref("browser.shell.checkDefaultBrowser",false);\nuser_pref("datareporting.healthreport.uploadEnabled",false);\nuser_pref("toolkit.telemetry.enabled",false);\nuser_pref("browser.startup.homepage_override.mstone","ignore");\n')
log=(out/'firefox.log').open('w')
firefox=subprocess.Popen(['firefox','--headless','--no-remote','--profile',str(profile),'--marionette'],stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
class Client:
 def __init__(self,sock):self.sock=sock;self.counter=0;self.hello=self.read()
 def read(self):
  length=b''
  while True:
   c=self.sock.recv(1)
   if not c:raise RuntimeError('Firefox connection closed')
   if c==b':':break
   length+=c
  data=b'';remaining=int(length)
  while remaining:
   chunk=self.sock.recv(remaining)
   if not chunk:raise RuntimeError('Incomplete Firefox reply')
   data+=chunk;remaining-=len(chunk)
  return json.loads(data)
 def call(self,name,args=None):
  self.counter+=1;raw=json.dumps([0,self.counter,name,args or {}],ensure_ascii=False).encode();self.sock.sendall(str(len(raw)).encode()+b':'+raw)
  reply=self.read()
  if reply[2] is not None:raise RuntimeError(name+': '+json.dumps(reply[2],ensure_ascii=False))
  result=reply[3]
  return result.get('value',result) if isinstance(result,dict) else result
 def script(self,source):return self.call('WebDriver:ExecuteScript',{'script':source,'args':[],'newSandbox':False,'sandbox':'default'})
 def find(self,selector):
  result=self.call('WebDriver:FindElement',{'using':'css selector','value':selector})
  return result.get('element-6066-11e4-a52e-4f735466cecf',result.get('ELEMENT'))
 def click(self,selector):self.call('WebDriver:ElementClick',{'id':self.find(selector)})
 def until(self,expression):
  for _ in range(100):
   if self.script('return '+expression):return
   time.sleep(.1)
  raise AssertionError('Timed out: '+expression)
 def swipe(self,selector,dx):
  r=self.script('const r=document.querySelector('+json.dumps(selector)+').getBoundingClientRect();return {x:Math.round(Math.min(r.right-30,r.left+r.width*.8)),y:Math.round((r.top+r.bottom)/2)};')
  self.call('WebDriver:PerformActions',{'actions':[{'type':'pointer','id':'review-mouse','parameters':{'pointerType':'mouse'},'actions':[{'type':'pointerMove','origin':'viewport','x':r['x'],'y':r['y'],'duration':0},{'type':'pointerDown','button':0},{'type':'pointerMove','origin':'viewport','x':max(8,r['x']+dx),'y':r['y'],'duration':250},{'type':'pointerUp','button':0}]}]})
 def screenshot(self,name):
  data=self.call('WebDriver:TakeScreenshot',{'id':self.find('html'),'full':True});(out/name).write_bytes(base64.b64decode(data))
report={'checks':[]}
try:
 connection=None
 for _ in range(150):
  try:connection=socket.create_connection(('127.0.0.1',port),timeout=2);connection.settimeout(30);break
  except OSError:
   if firefox.poll() is not None:raise RuntimeError('Firefox startup failed: '+(out/'firefox.log').read_text()[-3000:])
   time.sleep(.1)
 if connection is None:raise RuntimeError('Firefox Marionette did not start')
 m=Client(connection);m.call('WebDriver:NewSession',{'capabilities':{}})
 m.call('WebDriver:SetWindowRect',{'width':1200,'height':1400})
 for width in [320,390,768,1100]:
  m.call('WebDriver:SwitchToFrame',{'id':None});m.call('WebDriver:Navigate',{'url':'http://127.0.0.1:'+str(server.server_port)+'/qa?width='+str(width)});m.call('WebDriver:SwitchToFrame',{'id':0})
  m.until("document.querySelectorAll('.clip-row').length===5")
  metrics=m.script("const front=document.querySelector('.clip-front'),content=front.querySelector('.clip-content'),a=front.getBoundingClientRect(),b=content.getBoundingClientRect();return {width:innerWidth,scroll:document.documentElement.scrollWidth,centerError:Math.abs((a.top+a.bottom-b.top-b.bottom)/2),icon:document.querySelector('[aria-label=设置] svg').getBoundingClientRect().width,search:document.querySelector('.library-search').getBoundingClientRect().width};")
  assert metrics['width']==width and metrics['scroll']<=width,metrics
  assert metrics['centerError']<=2 and metrics['icon']==24,metrics
  report['checks'].append({'responsive':metrics})
  if width==390:
   m.screenshot('pwa-light-390.png')
   identity=m.find('input[aria-label="搜索剪贴板"]');m.call('WebDriver:ElementSendKeys',{'id':identity,'text':'记录表'});m.until("document.querySelectorAll('.clip-row').length===1")
   assert m.find('input[aria-label="搜索剪贴板"]')==identity
   m.click('[aria-label="清空搜索"]');m.until("document.querySelectorAll('.clip-row').length===5")
   assert m.script("return document.activeElement===document.querySelector('input[aria-label=搜索剪贴板]')")
   report['checks'].append('live search preserves input and focus; clear restores all sources')
   m.call('WebDriver:ElementSendKeys',{'id':m.find('.clip-front'),'text':'\uE012'});m.until("document.activeElement?.closest('.clip-swipe-actions')!==null")
   m.call('WebDriver:ElementSendKeys',{'id':m.find('.clip-swipe-actions button:not(:disabled)'),'text':'\uE00C'});m.until("document.querySelectorAll('.clip-front[aria-expanded=true]').length===0")
   assert m.script("return document.activeElement?.classList.contains('clip-front')")
   report['checks'].append('keyboard disclosure focuses its actions; Escape closes and restores focus')
   m.swipe('.clip-row:nth-child(2) .clip-front',-112);m.until("document.querySelectorAll('.clip-front[aria-expanded=true]').length===1")
   assert not m.script("return document.querySelector('.toast')?.textContent.includes('已复制')??false")
   m.click('.clip-row:nth-child(2) .clip-swipe-actions button:first-child');m.until("document.querySelectorAll('.clip-corner svg.lucide-pin').length===2")
   m.swipe('.clip-row:nth-child(3) .clip-front',-112);m.until("document.querySelectorAll('.clip-front[aria-expanded=true]').length===1")
   m.click('.clip-row:nth-child(3) .clip-swipe-actions .delete');m.until("document.querySelectorAll('.clip-row').length===4")
   m.click('.clipboard-undo .text-button');m.until("document.querySelectorAll('.clip-row').length===5")
   report['checks'].append('real pointer swipe reveals actions without copying; pin, delete and encrypted-vault undo work')
   m.click('[aria-label="设置"]');m.click('.setting-row:first-child');m.until("document.querySelectorAll('.theme-list button').length===3");m.click('.theme-list button:last-child');m.until("document.documentElement.dataset.theme==='dark'")
   checks=m.script("return Array.from(document.querySelectorAll('.fc-setting-option input')).map(e=>e.checked)");assert checks==[True,False],checks
   m.click('.fc-setting-option:last-child input');m.until("document.documentElement.dataset.cardTint==='on'")
   m.click('[aria-label="返回剪贴板"]');m.click('[aria-label="返回剪贴板"]');m.until("document.querySelectorAll('.clip-row').length===5")
   m.screenshot('pwa-dark-390.png');report['checks'].append('appearance toggles and 24 px return icon work without a bottom navigation')
  if width==768:m.screenshot('pwa-light-768.png')
 m.call('Marionette:Quit',{'flags':['eForceQuit']});report['result']='PASS'
except Exception as e:
 report['result']='FAIL';report['error']=str(e)
 try:m.screenshot('failure.png')
 except Exception:pass
 raise
finally:
 (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');server.shutdown()
 if firefox.poll() is None:
  firefox.terminate()
  try:firefox.wait(timeout=10)
  except subprocess.TimeoutExpired:firefox.kill()
 log.close();print(json.dumps(report,ensure_ascii=False))
