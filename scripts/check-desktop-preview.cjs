const fs=require('node:fs'),assert=require('node:assert/strict');
const {chromium}=require('playwright');
const source=require('node:path').resolve(__dirname,'../docs/ui/floatclip-desktop-review.html');
(async()=>{
 fs.mkdirSync('analysis/desktop-checks',{recursive:true});
 const fragment=fs.readFileSync(source,'utf8');assert(Buffer.byteLength(fragment)<1e6);assert(!fragment.includes('\\"'));assert(!/<svg|<path/.test(fragment));
 const browser=await chromium.launch({headless:true,channel:process.env.PLAYWRIGHT_CHANNEL||undefined}),errors=[];
 try{
  const page=await browser.newPage({viewport:{width:1024,height:1000}});page.on('pageerror',e=>errors.push(e.message));
  await page.setContent('<!doctype html><html><head><meta name="color-scheme" content="light dark"></head><body style="margin:0">'+fragment+'</body></html>');
  const app=page.locator('.dr-app'),main=app.locator('main'),nav=app.locator('nav'),activity=()=>main.getByRole('region',{name:'传输活动'});
  assert.equal(await main.getByRole('heading',{name:'共享文件',exact:true}).count(),1);assert.equal(await main.locator('tbody tr').count(),5);assert.equal(await main.locator('th').count(),6);
  const w=(await app.boundingBox()).width;assert.equal(await activity().count(),0);let anchor;
  await main.getByRole('checkbox',{name:'选择 FloatClip 设计说明.pdf',exact:true}).check();
  await main.getByRole('button',{name:'预览',exact:true}).click();assert(await main.getByText('文件尚未下载，选中或预览不会自动传输。',{exact:true}).isVisible());assert.equal(await main.getByRole('progressbar').count(),0);
  assert.equal(await main.getByRole('dialog').getAttribute('aria-modal'),'false');await main.getByRole('button',{name:'关闭详情'}).click();
  await main.getByRole('button',{name:'下载',exact:true}).click();assert(await main.getByRole('dialog',{name:'保存到文件夹'}).isVisible());await main.getByRole('button',{name:'开始下载'}).click();
  await page.waitForTimeout(400);anchor=(await activity().boundingBox()).y;assert(await main.getByRole('button',{name:'取消',exact:true}).isVisible());await main.getByRole('button',{name:'取消',exact:true}).click();assert(await activity().getByText('已取消',{exact:true}).isVisible());
  await main.getByRole('button',{name:'下载',exact:true}).click();await main.getByRole('button',{name:'开始下载'}).click();await page.waitForTimeout(3350);assert(await activity().getByText('已完成',{exact:true}).isVisible());assert.equal((await activity().boundingBox()).y,anchor);
  await main.getByRole('button',{name:'预览',exact:true}).click();assert(await main.getByText('PDF · 第 1 页（示例文档）',{exact:true}).isVisible());await main.getByRole('button',{name:'关闭详情'}).click();
  await page.screenshot({path:'analysis/desktop-checks/desktop-review-files.png',fullPage:true});
  for(const name of ['剪贴板','我的设备','设置','版本与更新','文件']){await nav.getByRole('button',{name,exact:true}).click();assert.equal((await app.boundingBox()).width,w);}
  await app.getByRole('button',{name:'固定面板',exact:true}).click();assert.equal(await app.getByRole('button',{name:'取消固定面板',exact:true}).getAttribute('aria-pressed'),'true');
  await nav.getByRole('button',{name:'剪贴板',exact:true}).click();await main.getByRole('searchbox').fill('预算');assert.equal(await main.locator('.dr-clip-row').count(),1);await main.getByRole('button',{name:'清空搜索'}).click();
  let box=await main.locator('.dr-clip-front').first().boundingBox();await page.mouse.move(box.x+120,box.y+45);await page.mouse.down();await page.mouse.move(box.x+195,box.y+45,{steps:9});await page.mouse.up();assert.equal(await main.locator('.dr-clip-row').first().getAttribute('data-swipe'),'right');await main.getByRole('button',{name:'固定',exact:true}).first().click();
  await main.getByRole('button',{name:'全部清除'}).click();assert.equal(await main.locator('.dr-clip-row').count(),3);await main.locator('.dr-clip-front').first().click({button:'right'});assert.equal(await main.locator('.dr-clip-row').first().getAttribute('data-swipe'),'left');
  await page.screenshot({path:'analysis/desktop-checks/desktop-review-clipboard.png',fullPage:true});
  await nav.getByRole('button',{name:'我的设备',exact:true}).click();await main.getByRole('button',{name:'重新连接',exact:true}).click();assert(await main.getByText('正在完成同步握手…',{exact:true}).isVisible());await page.waitForTimeout(1350);assert.equal(await main.getByText('蓝牙同步就绪',{exact:true}).count(),2);
  assert(await main.getByRole('checkbox',{name:'与 V2115A 同步',exact:true}).isVisible());await page.screenshot({path:'analysis/desktop-checks/desktop-review-devices.png',fullPage:true});
  await nav.getByRole('button',{name:'版本与更新',exact:true}).click();await main.getByRole('button',{name:'检查更新',exact:true}).click();await page.waitForTimeout(800);await main.getByRole('button',{name:'下载更新',exact:true}).click();await main.getByRole('button',{name:'更新并重启',exact:true}).click();assert(await main.locator('.dr-setting-label').filter({hasText:'更新流程演示已完成'}).isVisible());
  const lum=c=>c.match(/[\d.]+/g).slice(0,3).map(Number).map(x=>x/255).map(x=>x<=.04045?x/12.92:((x+.055)/1.055)**2.4).reduce((s,x,i)=>s+x*[.2126,.7152,.0722][i],0);
  for(const theme of ['light','dark','tokyo-night']){await nav.getByRole('button',{name:'设置',exact:true}).click();await main.getByRole('combobox',{name:'应用主题'}).selectOption(theme);const colors=await app.evaluate(e=>{const s=getComputedStyle(e),probe=e.querySelector('.dr-muted')||e;return {fg:s.color,bg:s.backgroundColor,muted:getComputedStyle(probe).color};});assert((Math.max(lum(colors.fg),lum(colors.bg))+.05)/(Math.min(lum(colors.fg),lum(colors.bg))+.05)>=7);}
  for(const width of [520,320]){await page.setViewportSize({width,height:1000});for(const name of ['文件','我的设备','设置','版本与更新','剪贴板']){await nav.getByRole('button',{name,exact:true}).click();assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));}await nav.getByRole('button',{name:'文件',exact:true}).click();await page.screenshot({path:`analysis/desktop-checks/desktop-review-${width}.png`,fullPage:true});}
  assert.equal(errors.length,0,errors.join('\n'));fs.writeFileSync('analysis/desktop-checks/desktop-review-report.json',JSON.stringify({result:'PASS',checks:'five pages; stable window width and transfer dock; deliberate download/preview; cancellation; full 6-column list; real pointer swipe; protected clear; always-visible toggles; HELLO status; update flow; 320/520/1024px; themes; no runtime errors'},null,2));console.log('PASS: complete desktop review interactions and responsive layout');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
