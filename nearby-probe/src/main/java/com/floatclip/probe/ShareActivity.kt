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
import android.os.Build
import android.content.res.ColorStateList
import android.view.accessibility.AccessibilityNodeInfo
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
open class ShareActivity : Activity() {
    private val clipboardHost by lazy { intent.getBooleanExtra("devicesOnly",false) }
    protected open fun sharedText(id: UUID,value: String,outgoing: Boolean,peer: String) = Unit
    protected open fun openWebDevices(): Boolean = false
    protected open fun managedConnections(): Boolean = false
    protected open fun beginManagedPairing(): Boolean = false
    protected open fun stopManagedPairing(): Boolean = false
    protected open fun pauseManagedConnections(): Boolean = false
    protected open fun clearManagedTrust(): Boolean = false
    protected open fun hostTheme(): String? = null
    protected fun managedState(active: ShareSession?,summary: String,allowing: Boolean) {
        session=active;status=summary;discovering=allowing;invitation="";render()
    }
    protected fun managedOffer(value: ShareWire.Offer,active: ShareSession?) {session=active;offer=value;switchTab(0)}
    protected fun managedProgress(name: String,done: Long,total: Long,outgoing: Boolean) {progress="${if(outgoing) "发送" else "接收"} $name · ${if(total==0L) 100 else done*100/total}%";if(::progressView.isInitialized)progressView.text=progress}
    protected fun managedFile(value: ShareWire.Offer,file: File,outgoing: Boolean) {add(Received(file=if(outgoing)null else file,name=value.name,outgoing=outgoing));progress="文件已校验完成";render()}
    protected open fun copyText(value: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("FloatClip",value))
    }
    private val prefs by lazy { getSharedPreferences("share-ui", MODE_PRIVATE) }
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var transport: LanShare
    private var pair: LanPair? = null
    private var discovering = false
    private var pairDialog: AlertDialog? = null
    private val trust by lazy { getSharedPreferences("share-pairs", MODE_PRIVATE) }
    private var updateBusy = false
    private var updateMessage = "当前版本 ${ShareUpdates.VERSION} · 共享预览通道"
    private var release: ShareUpdates.Release? = null
    private var session: ShareSession? = null
    private var invitation = ""
    private var phase = "未连接"
    private var status = "添加设备，开始共享"
    private var progress = ""
    private var draft = ""
    private var tab = 0
    private enum class Detail { ROOT, APPEARANCE, CACHE, UPDATES, HELP, ADVANCED, TRUST, SHARING }
    private var detail = Detail.ROOT
    private val backStack = ArrayDeque<Pair<Detail, Int>>()
    private var renderedDetail = Detail.ROOT
    private var transition = 0
    private var keyboardVisible = false
    private lateinit var header: LinearLayout
    private lateinit var bottom: LinearLayout
    private var renderedTab = -1
    private lateinit var scroller: ScrollView
    private lateinit var uiContext: Context
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
    private var dark = false
    private lateinit var p: ShareDesign.Palette
    private val cache by lazy { File(cacheDir, "share-items").apply { mkdirs() } }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        cache.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86400000L || it.name.endsWith(".part") }?.forEach { it.delete() }
        if(clipboardHost) tab=1
        build()
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (!goBack()) finish()
            }
        }
    }
    @Suppress("DEPRECATION")
    override fun onBackPressed() { if (!goBack()) super.onBackPressed() }
    override fun onStart() { super.onStart(); foreground = true; if(!managedConnections() && !picker && session==null && pair==null && invitation.isEmpty()) addresses().let { ips -> (prefs.getString("address",null)?.takeIf { it in ips } ?: ips.firstOrNull())?.let { listen(it) } } }
    override fun onResume() { super.onResume(); if(::body.isInitialized) render() }
    override fun onStop() {
        foreground = false
        if (!picker&&!managedConnections()) disconnect("已暂停，回到前台后重新连接")
        super.onStop()
    }
    override fun onDestroy() { if(!managedConnections())disconnect();io.shutdownNow();super.onDestroy() }
    override fun onConfigurationChanged(config: Configuration) { super.onConfigurationChanged(config); build() }

    @Suppress("DEPRECATION") // Platform fallbacks are required on supported Android 9–14 devices.
    private fun palette() {
        dark = when (hostTheme() ?: prefs.getString("theme", "system")) {
            "dark" -> true; "light" -> false
            else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        p = ShareDesign.palette(dark)
        val themeId=if(dark) R.style.Theme_FloatClip_Dark else R.style.Theme_FloatClip_Light
        setTheme(themeId)
        uiContext = ContextThemeWrapper(this,themeId)
        @Suppress("DEPRECATION")
        window.statusBarColor = if(Build.VERSION.SDK_INT >= 30) Color.TRANSPARENT else p.bg
        @Suppress("DEPRECATION")
        window.navigationBarColor = if(Build.VERSION.SDK_INT >= 30) Color.TRANSPARENT else p.card
        window.navigationBarDividerColor = Color.TRANSPARENT
        if(Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if(Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            // Android 11 PhoneWindow dereferences an uncreated DecorView here.
            // Apply icon appearance through the attached view's insets callback below.
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if(dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }
    private fun build() {
        val oldScroll = if(::scroller.isInitialized) scroller.scrollY else 0
        palette()
        root = column().apply { setBackgroundColor(p.bg); fitsSystemWindows = Build.VERSION.SDK_INT < 30; isFocusableInTouchMode=true }
        header = LinearLayout(uiContext).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(12),dp(4),dp(12),dp(4)) }
        root.addView(header)
        scroller=ScrollView(uiContext).apply { isFillViewport=true; clipToPadding=false; isVerticalScrollBarEnabled=false }
        body=column().apply { setPadding(dp(16),dp(8),dp(16),dp(24)) }
        scroller.addView(body)
        root.addView(scroller,LinearLayout.LayoutParams(-1,0,1f))
        bottom=column().apply { id=R.id.nav_container; setBackgroundColor(p.card) }
        bottom.addView(View(uiContext).apply { setBackgroundColor(p.line) },LinearLayout.LayoutParams(-1,dp(1)))
        nav=LinearLayout(uiContext).apply { gravity=Gravity.CENTER; setPadding(dp(12),dp(8),dp(12),dp(8)) }
        bottom.addView(nav)
        root.addView(bottom)
        root.addOnLayoutChangeListener { _,left,_,right,_,oldLeft,_,oldRight,_ ->
            if(right-left != oldRight-oldLeft) {
                val gutter=maxOf(dp(16),(right-left-dp(640))/2+dp(16))
                header.setPadding(maxOf(dp(12),gutter-dp(4)),dp(4),maxOf(dp(12),gutter-dp(4)),dp(4))
                body.setPadding(gutter,dp(8),gutter,dp(24))
                val navGutter=maxOf(dp(12),(right-left-dp(480))/2)
                nav.setPadding(navGutter,nav.paddingTop,navGutter,nav.paddingBottom)
            }
        }
        if(Build.VERSION.SDK_INT >= 30) root.setOnApplyWindowInsetsListener { view,insets ->
            val appearance=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            view.windowInsetsController?.setSystemBarsAppearance(if(dark) 0 else appearance,appearance)
            val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime=insets.getInsets(WindowInsets.Type.ime())
            keyboardVisible=insets.isVisible(WindowInsets.Type.ime())
            root.setPadding(bars.left,bars.top,bars.right,if(keyboardVisible) ime.bottom else if(clipboardHost || detail!=Detail.ROOT) bars.bottom else 0)
            bottom.visibility=if(clipboardHost || keyboardVisible || detail!=Detail.ROOT) View.GONE else View.VISIBLE
            nav.setPadding(nav.paddingLeft,dp(8),nav.paddingRight,dp(8)+bars.bottom)
            insets
        }
        renderedTab=-1
        setContentView(root)
        root.requestFocus()
        root.requestApplyInsets()
        render()
        scroller.post { scroller.scrollTo(0,oldScroll) }
    }
    private fun dismissKeyboard() {
        val focused=currentFocus
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(focused?.windowToken ?: root.windowToken,0)
        focused?.clearFocus()
    }
    private fun navigate(target: Detail) {
        if(target==detail) return
        dismissKeyboard()
        backStack.addLast(detail to scroller.scrollY)
        detail=target; transition=1; render()
    }
    private fun goBack(): Boolean {
        if(backStack.isEmpty()) return false
        val previous=backStack.removeLast(); detail=previous.first; transition=-1
        render(); scroller.post { scroller.scrollTo(0,previous.second) }
        return true
    }
    private fun switchTab(index: Int) {
        dismissKeyboard(); backStack.clear(); detail=Detail.ROOT; tab=index; transition=0; render()
    }
    private fun screenTitle() = when(detail) {
        Detail.ROOT -> listOf("共享","设备","设置")[tab]
        Detail.APPEARANCE -> "外观"; Detail.CACHE -> "接收缓存"; Detail.UPDATES -> "版本与更新"
        Detail.HELP -> "连接帮助"; Detail.ADVANCED -> "网络与备用连接"; Detail.TRUST -> "设备授权"; Detail.SHARING -> "共享说明"
    }
    private fun toolbar() {
        header.removeAllViews()
        if(detail==Detail.ROOT && !clipboardHost) {
            header.addView(ImageView(uiContext).apply { setImageResource(R.drawable.ic_floatclip); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(28),dp(28)).apply { marginStart=dp(4); marginEnd=dp(12) })
        } else {
            val back=FrameLayout(uiContext).apply {
                id=R.id.detail_back; contentDescription="返回"; background=ripple(p.bg,24); buttonSemantics(this); setOnClickListener { if(!goBack()) finish() }
            }
            back.addView(icon(ShareGlyph.CHEVRON,p.text).apply { rotation=180f },FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER))
            header.addView(back,LinearLayout.LayoutParams(dp(48),dp(48)).apply { marginEnd=dp(4) })
        }
        header.addView(text(screenTitle(),if(detail==Detail.ROOT) 22 else 20,p.text,true).apply { id=R.id.page_title; isAccessibilityHeading=true },LinearLayout.LayoutParams(0,-2,1f))
        if(detail==Detail.ROOT && !clipboardHost) {
            val badge=FrameLayout(uiContext).apply { minimumHeight=dp(48); background=ripple(p.bg,12); contentDescription="版本与更新"; buttonSemantics(this); setOnClickListener { navigate(Detail.UPDATES) } }
            badge.addView(text("预览版",11,p.blue,true).apply { background=shape(p.tint,8); setPadding(dp(8),dp(5),dp(8),dp(5)); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
            header.addView(badge,LinearLayout.LayoutParams(-2,dp(48)))
        }
    }
    private fun render() {
        val scrollY=if(renderedTab==tab && renderedDetail==detail) scroller.scrollY else 0
        body.animate().cancel(); body.alpha=1f; body.translationX=0f
        body.removeAllViews(); nav.removeAllViews(); toolbar()
        bottom.visibility=if(!clipboardHost && detail==Detail.ROOT && !keyboardVisible) View.VISIBLE else View.GONE
        listOf("共享" to ShareGlyph.SHARE,"设备" to ShareGlyph.DEVICES,"设置" to ShareGlyph.SETTINGS).forEachIndexed { i,(label,glyph) ->
            val selected=tab==i
            val item=column().apply {
                id=listOf(R.id.nav_share,R.id.nav_devices,R.id.nav_settings)[i]
                gravity=Gravity.CENTER; minimumHeight=dp(64); setPadding(dp(4),dp(2),dp(4),dp(2))
                background=ripple(p.card,16); contentDescription=label; isSelected=selected; buttonSemantics(this)
                setOnClickListener { if(tab!=i || detail!=Detail.ROOT) switchTab(i) }
            }
            val indicator=FrameLayout(uiContext).apply { if(selected) background=shape(p.tint,16); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
            indicator.addView(icon(glyph,if(selected) p.blue else p.muted),FrameLayout.LayoutParams(dp(24),dp(24),Gravity.CENTER))
            item.addView(indicator,LinearLayout.LayoutParams(dp(60),dp(32)))
            item.addView(text(label,12,if(selected) p.blue else p.muted,selected).apply { gravity=Gravity.CENTER; setPadding(0,dp(4),0,0); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            nav.addView(item,LinearLayout.LayoutParams(0,-2,1f))
        }
        when(detail) {
            Detail.ROOT -> when(tab) { 0 -> sharePage(); 1 -> devicesPage(); else -> settingsPage() }
            Detail.APPEARANCE -> appearancePage(); Detail.CACHE -> cachePage(); Detail.UPDATES -> updatesPage()
            Detail.HELP -> helpPage(); Detail.ADVANCED -> advancedPage(); Detail.TRUST -> trustPage(); Detail.SHARING -> sharingPage()
        }
        renderedTab=tab; renderedDetail=detail
        scroller.post { scroller.scrollTo(0,scrollY) }; root.requestApplyInsets()
        if(transition!=0 && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            body.alpha=0.5f; body.translationX=dp(12).toFloat()*transition
            body.animate().alpha(1f).translationX(0f).setDuration(160).start()
        }
        transition=0
    }
    private fun connection(parent: LinearLayout) {
        val active=session!=null || discovering || invitation.isNotEmpty()
        val row=LinearLayout(uiContext).apply { gravity=Gravity.CENTER_VERTICAL }
        val badge=FrameLayout(uiContext).apply { background=shape(p.tint,12) }
        badge.addView(icon(if(session!=null) ShareGlyph.SHIELD else ShareGlyph.DEVICES,p.blue),FrameLayout.LayoutParams(dp(24),dp(24),Gravity.CENTER))
        row.addView(badge,LinearLayout.LayoutParams(dp(36),dp(36)).apply { marginEnd=dp(12) })
        val details=column()
        connectionTitleView=text(if(session!=null) "已加密连接" else if(active) "等待电脑连接" else "尚未连接",15,p.text,true)
        details.addView(connectionTitleView)
        statusView=text(status,12,p.muted).apply { maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END; setPadding(0,dp(4),0,0); accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
        details.addView(statusView)
        row.addView(details,LinearLayout.LayoutParams(0,-2,1f)); parent.addView(row)
    }
    private fun sharePage() {
        card {
            val row=LinearLayout(uiContext).apply { gravity=Gravity.CENTER_VERTICAL; minimumHeight=dp(48); background=ripple(p.card,12); contentDescription="查看连接设备"; buttonSemantics(this); setOnClickListener { switchTab(1) } }
            row.addView(column().apply { connection(this) },LinearLayout.LayoutParams(0,-2,1f))
            row.addView(icon(ShareGlyph.CHEVRON,p.muted),LinearLayout.LayoutParams(dp(18),dp(18)).apply { marginStart=dp(8) })
            addView(row)
        }
        card {
            addView(text("文字",16,p.text,true))
            val editor=EditText(uiContext).apply {
                id=R.id.share_editor; hint="输入或粘贴文字"; setHintTextColor(p.muted); setTextColor(p.text); textSize=15f
                gravity=Gravity.TOP
                inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines=2; maxLines=6
                background=android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused),shape(p.bg,12,true,p.blue))
                    addState(intArrayOf(),shape(p.bg,12,true))
                }
                backgroundTintList=null; setPadding(dp(14),dp(14),dp(14),dp(14)); setText(draft)
                contentDescription="要发送的文字"
                addTextChangedListener(object: TextWatcher {
                    override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) { }
                    override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { draft=s?.toString().orEmpty() }
                    override fun afterTextChanged(s:Editable?) { }
                })
            }
            addView(editor,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) })
            val row=LinearLayout(uiContext).apply { gravity=Gravity.CENTER_VERTICAL }
            row.addView(action("粘贴",false) {
                val cb=getSystemService(ClipboardManager::class.java)
                val value=cb.primaryClip?.takeIf { it.itemCount>0 }?.getItemAt(0)?.text?.toString()
                if(value==null) note("剪贴板中没有可用文字") else if(value.toByteArray(Charsets.UTF_8).size>ShareWire.MAX_TEXT) note("文字最多 64 KiB") else editor.setText(value)
            },LinearLayout.LayoutParams(0,-2,1f).apply { marginEnd=dp(12) })
            row.addView(action("发送",true) {
                val active=session; val value=draft
                if(active==null) note("请先连接设备") else io.execute { try { active.sendText(value); ui { note("已发送，等待确认") } } catch(e:Exception) { ui { note("发送失败，请检查连接和文字长度") } } }
            },LinearLayout.LayoutParams(0,-2,2f))
            addView(row,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) })
        }
        card {
            addView(settingRow("文件","单个文件，最大 64 MiB",ShareGlyph.FILE) { chooseFile() })
            progressView=text(progress,12,p.blue).apply { visibility=if(progress.isEmpty()) View.GONE else View.VISIBLE; setPadding(0,dp(10),0,0); accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
            addView(progressView)
            if(progress.isNotEmpty() && session!=null) button(if(managedConnections()) "取消传输并暂停共享" else "取消传输并断开",false) { disconnect(); render() }
        }
        offer?.let { pending -> card {
            cardTitle("收到文件请求", "${pending.name} · ${size(pending.size)}")
            button("接收文件",true) { try { session?.accept(pending.id); offer=null; progress="正在接收 ${pending.name}"; render() } catch(e:Exception) { note("无法接收：请检查空间或清理接收缓存") } }
            button("暂不接收",false) { try { session?.reject(pending.id) } catch(_:Exception) { }; offer=null; render() }
        } }
        section("最近共享", "",small=true)
        if(inbox.isEmpty()) card {
            addView(icon(ShareGlyph.SHARE,p.muted),LinearLayout.LayoutParams(dp(28),dp(28)).apply { gravity=Gravity.CENTER_HORIZONTAL; bottomMargin=dp(12) })
            addView(text("还没有共享内容",15,p.text,true).apply { gravity=Gravity.CENTER })
            addView(text("连接后，试着发送第一条文字",13,p.muted).apply { gravity=Gravity.CENTER; setPadding(0,dp(6),0,dp(4)) })
        }
        for(item in inbox.toList().asReversed()) card {
            addView(text(if(item.outgoing) "已送达" else if(item.file==null) "收到文字" else "收到文件",12,p.blue,true))
            addView(text(item.text ?: item.name,15,p.text).apply { maxLines=5; ellipsize=android.text.TextUtils.TruncateAt.END; setPadding(0,dp(10),0,0) })
            if(item.file==null && item.text!=null) button("复制文字",false) {
                copyText(item.text); note("已复制")
            }
            if(item.file!=null && !item.outgoing) button("另存为…",false) {
                saving=item; picker=true
                try { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    type="application/octet-stream"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_TITLE,item.name)
                },42) } catch(e:Exception) { picker=false; note("无法打开保存位置") }
            }
        }
    }
    private fun chooseFile() {
        if(session==null) note("请先连接设备") else {
            picker=true
            try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="*/*"; addCategory(Intent.CATEGORY_OPENABLE) },41) }
            catch(e:Exception) { picker=false; note("无法打开文件选择器") }
        }
    }
    private fun devicesPage() {
        intro(if(managedConnections()) "蓝牙可直接添加设备；同一局域网也可自动发现" else "同一 Wi-Fi 或手机热点，即可发现")
        card {
            connection(this)
            if(session!=null) {
                button("开始共享",true) { switchTab(0) }
                button(if(managedConnections()) "暂停共享" else "断开连接",false) { disconnect(); render() }
            } else {
                button(if(discovering) "重新开放配对窗口" else if(managedConnections()) "添加设备" else "允许附近设备连接",true) {
                    if(managedConnections()) beginManagedPairing()
                    else {
                        val ips=addresses(); (prefs.getString("address",null)?.takeIf { it in ips } ?: ips.firstOrNull())?.let { listen(it) } ?: note("请先连接 Wi-Fi 或开启热点")
                    }
                }
                if(discovering) button(if(managedConnections()) "关闭配对窗口" else "暂停发现",false) {
                    if(managedConnections()&&stopManagedPairing()) render() else { disconnect("发现已暂停"); render() }
                }
                val hint=if(managedConnections()) {
                    if(discovering) "90 秒配对窗口 · 蓝牙与局域网均可首配" else "已授权设备会通过蓝牙或局域网自动重连"
                } else if(discovering) "90 秒发现窗口 · 保持手机前台" else "连接电脑时，保持手机前台"
                addView(text(hint,12,p.muted).apply { gravity=Gravity.CENTER; setPadding(0,dp(10),0,0) })
            }
        }
        card {
            addView(settingRow("设备授权","",ShareGlyph.SHIELD,"${trust.all.size} 台",R.id.menu_trust) { navigate(Detail.TRUST) })
            if(clipboardHost) {
                divider(this)
                addView(settingRow("跨网设备（iPad / Web）","远程设备也会作为独立剪贴板来源",ShareGlyph.DEVICES) { openWebDevices() })
            }
            if(!managedConnections()) {
                divider(this)
                addView(settingRow("连接帮助","",ShareGlyph.DEVICES,rowId=R.id.menu_help) { navigate(Detail.HELP) })
            }
        }
    }
    private fun settingsPage() {
        card {
            addView(settingRow("外观","",ShareGlyph.SUN,themeLabel(),R.id.menu_appearance) { navigate(Detail.APPEARANCE) })
        }
        card {
            addView(settingRow("接收缓存","",ShareGlyph.FILE,size(cacheBytes()),R.id.menu_cache) { navigate(Detail.CACHE) })
            divider(this)
            addView(settingRow("版本与更新","",ShareGlyph.SYSTEM,if(release?.let { it.build>ShareUpdates.BUILD }==true) "有新版本" else ShareUpdates.VERSION,R.id.menu_updates) { navigate(Detail.UPDATES) })
        }
        card {
            addView(settingRow("共享说明","",ShareGlyph.SHIELD,rowId=R.id.menu_sharing) { navigate(Detail.SHARING) })
            divider(this)
            addView(settingRow("连接帮助","",ShareGlyph.DEVICES,rowId=R.id.menu_help) { navigate(Detail.HELP) })
        }
        body.addView(text("FloatClip Share · ${ShareUpdates.VERSION} 预览版",12,p.muted).apply { gravity=Gravity.CENTER; setPadding(0,dp(12),0,dp(8)) })
    }
    private fun appearancePage() {
        intro("选择适合你的显示方式")
        card {
            val mode=prefs.getString("theme","system")
            listOf(Triple("system","跟随系统",ShareGlyph.SYSTEM),Triple("light","浅色模式",ShareGlyph.SUN),Triple("dark","深色模式",ShareGlyph.MOON)).forEachIndexed { index,(id,label,glyph) ->
                if(index>0) space(this,4)
                val selected=mode==id
                val row=LinearLayout(uiContext).apply {
                    this.id=when(id) { "light" -> R.id.theme_light; "dark" -> R.id.theme_dark; else -> R.id.theme_system }
                    gravity=Gravity.CENTER_VERTICAL; minimumHeight=dp(56); setPadding(dp(12),dp(10),dp(12),dp(10))
                    background=ripple(if(selected) p.tint else p.card,12)
                    contentDescription=label; buttonSemantics(this)
                    accessibilityDelegate=object:View.AccessibilityDelegate() {
                        override fun onInitializeAccessibilityNodeInfo(host:View,info:AccessibilityNodeInfo) {
                            super.onInitializeAccessibilityNodeInfo(host,info); info.className=RadioButton::class.java.name; info.isCheckable=true; info.isChecked=selected
                        }
                    }
                    setOnClickListener { if(!selected) { prefs.edit().putString("theme",id).apply(); build() } }
                }
                row.addView(icon(glyph,if(selected) p.blue else p.muted),LinearLayout.LayoutParams(dp(22),dp(22)).apply { marginEnd=dp(12) })
                row.addView(text(label,15,if(selected) p.blue else p.text,selected).apply { importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(0,-2,1f))
                row.addView(TextView(uiContext).apply { text=if(selected) "●" else "○"; textSize=20f; setTextColor(if(selected) p.blue else p.muted); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(24),-2))
                addView(row)
            }
        }
    }
    private fun cacheBytes()=cache.listFiles()?.sumOf { it.length() } ?: 0L
    private fun themeLabel()=when(prefs.getString("theme","system")) { "light" -> "浅色"; "dark" -> "深色"; else -> "跟随系统" }
    private fun cachePage() {
        card {
            addView(text(size(cacheBytes()),28,p.text,true))
            addView(text("接收与发送的临时缓存",13,p.muted).apply { setPadding(0,dp(8),0,0) })
        }
        card {
            addView(text("已另存的文件会保留。清理后，尚未另存的接收文件将无法从最近共享中打开。",14,p.muted))
            button("清理缓存",false) {
                if(session!=null) note("请先断开连接") else dialog().setTitle("清理接收缓存？").setMessage("尚未另存的接收文件将被清理。")
                    .setNegativeButton("取消",null).setPositiveButton("清理") { _,_ -> cache.listFiles()?.forEach { it.delete() }; inbox.clear(); render() }.show()
            }
        }
    }
    private fun updatesPage() {
        card {
            cardTitle("FloatClip Share", "${ShareUpdates.VERSION} · 共享预览版")
            addView(text(updateMessage,13,p.muted).apply { setPadding(0,dp(14),0,0); accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE })
            button(if(updateBusy) "正在检查…" else "检查更新",false) { checkUpdate() }.apply { id=R.id.check_updates; isEnabled=!updateBusy }
            release?.takeIf { it.build>=ShareUpdates.BUILD }?.let { value -> button(if(value.build>ShareUpdates.BUILD) "下载 ${value.version} 更新" else "下载当前版本",true) { openDownload(value.url) } }
        }
        intro("检查后，由你决定是否下载和安装。")
    }
    private fun trustPage() {
        card {
            cardTitle("已授权 ${trust.all.size} 台设备", "首次核对识别码后自动记住")
            addView(text("记住的设备可直接重连；移除授权后，需要再次核对识别码。",14,p.muted).apply { setPadding(0,dp(14),0,0) })
            if(trust.all.isNotEmpty()) button("移除所有设备授权",false) {
                dialog().setTitle("移除所有设备授权？").setMessage("所有已记住的电脑都需要重新配对。")
                    .setNegativeButton("取消",null).setPositiveButton("移除授权") { _,_ ->
                        if(managedConnections()&&clearManagedTrust()) render()
                        else {
                            disconnect()
                            trust.edit().clear().apply()
                            render()
                        }
                    }.show()
            }
        }
    }
    private fun helpPage() {
        card {
            if(managedConnections()) {
                step(this,"1","在设置中开启剪贴板共享")
                step(this,"2","首次添加设备时保持此页打开，蓝牙优先，无需同一 Wi-Fi")
                step(this,"3","核对识别码后授权，之后按需通过蓝牙或局域网自动重连")
            } else {
                step(this,"1","手机保持 FloatClip 前台")
                step(this,"2","在电脑设备页查找手机")
                step(this,"3","首次核对两端识别码后允许")
            }
        }
        card {
            addView(text("找不到设备时",16,p.text,true))
            addView(text(if(managedConnections()) "优先确认两端蓝牙已在系统设置中完成配对。局域网发现仅作为备用，不要求手机和电脑连接同一 Wi-Fi。" else "确认两端连接同一 Wi-Fi。校园网隔离设备时，可让电脑连接手机热点。",14,p.muted).apply { setPadding(0,dp(10),0,0) })
            divider(this)
            addView(settingRow("网络与备用连接","选择地址、邀请链接与诊断",ShareGlyph.DEVICES,rowId=R.id.menu_advanced) { navigate(Detail.ADVANCED) })
        }
    }
    private fun advancedPage() {
        val ips=addresses()
        if(managedConnections()) {
            card {
                cardTitle("局域网备用", "蓝牙首配无需局域网；这里仅显示可用的 Wi-Fi / 热点地址")
                addView(text(if(ips.isEmpty()) "暂无可用局域网地址" else ips.joinToString("\n"),14,p.muted).apply { setPadding(0,dp(12),0,0) })
                button("开放 90 秒配对窗口",false) { beginManagedPairing() }
            }
            card {
                cardTitle("备用邀请", "持续剪贴板同步不回退到旧的临时 Share 会话")
                addView(text("附近发现不可用时请先检查同一局域网、AP 隔离或电脑端 TUN 路由；临时邀请链接暂不用于持久授权和自动重连。",14,p.muted).apply { setPadding(0,dp(12),0,0) })
            }
            card {
                addView(text("代理影响连接时，可在电脑设备页查看网络诊断。",14,p.muted))
                button("打开连接诊断",false) { startActivity(Intent(this@ShareActivity,ProbeActivity::class.java)) }
            }
            return
        }
        var selected=prefs.getString("address",null)?.takeIf { it in ips } ?: ips.firstOrNull()
        card {
            cardTitle("连接地址", "仅在默认连接方式不可用时调整")
            if(ips.size>1) {
                val selector=Spinner(uiContext)
                selector.adapter=object:ArrayAdapter<String>(uiContext,android.R.layout.simple_spinner_dropdown_item,ips) {
                    override fun getView(position:Int,convertView:View?,parent:ViewGroup):View = super.getView(position,convertView,parent).apply { (this as TextView).setTextColor(p.text) }
                    override fun getDropDownView(position:Int,convertView:View?,parent:ViewGroup):View = super.getDropDownView(position,convertView,parent).apply { (this as TextView).setTextColor(p.text); setBackgroundColor(p.card) }
                }
                selector.setSelection(ips.indexOf(selected).coerceAtLeast(0))
                selector.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent:AdapterView<*>?) { }
                    override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) { selected=ips[position]; prefs.edit().putString("address",selected).apply() }
                }
                addView(selector,LinearLayout.LayoutParams(-1,dp(48)).apply { topMargin=dp(12) })
            } else addView(text(selected ?: "暂无可用 Wi-Fi 或热点地址",14,p.muted).apply { setPadding(0,dp(12),0,0) })
            button("使用此地址重新发现",false) { selected?.let { listen(it) } ?: note("请先连接 Wi-Fi 或开启热点") }
        }
        card {
            cardTitle("备用邀请", "附近发现不可用时使用，90 秒有效")
            if(session==null) {
                button("生成邀请链接",false) { selected?.let { listen(it,false) } ?: note("没有可用网络") }
                if(invitation.isNotEmpty()) button("复制邀请链接",true) {
                    copyText(invitation); note("已复制备用邀请")
                }
            } else addView(text("请先断开当前连接",13,p.muted).apply { setPadding(0,dp(12),0,0) })
        }
        card {
            addView(text("代理影响连接时，可在电脑设备页查看网络诊断。",14,p.muted))
            button("打开连接诊断",false) { startActivity(Intent(this@ShareActivity,ProbeActivity::class.java)) }
        }
    }
    private fun sharingPage() {
        if(managedConnections()) {
            card {
                cardTitle("已授权设备自动重连", "连接生命周期由剪贴板共享服务管理")
                addView(text("新复制的文字按共享开关实时发送；接收内容先进入 FloatClip 历史，并可选择写入系统剪贴板。",14,p.muted).apply { setPadding(0,dp(14),0,0) })
            }
            card {
                cardTitle("Android 11 剪贴板限制", "后台连接不等于后台可读取其他应用的剪贴板")
                addView(text("在其他应用前台复制时，系统可能不允许 FloatClip 读取内容；连接和接收仍可保持。该行为需要在当前 OriginOS 设备上继续验收。",14,p.muted).apply { setPadding(0,dp(14),0,0) })
            }
        } else {
            card {
                cardTitle("由你主动分享", "文字粘贴和文件选择均由你发起")
                addView(text("内容加密直传。收到文字后手动复制，收到文件先确认接收，再选择保存位置。",14,p.muted).apply { setPadding(0,dp(14),0,0) })
            }
            card {
                cardTitle("保持前台连接", "离开应用会暂停，系统文件选择期间除外")
                addView(text("空闲 3 分钟或连接满 10 分钟后需重新连接。单个文件最大 64 MiB，传输上限 2 MiB/s。",14,p.muted).apply { setPadding(0,dp(14),0,0) })
            }
        }
    }
    private fun listen(ip: String, nearby: Boolean = true) {
        if(managedConnections()) {
            if(nearby) beginManagedPairing()
            else note("持续剪贴板同步不使用临时邀请，请通过附近设备完成授权")
            return
        }
        disconnect(); val generation=epoch
        val messages=object:ShareSession.Events {
            override fun text(id:UUID,value:String,outgoing:Boolean)=ui { if(generation==epoch) { sharedText(id,value,outgoing,pair?.peerIdentity() ?: ""); add(Received(text=value,outgoing=outgoing)); note(if(outgoing) "文字已送达" else "收到文字"); if(tab==0) contentChanged() } }
            override fun offered(value:ShareWire.Offer)=ui { if(generation==epoch) { offer=value; if(tab==0) contentChanged() else note("收到文件请求，请前往共享页") } }
            override fun progress(id:UUID,name:String,done:Long,total:Long,outgoing:Boolean)=ui { if(generation==epoch) { progress="${if(outgoing) "发送" else "接收"} $name · ${if(total==0L) 100 else done*100/total}%"; if(::progressView.isInitialized) { progressView.text=progress; progressView.visibility=View.VISIBLE } } }
            override fun file(value:ShareWire.Offer,local:File,outgoing:Boolean)=ui { if(generation==epoch) { add(Received(file=if(outgoing) null else local,name=value.name,text=null,outgoing=outgoing)); progress="${value.name} · 已校验完成"; if(outgoing && local.name.startsWith("out-")) local.delete(); if(tab==0) contentChanged() } }
            override fun notice(message:String)=ui { if(generation==epoch) { progress=message; if(::progressView.isInitialized) { progressView.text=message; progressView.visibility=if(message.isEmpty()) View.GONE else View.VISIBLE } } }
            override fun closed()=ui { if(generation==epoch) { session=null; invitation=""; offer=null; pairDialog?.dismiss(); pairDialog=null; pair?.close(); pair=null; discovering=false; status="连接已结束，前往设备页重连"; if(::connectionTitleView.isInitialized) connectionTitleView.text="尚未连接"; if(::statusView.isInitialized) statusView.text=status; contentChanged() } }
        }
        phase="准备连接"; status=if(nearby) "正在允许附近设备连接" else "正在生成备用邀请"
        if(nearby) {
            val service=LanPair(cache,trust,object:LanPair.Events {
                override fun available(address:String)=ui { if(generation==epoch && foreground) { discovering=true; status="在 Windows 点击此手机即可连接"; render() } }
                override fun verify(code:String,answer:java.util.function.Consumer<Boolean>)=ui {
                    if(generation!=epoch || !foreground) { answer.accept(false); return@ui }
                    pairDialog=dialog().setTitle("允许 Windows 连接？")
                        .setMessage("核对两端的识别码是否完全相同：\n\n$code\n\n确认后记住此电脑；以后可以直接重连。")
                        .setNegativeButton("拒绝") { _,_ -> answer.accept(false) }
                        .setPositiveButton("相同，允许") { _,_ -> answer.accept(true) }
                        .setOnCancelListener { answer.accept(false) }.show()
                }
                override fun connected(value:ShareSession)=ui { if(generation==epoch && foreground) { pairDialog?.dismiss(); pairDialog=null; session=value; discovering=false; status="已记住设备 · 加密直连"; switchTab(0) } else value.close() }
                override fun stopped(message:String)=ui { if(generation==epoch) { pairDialog?.dismiss(); pairDialog=null; pair=null; discovering=false; session=null; status=message; render() } }
            },messages)
            pair=service; service.listen(ip)
        } else {
            transport=LanShare(cache,object:LanShare.Events {
                override fun invitation(code:String)=ui { if(generation==epoch && foreground) { invitation=code; status="备用邀请 90 秒有效"; render() } }
                override fun connected(value:ShareSession)=ui { if(generation==epoch && foreground) { session=value; invitation=""; status="临时设备 · 加密直连"; switchTab(0) } else value.close() }
                override fun stopped(message:String)=ui { if(generation==epoch) { invitation=""; session=null; status=message; render() } }
            },messages)
            transport.listen(ip)
        }
        render()
    }
    private fun disconnect(message:String="添加设备，开始共享") {
        if(managedConnections()&&pauseManagedConnections()){epoch++;session=null;discovering=false;status="共享已暂停";return}
        epoch++; pairDialog?.dismiss(); pairDialog=null; pair?.close(); pair=null; discovering=false; if(::transport.isInitialized) transport.close(); session=null; invitation=""; offer=null; phase="未连接"; status=message
    }
    private fun checkUpdate() {
        if(updateBusy) return
        updateBusy=true; updateMessage="正在检查更新…"; release=null; render()
        Thread {
            try {
                val found=ShareUpdates.check()
                ui { updateBusy=false; release=found; updateMessage=(if(found.build>ShareUpdates.BUILD) "发现新版本 ${found.version}" else if(found.build==ShareUpdates.BUILD) "已是最新版本 ${ShareUpdates.VERSION}" else "当前版本 ${ShareUpdates.VERSION}，更新源尚未收录此预览包")+"\n"+found.notes; if(tab==2 || detail==Detail.UPDATES) render() }
            } catch(_:Exception) { ui { updateBusy=false; updateMessage="检查失败，请确认网络后重试"; if(tab==2 || detail==Detail.UPDATES) render() } }
        }.start()
    }
    private fun openDownload(url:String) {
        try { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) } catch(_:Exception) { note("没有可用浏览器，请稍后重试") }
    }
    private fun contentChanged() {
        if(tab==0 && detail==Detail.ROOT && currentFocus is EditText) {
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
    private fun intro(value:String) { body.addView(text(value,13,p.muted).apply { setPadding(dp(2),dp(4),dp(2),dp(16)) }) }
    private fun section(title:String,subtitle:String,small:Boolean=false) {
        body.addView(text(title,if(small) 17 else 22,p.text,true).apply { setPadding(dp(2),dp(4),0,dp(12)) })
        if(subtitle.isNotEmpty()) intro(subtitle)
    }
    private fun card(content:LinearLayout.()->Unit) {
        body.addView(column().apply { background=shape(p.card,ShareDesign.CARD,true); elevation=dp(1).toFloat(); setPadding(dp(16),dp(16),dp(16),dp(16)); content() },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
    }
    private fun LinearLayout.cardTitle(title:String,subtitle:String) {
        addView(text(title,16,p.text,true))
        addView(text(subtitle,13,p.muted).apply { setPadding(0,dp(6),0,0) })
    }
    private fun LinearLayout.button(label:String,primary:Boolean,click:()->Unit):Button {
        val view=action(label,primary,click)
        addView(view,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) })
        return view
    }
    private fun action(label:String,primary:Boolean,click:()->Unit)=Button(uiContext).apply {
        text=label; isAllCaps=false; textSize=14f; typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(p.muted,if(primary) Color.WHITE else p.blue)))
        stateListAnimator=null; minimumWidth=0; minWidth=0; minimumHeight=dp(ShareDesign.TARGET); minHeight=dp(ShareDesign.TARGET)
        includeFontPadding=false; gravity=Gravity.CENTER; setPadding(dp(16),dp(12),dp(16),dp(12))
        backgroundTintList=null
        val states=android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled),shape(p.bg,ShareDesign.CONTROL,true))
            addState(intArrayOf(),shape(if(primary) p.primary else p.tint,ShareDesign.CONTROL))
        }
        background=android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(if(primary) c("#40FFFFFF") else c("#252563EB")),states,shape(Color.WHITE,ShareDesign.CONTROL))
        setOnClickListener { click() }
    }
    private fun settingRow(title:String,subtitle:String,glyph:ShareGlyph,value:String?=null,rowId:Int=View.NO_ID,click:()->Unit):View {
        val row=LinearLayout(uiContext).apply {
            id=rowId; gravity=Gravity.CENTER_VERTICAL; minimumHeight=dp(56); setPadding(0,dp(6),0,dp(6))
            background=ripple(p.card,12); contentDescription=listOfNotNull(title,subtitle.takeIf { it.isNotEmpty() },value).joinToString("，"); buttonSemantics(this); setOnClickListener { click() }
        }
        row.addView(icon(glyph,p.muted),LinearLayout.LayoutParams(dp(22),dp(22)).apply { marginEnd=dp(12) })
        row.addView(column().apply {
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(text(title,15,p.text,true))
            if(subtitle.isNotEmpty()) addView(text(subtitle,12,p.muted).apply { setPadding(0,dp(5),0,0) })
        },LinearLayout.LayoutParams(0,-2,1f))
        if(value!=null) row.addView(text(value,13,p.muted).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END; maxEms=9; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(-2,-2).apply { marginStart=dp(8) })
        row.addView(icon(ShareGlyph.CHEVRON,p.muted),LinearLayout.LayoutParams(dp(18),dp(18)).apply { marginStart=dp(8) })
        return row
    }
    private fun step(parent:LinearLayout,number:String,label:String) {
        val row=LinearLayout(uiContext).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(0,dp(6),0,dp(6)) }
        row.addView(text(number,12,p.blue,true).apply { gravity=Gravity.CENTER; background=shape(p.tint,14) },LinearLayout.LayoutParams(dp(28),dp(28)).apply { marginEnd=dp(12) })
        row.addView(text(label,14,p.text),LinearLayout.LayoutParams(0,-2,1f)); parent.addView(row)
    }
    private fun divider(parent:LinearLayout) { parent.addView(View(uiContext).apply { setBackgroundColor(p.line) },LinearLayout.LayoutParams(-1,dp(1)).apply { topMargin=dp(8); bottomMargin=dp(8) }) }
    private fun space(parent:LinearLayout,height:Int) { parent.addView(View(uiContext),LinearLayout.LayoutParams(1,dp(height))) }
    private fun buttonSemantics(view:View) {
        view.isFocusable=true; view.isClickable=true
        view.accessibilityDelegate=object:View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host:View,info:AccessibilityNodeInfo) { super.onInitializeAccessibilityNodeInfo(host,info); info.className=Button::class.java.name }
        }
    }
    private fun icon(glyph:ShareGlyph,color:Int)=ImageView(uiContext).apply { setImageDrawable(ShareIcon(glyph,color)); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    private fun dialog()=AlertDialog.Builder(uiContext)
    private fun ripple(color:Int,radius:Int)=android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(c("#252563EB")),shape(color,radius),shape(Color.WHITE,radius))
    private fun column()=LinearLayout(uiContext).apply { orientation=LinearLayout.VERTICAL }
    private fun text(value:String,sp:Int,color:Int,bold:Boolean=false)=TextView(uiContext).apply {
        text=value; textSize=sp.toFloat(); setTextColor(color); includeFontPadding=false
        typeface=Typeface.create(if(bold) "sans-serif-medium" else "sans-serif",Typeface.NORMAL)
        setLineSpacing(dp(2).toFloat(),1.1f)
    }
    private fun shape(color:Int,radius:Int,border:Boolean=false,borderColor:Int=p.line)=GradientDrawable().apply { setColor(color); cornerRadius=dp(radius).toFloat(); if(border) setStroke(dp(1),borderColor) }
    private fun note(message:String) { Toast.makeText(this,message,Toast.LENGTH_SHORT).show() }
    private fun ui(action:()->Unit) { runOnUiThread { if(!isDestroyed) action() } }
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun c(value:String)=Color.parseColor(value)
    private fun size(n:Long)=if(n<1024) "$n B" else String.format(Locale.ROOT,"%.1f MiB",n/1048576.0)
}
