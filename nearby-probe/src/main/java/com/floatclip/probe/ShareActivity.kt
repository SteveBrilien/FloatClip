package com.floatclip.probe

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import com.floatclip.share.ShareSession
import com.floatclip.share.ShareWire
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.*
import java.util.concurrent.Executors

/** Standalone front-end. No background clipboard polling or global network changes. */
class ShareActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("share-ui", MODE_PRIVATE) }
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var transport: LanShare
    private var pair: LanPair? = null
    private var discovering = false
    private var pairDialog: AlertDialog? = null
    private val trust by lazy { getSharedPreferences("share-pairs", MODE_PRIVATE) }
    private var updateBusy = false
    private var updateMessage = "当前版本 0.3.0 · 共享预览通道"
    private var release: ShareUpdates.Release? = null
    private var session: ShareSession? = null
    private var invitation = ""
    private var phase = "未连接"
    private var status = "添加设备，开始共享"
    private var progress = ""
    private var draft = ""
    private var tab = 0
    private var foreground = false
    private var picker = false
    private var epoch = 0
    private var saving: Received? = null
    private val inbox = ArrayDeque<Received>()
    private var offer: ShareWire.Offer? = null
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var connectionTitleView: TextView
    private lateinit var statusView: TextView
    private lateinit var progressView: TextView
    private lateinit var nav: LinearLayout
    private data class Received(val text: String? = null, val file: File? = null, val name: String = "", val outgoing: Boolean = false)
    private data class Palette(val bg: Int, val card: Int, val text: Int, val muted: Int, val line: Int, val blue: Int, val tint: Int)
    private var dark = false
    private lateinit var p: Palette
    private val cache by lazy { File(cacheDir, "share-items").apply { mkdirs() } }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        cache.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86400000L || it.name.endsWith(".part") }?.forEach { it.delete() }
        build()
    }
    override fun onStart() { super.onStart(); foreground = true; if(!picker && session==null && pair==null && invitation.isEmpty()) addresses().let { ips -> (prefs.getString("address",null)?.takeIf { it in ips } ?: ips.firstOrNull())?.let { listen(it) } } }
    override fun onResume() { super.onResume(); if(::body.isInitialized) render() }
    override fun onStop() {
        foreground = false
        if (!picker) disconnect("已暂停，回到前台后重新连接")
        super.onStop()
    }
    override fun onDestroy() { disconnect(); io.shutdownNow(); super.onDestroy() }
    override fun onConfigurationChanged(config: Configuration) { super.onConfigurationChanged(config); build() }

    private fun palette() {
        dark = when (prefs.getString("theme", "system")) { "dark" -> true; "light" -> false; else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES }
        p = if (dark) Palette(c("#0D1424"),c("#152139"),c("#EAF0FA"),c("#A2B2CA"),c("#2B3B54"),c("#60A5FA"),c("#20375B"))
            else Palette(c("#F5F8FC"),Color.WHITE,c("#18243A"),c("#61718A"),c("#E1E8F2"),c("#2563EB"),c("#EAF1FF"))
        @Suppress("DEPRECATION")
        window.statusBarColor = p.bg
        @Suppress("DEPRECATION")
        window.navigationBarColor = p.card
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }
    private fun build() {
        palette()
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg); fitsSystemWindows = true }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20),dp(18),dp(20),dp(6)) }
        val brand = text("F", 22, Color.WHITE, true).apply { gravity = Gravity.CENTER; background = shape(c("#2563EB"),14); layoutParams = LinearLayout.LayoutParams(dp(44),dp(44)) }
        header.addView(brand)
        val titles = column().apply { setPadding(dp(12),0,0,0); addView(text("FloatClip",24,p.text,true)); addView(text("跨设备，轻松共享",12,p.muted)) }
        header.addView(titles, LinearLayout.LayoutParams(0,-2,1f))
        header.addView(text("预览版",11,p.blue).apply { background=shape(p.tint,20); setPadding(dp(10),dp(6),dp(10),dp(6)) })
        root.addView(header)
        val scroll=ScrollView(this).apply { isFillViewport=true; clipToPadding=false }
        body=column().apply { setPadding(dp(20),dp(12),dp(20),dp(20)) }
        scroll.addView(body)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        nav=LinearLayout(this).apply { setPadding(dp(16),dp(8),dp(16),dp(12)); setBackgroundColor(p.card) }
        root.addView(nav)
        setContentView(root)
        render()
    }
    private fun render() {
        body.removeAllViews(); nav.removeAllViews()
        listOf("共享","设备","设置").forEachIndexed { i,label ->
            nav.addView(Button(this).apply {
                text=label; isAllCaps=false; textSize=14f; setTextColor(if(tab==i)p.blue else p.muted)
                background=shape(if(tab==i)p.tint else p.card,14)
                setOnClickListener { tab=i; render() }
            },LinearLayout.LayoutParams(0,dp(48),1f).apply { marginEnd=dp(6) })
        }
        when(tab) { 0 -> sharePage(); 1 -> devicesPage(); else -> settingsPage() }
    }
    private fun connection(parent: LinearLayout) {
        val row=column()
        connectionTitleView=text(if(session!=null) "●  已加密连接" else if(discovering || invitation.isNotEmpty()) "●  可被附近设备发现" else "○  未连接",15,if(session!=null || discovering || invitation.isNotEmpty())p.blue else p.muted,true); row.addView(connectionTitleView)
        statusView=text(status,12,p.muted).apply { setPadding(0,dp(7),0,0) }; row.addView(statusView)
        parent.addView(row)
    }
    private fun sharePage() {
        section("共享", "文字和文件，直达另一台设备")
        card { connection(this); if(session==null) button("连接设备",true) { tab=1; render() } }
        card {
            addView(text("发送文字",17,p.text,true))
            val editor=EditText(this@ShareActivity).apply {
                hint="输入内容，或从剪贴板粘贴"; setHintTextColor(p.muted); setTextColor(p.text); textSize=15f
                minLines=3; maxLines=7; gravity=Gravity.TOP; background=shape(p.bg,12)
                setPadding(dp(14),dp(12),dp(14),dp(12)); setText(draft)
                addTextChangedListener(object: TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) { }
                    override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) { draft=s?.toString().orEmpty() }
                    override fun afterTextChanged(s: Editable?) { }
                })
            }
            addView(editor,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(14) })
            val row=LinearLayout(this@ShareActivity)
            row.addView(action("粘贴",false) {
                val cb=getSystemService(ClipboardManager::class.java)
                val value=cb.primaryClip?.takeIf { it.itemCount>0 }?.getItemAt(0)?.text?.toString()
                if(value==null) note("剪贴板中没有可用文字") else if(value.toByteArray(Charsets.UTF_8).size>ShareWire.MAX_TEXT) note("文字最多 64 KiB") else editor.setText(value)
            },LinearLayout.LayoutParams(0,dp(46),1f).apply { marginEnd=dp(10) })
            row.addView(action("发送文字",true) {
                val active=session; val value=draft
                if(active==null) note("请先连接设备") else io.execute { try { active.sendText(value); ui { note("已发送，等待确认") } } catch(e:Exception) { ui { note("发送失败，请检查连接和文字长度") } } }
            },LinearLayout.LayoutParams(0,dp(46),1f))
            addView(row,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) })
        }
        card {
            addView(text("发送文件",17,p.text,true)); addView(text("单个文件 · 最大 64 MiB",12,p.muted).apply { setPadding(0,dp(6),0,0) })
            button("选择文件",false) {
                if(session==null) note("请先连接设备") else {
                    picker=true
                    try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="*/*"; addCategory(Intent.CATEGORY_OPENABLE) },41) }
                    catch(e:Exception) { picker=false; note("无法打开文件选择器") }
                }
            }
            progressView=text(progress,12,p.blue); addView(progressView)
            if(progress.isNotEmpty() && session!=null) button("取消传输并断开",false) { disconnect(); render() }
        }
        val pending=offer
        if(pending!=null) card {
            addView(text("对方想发送文件",15,p.text,true)); addView(text(pending.name,14,p.text)); addView(text(size(pending.size),12,p.muted))
            button("接收文件",true) { try { session?.accept(pending.id); offer=null; progress="正在接收 ${pending.name}"; render() } catch(e:Exception) { note("无法接收：请检查空间或清理接收缓存") } }
            button("拒绝",false) { try { session?.reject(pending.id) } catch(_:Exception) { }; offer=null; render() }
        }
        section("最近共享", "收到后由你决定复制或保存")
        if(inbox.isEmpty()) card { addView(text("还没有共享内容",15,p.muted)); addView(text("连接后，发送第一条文字吧",12,p.muted)) }
        for(item in inbox.toList().asReversed()) card {
            addView(text(if(item.outgoing) "已送达" else if(item.file==null) "收到文字" else "收到文件",11,p.blue,true))
            addView(text(item.text ?: item.name,15,p.text).apply { maxLines=5; ellipsize=android.text.TextUtils.TruncateAt.END; setPadding(0,dp(8),0,dp(4)) })
            if(item.file==null && item.text!=null) button("复制",false) {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("FloatClip",item.text)); note("已复制")
            }
            if(item.file!=null && !item.outgoing) button("另存为…",false) {
                saving=item; picker=true
                try { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    type="application/octet-stream"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_TITLE,item.name)
                },42) } catch(e:Exception) { picker=false; note("无法打开保存位置") }
            }
        }
    }
    private fun devicesPage() {
        section("连接设备", "同一 Wi-Fi 或手机热点，无需复制链接")
        card {
            connection(this)
            if(session!=null) {
                addView(text("现在可以共享文字和文件",14,p.text).apply { setPadding(0,dp(16),0,0) })
                button("返回共享",true) { tab=0; render() }
                button("断开连接",false) { disconnect(); render() }
            } else {
                addView(text("1  在 Windows 打开「设备」\n2  点击发现的手机\n3  首次核对两端识别码并允许",15,p.text).apply { setLineSpacing(dp(8).toFloat(),1f); setPadding(0,dp(18),0,dp(8)) })
                val ips=addresses()
                var selected=prefs.getString("address",null)?.takeIf { it in ips } ?: ips.firstOrNull()
                if(ips.size>1) {
                    val selector=Spinner(this@ShareActivity)
                    selector.adapter=object:ArrayAdapter<String>(this@ShareActivity,android.R.layout.simple_spinner_dropdown_item,ips) {
                        override fun getView(position:Int,convertView:View?,parent:ViewGroup):View = super.getView(position,convertView,parent).apply { (this as TextView).setTextColor(p.text) }
                        override fun getDropDownView(position:Int,convertView:View?,parent:ViewGroup):View = super.getDropDownView(position,convertView,parent).apply { (this as TextView).setTextColor(p.text); setBackgroundColor(p.card) }
                    }
                    selector.setSelection(ips.indexOf(selected).coerceAtLeast(0))
                    selector.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
                        override fun onNothingSelected(parent:AdapterView<*>?) { }
                        override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) { selected=ips[position]; prefs.edit().putString("address",selected).apply() }
                    }
                    addView(selector,LinearLayout.LayoutParams(-1,dp(48)))
                }
                addView(text(if(discovering) "发现窗口 90 秒 · ${selected.orEmpty()}" else "手机保持前台，记住的设备可直接重连",12,p.muted))
                button(if(discovering) "重新允许连接" else "允许附近设备连接",true) {
                    selected?.let { listen(it) } ?: note("请先连接 Wi-Fi 或开启热点")
                }
                if(discovering) button("暂停发现",false) { disconnect("发现已暂停"); render() }
                button("备用：使用邀请链接",false) { selected?.let { listen(it,false) } ?: note("没有可用网络") }
                if(invitation.isNotEmpty()) button("复制备用邀请",false) {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("FloatClip 连接邀请",invitation)); note("已复制备用邀请")
                }
            }
        }
        card {
            addView(text("已记住的设备",17,p.text,true))
            addView(text("${trust.all.size} 台 · 首次确认后，下次无需再核对",13,p.muted).apply { setPadding(0,dp(8),0,0) })
            if(trust.all.isNotEmpty()) button("移除所有设备授权",false) {
                AlertDialog.Builder(android.view.ContextThemeWrapper(this@ShareActivity,if(dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert)).setTitle("移除设备授权？").setMessage("所有已记住的电脑都需要重新配对。")
                    .setNegativeButton("取消",null).setPositiveButton("移除") { _,_ -> disconnect(); trust.edit().clear().apply(); render() }.show()
            }
        }
    }
    private fun settingsPage() {
        section("设置", "按你的习惯，保持简单")
        card {
            addView(text("外观",17,p.text,true))
            val mode=prefs.getString("theme","system")
            listOf("system" to "跟随系统","light" to "浅色","dark" to "深色").forEach { (id,label) ->
                button((if(mode==id) "✓  " else "    ")+label,mode==id) { prefs.edit().putString("theme",id).apply(); build() }
            }
        }
        card {
            addView(text("共享方式",17,p.text,true)); addView(text("前台手动共享 · 加密直连",14,p.muted).apply { setPadding(0,dp(10),0,dp(4)) })
            addView(text("文件速度上限 2 MiB/s；暂不自动同步系统剪贴板。",12,p.muted))
            button("清理接收缓存",false) {
                if(session!=null) note("请先断开连接") else AlertDialog.Builder(android.view.ContextThemeWrapper(this@ShareActivity,if(dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert)).setTitle("清理缓存？").setMessage("已另存的文件不受影响。")
                    .setNegativeButton("取消",null).setPositiveButton("清理") { _,_ -> cache.listFiles()?.forEach { it.delete() }; inbox.clear(); render() }.show()
            }
        }
        card {
            addView(text("FloatClip Share",16,p.text,true)); addView(text(updateMessage,13,p.muted).apply { setPadding(0,dp(8),0,0) })
            button(if(updateBusy) "正在检查…" else "检查更新",false) { checkUpdate() }
            release?.let { value -> button(if(value.build>ShareUpdates.BUILD) "下载 ${value.version} 更新" else "下载当前版本",true) { openDownload(value.url) } }
            button("连接诊断",false) { startActivity(Intent(this@ShareActivity,ProbeActivity::class.java)) }
        }
    }
    private fun listen(ip: String, nearby: Boolean = true) {
        disconnect(); val generation=epoch
        val messages=object:ShareSession.Events {
            override fun text(id:UUID,value:String,outgoing:Boolean)=ui { if(generation==epoch) { add(Received(text=value,outgoing=outgoing)); note(if(outgoing) "文字已送达" else "收到文字"); if(tab==0) contentChanged() } }
            override fun offered(value:ShareWire.Offer)=ui { if(generation==epoch) { offer=value; if(tab==0) contentChanged() else note("收到文件请求，请前往共享页") } }
            override fun progress(id:UUID,name:String,done:Long,total:Long,outgoing:Boolean)=ui { if(generation==epoch) { progress="${if(outgoing) "发送" else "接收"} $name · ${if(total==0L) 100 else done*100/total}%"; if(::progressView.isInitialized) progressView.text=progress } }
            override fun file(value:ShareWire.Offer,local:File,outgoing:Boolean)=ui { if(generation==epoch) { add(Received(file=if(outgoing) null else local,name=value.name,text=null,outgoing=outgoing)); progress="${value.name} · 已校验完成"; if(outgoing && local.name.startsWith("out-")) local.delete(); if(tab==0) contentChanged() } }
            override fun notice(message:String)=ui { if(generation==epoch) { progress=message; if(::progressView.isInitialized) progressView.text=message } }
            override fun closed()=ui { if(generation==epoch) { session=null; invitation=""; offer=null; pairDialog?.dismiss(); pairDialog=null; pair?.close(); pair=null; discovering=false; status="连接已结束，前往设备页重连"; if(::connectionTitleView.isInitialized) connectionTitleView.text="○  未连接"; if(::statusView.isInitialized) statusView.text=status; contentChanged() } }
        }
        phase="准备连接"; status=if(nearby) "正在允许附近设备连接" else "正在生成备用邀请"
        if(nearby) {
            val service=LanPair(cache,trust,object:LanPair.Events {
                override fun available(address:String)=ui { if(generation==epoch && foreground) { discovering=true; status="在 Windows 点击此手机即可连接"; render() } }
                override fun verify(code:String,answer:java.util.function.Consumer<Boolean>)=ui {
                    if(generation!=epoch || !foreground) { answer.accept(false); return@ui }
                    pairDialog=AlertDialog.Builder(android.view.ContextThemeWrapper(this@ShareActivity,if(dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert)).setTitle("允许 Windows 连接？")
                        .setMessage("核对两端的识别码是否完全相同：\n\n$code\n\n确认后记住此电脑；以后可以直接重连。")
                        .setNegativeButton("拒绝") { _,_ -> answer.accept(false) }
                        .setPositiveButton("相同，允许") { _,_ -> answer.accept(true) }
                        .setOnCancelListener { answer.accept(false) }.show()
                }
                override fun connected(value:ShareSession)=ui { if(generation==epoch && foreground) { pairDialog?.dismiss(); pairDialog=null; session=value; discovering=false; status="已记住设备 · 加密直连"; tab=0; render() } else value.close() }
                override fun stopped(message:String)=ui { if(generation==epoch) { pairDialog?.dismiss(); pairDialog=null; pair=null; discovering=false; session=null; status=message; render() } }
            },messages)
            pair=service; service.listen(ip)
        } else {
            transport=LanShare(cache,object:LanShare.Events {
                override fun invitation(code:String)=ui { if(generation==epoch && foreground) { invitation=code; status="备用邀请 90 秒有效"; render() } }
                override fun connected(value:ShareSession)=ui { if(generation==epoch && foreground) { session=value; invitation=""; status="临时设备 · 加密直连"; tab=0; render() } else value.close() }
                override fun stopped(message:String)=ui { if(generation==epoch) { invitation=""; session=null; status=message; render() } }
            },messages)
            transport.listen(ip)
        }
        render()
    }
    private fun disconnect(message:String="添加设备，开始共享") {
        epoch++; pairDialog?.dismiss(); pairDialog=null; pair?.close(); pair=null; discovering=false; if(::transport.isInitialized) transport.close(); session=null; invitation=""; offer=null; phase="未连接"; status=message
    }
    private fun checkUpdate() {
        if(updateBusy) return
        updateBusy=true; updateMessage="正在检查更新…"; release=null; render()
        Thread {
            try {
                val found=ShareUpdates.check()
                ui { updateBusy=false; release=found; updateMessage=(if(found.build>ShareUpdates.BUILD) "发现新版本 ${found.version}" else "已是最新版本 ${ShareUpdates.VERSION}")+"\n"+found.notes; if(tab==2) render() }
            } catch(_:Exception) { ui { updateBusy=false; updateMessage="检查失败，请确认网络后重试"; if(tab==2) render() } }
        }.start()
    }
    private fun openDownload(url:String) {
        try { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) } catch(_:Exception) { note("没有可用浏览器，请稍后重试") }
    }
    private fun contentChanged() {
        if(tab==0 && currentFocus is EditText) {
            if(::statusView.isInitialized) { statusView.text="有新的共享状态 · 点击查看"; statusView.setOnClickListener { currentFocus?.clearFocus(); render() } }
        } else render()
    }
    private fun add(value:Received) { if(inbox.size>=8) { inbox.removeFirst().file?.delete() }; inbox.addLast(value) }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data); picker=false
        val uri=data?.data
        if(resultCode!=RESULT_OK || uri==null) return
        if(requestCode==41) {
            val active=session ?: return
            var name="file.bin"
            contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) name=it.getString(0) ?: name }
            val taskEpoch=epoch
            val target=File(cache,"out-${UUID.randomUUID()}.tmp")
            progress="正在准备 $name"; render()
            io.execute {
                try {
                    ShareWire.safeName(name); var count=0L; val cached=cache.listFiles()?.sumOf { it.length() } ?: 0L
                    contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { output ->
                        val buffer=ByteArray(32768); var n=input.read(buffer)
                        while(n>=0) { if(session!==active || Thread.currentThread().isInterrupted) error("Session changed"); count+=n; if(count>ShareWire.MAX_FILE || cached+count>128L*1024*1024) error("File too large or cache full"); output.write(buffer,0,n); n=input.read(buffer) }
                    } } ?: error("File unavailable")
                    if(session!==active) error("Session changed")
                    active.sendFile(target,name)
                } catch(e:Exception) { target.delete(); ui { if(taskEpoch==epoch) { progress="文件不可用、超过 64 MiB，或缓存已满"; render() } } }
            }
        } else if(requestCode==42) {
            val item=saving ?: return; saving=null
            io.execute {
                try { contentResolver.openOutputStream(uri,"w")?.use { out -> item.file!!.inputStream().use { it.copyTo(out,32768) } } ?: error("Unavailable"); ui { note("文件已保存") } }
                catch(e:Exception) { ui { note("保存失败，请重新选择位置") } }
            }
        }
    }
    private fun addresses():List<String> = try { Collections.list(NetworkInterface.getNetworkInterfaces()).filter {
        it.isUp && (it.name.startsWith("wlan") || it.name.startsWith("eth") || it.name.startsWith("ap") || it.name.startsWith("en"))
    }.flatMap { Collections.list(it.inetAddresses) }.filter { it is Inet4Address && it.isSiteLocalAddress }.mapNotNull { it.hostAddress }.distinct().sorted() } catch(_:Exception) { emptyList() }
    private fun section(title:String,subtitle:String) { body.addView(text(title,26,p.text,true)); body.addView(text(subtitle,13,p.muted).apply { setPadding(0,dp(6),0,dp(16)) }) }
    private fun card(content:LinearLayout.()->Unit) { body.addView(column().apply { background=shape(p.card,18,true); setPadding(dp(18),dp(18),dp(18),dp(18)); content() },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(14) }) }
    private fun LinearLayout.button(label:String,primary:Boolean,click:()->Unit) { addView(action(label,primary,click),LinearLayout.LayoutParams(-1,dp(46)).apply { topMargin=dp(12) }) }
    private fun action(label:String,primary:Boolean,click:()->Unit)=Button(this).apply { text=label; isAllCaps=false; textSize=14f; setTextColor(if(primary) Color.WHITE else p.blue); background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(c("#302563EB")),shape(if(primary)c("#2563EB") else p.tint,12),null); setOnClickListener { click() } }
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun text(value:String,sp:Int,color:Int,bold:Boolean=false)=TextView(this).apply { text=value; textSize=sp.toFloat(); setTextColor(color); if(bold) typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) }
    private fun shape(color:Int,radius:Int,border:Boolean=false)=GradientDrawable().apply { setColor(color); cornerRadius=dp(radius).toFloat(); if(border) setStroke(dp(1),p.line) }
    private fun note(message:String) { Toast.makeText(this,message,Toast.LENGTH_SHORT).show() }
    private fun ui(action:()->Unit) { runOnUiThread { if(!isDestroyed) action() } }
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun c(value:String)=Color.parseColor(value)
    private fun size(n:Long)=if(n<1024) "$n B" else String.format(Locale.ROOT,"%.1f MiB",n/1048576.0)
}
