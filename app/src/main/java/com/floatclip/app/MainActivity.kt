package com.floatclip.app

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.os.PowerManager
import com.floatclip.app.overlay.ui.EntrySwipeActions
import com.floatclip.app.overlay.ui.EntryGestureRow
import com.floatclip.app.overlay.ui.EntryLockAction
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.floatclip.app.accessibility.PasteAccessibilityService
import com.floatclip.app.backup.VaultBackupManager
import com.floatclip.app.clipboard.ClipStore
import com.floatclip.app.clipboard.ClipboardWrites
import com.floatclip.app.clipboard.ClipboardImages
import com.floatclip.app.integration.IntegrationRegistry
import com.floatclip.app.model.ClipEntry
import com.floatclip.app.overlay.ClipboardOverlayService
import com.floatclip.app.prefs.CategoryStore
import com.floatclip.app.prefs.CategoryIcons
import com.floatclip.app.prefs.OverlayPreferences
import com.floatclip.app.prefs.OverlayThemeMode
import com.floatclip.app.prefs.UpdatePreferences
import com.floatclip.app.prefs.UpdateChannel
import com.floatclip.app.security.VaultSettings
import com.floatclip.app.clipboard.ClipboardQuery
import com.floatclip.app.clipboard.ClipboardSources
import com.floatclip.app.overlay.ui.ClipboardCard
import android.text.Editable
import android.text.TextWatcher
import android.content.ClipboardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import com.floatclip.app.sync.SyncPreferences
import com.floatclip.app.sync.SyncRuntime
import com.floatclip.app.sync.ClipboardSyncService
import com.floatclip.app.ui.FluentSwitch
import com.floatclip.app.ui.FloatDialog
import com.floatclip.probe.FloatClipAccent

open class MainActivity : Activity() {
    private lateinit var categoryStore: CategoryStore
    private lateinit var clipStore: ClipStore
    private lateinit var overlayPreferences: OverlayPreferences
    private lateinit var vaultSettings: VaultSettings
    private lateinit var vaultBackupManager: VaultBackupManager
    private lateinit var content: LinearLayout
    private lateinit var colors: AppColors
    private var searchQuery: String = ""
    private var currentPage: Int = PAGE_CLIPBOARD
    private var categoryManagement = false
    private var settingsSection = ""
    private var historyCategory: String = ALL_CATEGORY
    private var sourceFilter = ""
    private var historyList: LinearLayout? = null
    private var undoEntries: List<ClipEntry> = emptyList()
    private val undoOwner="main:"+java.util.UUID.randomUUID()
    private var lastImageCapture=""
    private var undoBar: LinearLayout? = null
    private val clipboardChanged = ClipboardManager.OnPrimaryClipChangedListener { captureCurrentClipboard() }
    private val sharedHistoryChanged=object: BroadcastReceiver(){override fun onReceive(context: Context?,intent: Intent?){
        if(currentPage==PAGE_CLIPBOARD&&historyList?.isAttachedToWindow==true)refreshHistory()
        else if(currentPage==PAGE_SETTINGS&&settingsSection=="剪贴板共享"&&::content.isInitialized)render()
    }}

    private val overlayServiceIntent by lazy(LazyThreadSafetyMode.NONE) {
        Intent(this, ClipboardOverlayService::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val historyFilter=IntentFilter(ClipboardSyncService.HISTORY_CHANGED).apply{addAction(SyncRuntime.STATE_CHANGED)}
        if(android.os.Build.VERSION.SDK_INT>=33)registerReceiver(sharedHistoryChanged,historyFilter,"$packageName.permission.INTERNAL",null,Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(sharedHistoryChanged,historyFilter,"$packageName.permission.INTERNAL",null)
        if(SyncPreferences(this).enabled())SyncRuntime.refresh(this)
        IntegrationRegistry.initialize(this)
        categoryStore = CategoryStore(this)
        clipStore = ClipStore(this)
        overlayPreferences = OverlayPreferences(this)
        vaultSettings = VaultSettings(this)
        vaultBackupManager = VaultBackupManager(this)
        currentPage = if(savedInstanceState?.getInt(STATE_PAGE, PAGE_CLIPBOARD)==PAGE_SETTINGS) PAGE_SETTINGS else PAGE_CLIPBOARD
        searchQuery = savedInstanceState?.getString("searchQuery").orEmpty()
        sourceFilter = savedInstanceState?.getString("sourceFilter").orEmpty()
        settingsSection = savedInstanceState?.getString("settingsSection").orEmpty()
        if(intent?.getStringExtra("floatclip_page")=="clipboard_sync"){currentPage=PAGE_SETTINGS;settingsSection="剪贴板共享"}
        categoryManagement = savedInstanceState?.getBoolean("categoryManagement") ?: false
        render()
        getSystemService(ClipboardManager::class.java).addPrimaryClipChangedListener(clipboardChanged)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_PAGE, currentPage)
        outState.putString("settingsSection", settingsSection)
        outState.putBoolean("categoryManagement", categoryManagement)
        outState.putString("searchQuery",searchQuery)
        outState.putString("sourceFilter",sourceFilter)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Fixed Android 11 navigation")
    override fun onBackPressed() {
        when {
            currentPage == PAGE_SETTINGS && settingsSection.isNotEmpty() -> { settingsSection = ""; render() }
            currentPage == PAGE_CLIPBOARD && categoryManagement -> { categoryManagement = false; render() }
            currentPage != PAGE_CLIPBOARD -> { currentPage=PAGE_CLIPBOARD; settingsSection=""; render() }
            else -> super.onBackPressed()
        }
    }

    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent);setIntent(intent)
        if(intent.getStringExtra("floatclip_page")=="clipboard_sync") {
            currentPage=PAGE_SETTINGS;settingsSection="剪贴板共享"
            if(::content.isInitialized)render()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) {
            if(currentPage==PAGE_CLIPBOARD && currentFocus is EditText && historyList?.isAttachedToWindow==true) refreshHistory() else render()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if(hasFocus && ::clipStore.isInitialized) captureCurrentClipboard()
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); render() }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN) EntryGestureRow.closeOutside(event.rawX,event.rawY)
        return super.dispatchTouchEvent(event)
    }
    override fun onDestroy() {
        if(::clipStore.isInitialized)clipStore.retainUndoImages(undoOwner,emptyList())
        unregisterReceiver(sharedHistoryChanged)
        getSystemService(ClipboardManager::class.java).removePrimaryClipChangedListener(clipboardChanged)
        super.onDestroy()
    }
    private fun captureCurrentClipboard() {
        // A focused read is valid on Android 11; no focus stealing or background polling.
        if(!hasWindowFocus() || !::clipStore.isInitialized) return
        val manager=getSystemService(ClipboardManager::class.java)
        val clip=runCatching { manager.primaryClip }.getOrNull() ?: return
        if(ClipboardWrites.isOwnClip(this,clip.description)) return
        if(clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE",false)==true) { SyncRuntime.observe(this,clip);return }
        if(ClipboardImages.isImage(this,clip)) {
            SyncRuntime.observe(this,clip)
            val event=ClipboardImages.event(clip)
            if(event==lastImageCapture)return
            lastImageCapture=event
            ClipboardImages.captureAsync(applicationContext,clip,clipStore) { result ->
                result.onSuccess { entry ->
                    if(entry!=null) { if(!isDestroyed&&currentPage==PAGE_CLIPBOARD)refreshHistory();notifyHistoryChanged() }
                }.onFailure {
                    lastImageCapture=""
                    if(!isDestroyed)Toast.makeText(this,it.message ?: "图片未收录，请在前台重试",Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        val value=clip.takeIf { it.itemCount>0 }?.getItemAt(0)?.text?.toString()
        if(value.isNullOrBlank()) return
        runCatching {
            if(clipStore.entries().firstOrNull { it.sourceDeviceId=="local" }?.text!=value.trim()) clipStore.addText(value)
            SyncRuntime.observe(this,clip)
            if(currentPage==PAGE_CLIPBOARD && historyList?.isAttachedToWindow==true) refreshHistory()
        }
    }

    @Deprecated("Legacy activity result is sufficient for the fixed Android 11 target")
    @android.annotation.SuppressLint("WrongConstant")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_BACKUP_TREE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
        // Selecting a tree never immediately grants overwrite permission. First inspect it so a
        // surviving FloatClip.vault from a previous install cannot be destroyed by an empty/new vault.
        vaultSettings.saveBackupTreeUri(uri, writeArmed = false)
        Thread {
            val existing = vaultBackupManager.hasBackup(uri)
            val pin = vaultSettings.pin()
            val initialized = !existing && pin != null &&
                vaultBackupManager.initializeBackup(uri, pin, clipStore.exportJson())
            runOnUiThread {
                val message = when {
                    existing -> "检测到已有 FloatClip.vault；已保持只读保护，请使用“从备份恢复”确认后再启用自动写入"
                    initialized -> "加密备份已初始化，后续会自动更新"
                    pin == null -> "目录已保存；设置 6 位 PIN 后会初始化加密备份"
                    else -> "目录已保存，但初始化失败；不会覆盖任何已有备份"
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                render()
            }
        }.start()
    }

    private fun render() {
        historyList=null
        undoBar=null
        colors = resolveAppColors()
        applySystemBars()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.pageBackground)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(colors.pageBackground)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(24))
        }
        scroll.addView(
            content,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.isFocusableInTouchMode=true
        setContentView(root)

        renderHeader()
        when (currentPage) {
            PAGE_SETTINGS -> renderSettingsPage()
            else -> renderClipboardPage()
        }
        root.requestFocus()
    }

    private fun headerIcon(icon: Int, label: String, action: () -> Unit): View =
        ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(colors.primaryText)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minimumWidth=dp(48); minimumHeight=dp(48)
            contentDescription = label
            background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(colors.actionBackground), null, null)
            setOnClickListener { action() }
        }

    private fun renderHeader() {
        val nested = currentPage != PAGE_CLIPBOARD || categoryManagement
        val title = when (currentPage) {
            PAGE_CLIPBOARD -> if (categoryManagement) "分类" else "剪贴板"
            PAGE_SETTINGS -> settingsSection.ifEmpty { "设置" }
            else -> "FloatClip"
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (nested) bar.addView(headerIcon(R.drawable.ic_chevron_left, "返回") {
            onBackPressed()
        }.apply { id=R.id.clipboard_back }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(4) })
        if(!nested) bar.addView(ImageView(this).apply { setImageResource(R.drawable.ic_floatclip_app); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(28),dp(28)).apply { marginEnd=dp(10) })
        bar.addView(textView(title, 21f, true), LinearLayout.LayoutParams(0, dp(56), 1f))
        if (currentPage == PAGE_CLIPBOARD && !categoryManagement) {
            bar.addView(headerIcon(R.drawable.ic_delete, "全部清除，保留固定和置顶记录", ::clearHistory).apply {
                id=R.id.clipboard_clear
                tooltipText="全部清除（保留固定和置顶）"
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            bar.addView(headerIcon(R.drawable.ic_device_computer, "我的设备") {
                startActivity(Intent(this,DeviceActivity::class.java).putExtra("devicesOnly",true))
            }.apply { id=R.id.clipboard_devices }, LinearLayout.LayoutParams(dp(48), dp(48)))
            bar.addView(headerIcon(R.drawable.ic_folder,"共享文件") {
                startActivity(Intent(this,com.floatclip.app.files.FileActivity::class.java))
            },LinearLayout.LayoutParams(dp(48),dp(48)))
            bar.addView(headerIcon(R.drawable.ic_settings_controls,"设置") {
                currentPage=PAGE_SETTINGS; settingsSection=""; render()
            }.apply { id=R.id.clipboard_settings },LinearLayout.LayoutParams(dp(48),dp(48)))
        }
        content.addView(bar)
        spacer(8)
    }

    private fun showUsageHelp() {
        FloatDialog.show(
            this,
            "使用帮助",
            "单击复制，长按更多；左滑置顶/删除，右滑保护。\n拖动标题移动面板，点击外侧收回。\n右上角图钉用于保持面板展开。",
            negative=null,
            positive="知道了",
        )
    }

    private fun renderCategoryManager() {
        val categoryCard = card()
        val categoryInput = EditText(this).apply {
            hint = "新分类"
            isSingleLine = true
            textSize = 15f
            setTextColor(colors.primaryText)
            setHintTextColor(colors.secondaryText)
            background = roundedBackground(colors.fieldBackground, 13f)
            setPadding(dp(13), 0, dp(13), 0)
        }
        val addCategory = actionButton("添加", false) {
            if (categoryStore.add(categoryInput.text?.toString().orEmpty())) { notifyOverlayAppearanceChanged(); render() }
        }
        categoryCard.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(categoryInput, LinearLayout.LayoutParams(0, dp(44), 1f))
                addView(addCategory, LinearLayout.LayoutParams(dp(74), dp(44)).apply { marginStart = dp(8) })
            },
        )

        val categories = categoryStore.categories().filterNot { it == CategoryStore.DEFAULT_CATEGORY }
        if (categories.isNotEmpty()) {
            spacerInside(categoryCard, 8)

            val dragItems = categories.toMutableList()
            val dragList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            categories.forEach { category ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), 0, dp(7), 0)
                    background = roundedBackground(colors.fieldBackground, 13f, true)
                    val iconButton=ImageView(this@MainActivity).apply {
                        setImageResource(CategoryIcons.resource(categoryStore.iconKey(category)))
                        imageTintList=ColorStateList.valueOf(colors.accent)
                        setPadding(dp(11),dp(11),dp(11),dp(11))
                        contentDescription="更换 $category 图标"
                        setOnClickListener {
                            val labels=CategoryIcons.choices.map { it.label }
                            val selected=CategoryIcons.choices.indexOfFirst { it.key==categoryStore.iconKey(category) }
                            FloatDialog.choices(this@MainActivity,"选择“$category”的图标",labels,selected) { index ->
                                categoryStore.saveIcon(category,CategoryIcons.choices[index].key)
                                notifyOverlayAppearanceChanged()
                                render()
                            }
                        }
                    }
                    addView(iconButton,LinearLayout.LayoutParams(dp(46),dp(48)))
                    addView(textView(category, 15f, false), LinearLayout.LayoutParams(0, dp(48), 1f))
                }
                val handle = ImageView(this).apply {
                    setImageResource(R.drawable.ic_drag)
                    imageTintList = ColorStateList.valueOf(colors.secondaryText)
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    contentDescription = "拖动 $category 排序"
                }
                row.addView(handle, LinearLayout.LayoutParams(dp(48), dp(48)))
                val wrapper = EntryGestureRow(this)
                wrapper.bind(row)
                wrapper.onLongPress = {
                    FloatDialog.show(
                        this,
                        "删除分类“$category”？",
                        "词条会保留并移到未分类。",
                        negative="取消",
                        positive="删除",
                    ) {
                        clipStore.moveCategoryToDefault(category)
                        categoryStore.delete(category)
                        notifyOverlayAppearanceChanged()
                        render()
                        true
                    }
                }
                wrapper.touchPassthrough = handle
                dragList.addView(wrapper, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(6) })
                installCategoryDrag(handle, wrapper, dragList, dragItems, category)
            }
            categoryCard.addView(dragList)
        }
        addCard(categoryCard)

    }

    private fun renderClipboardPage() {
        if (categoryManagement) { renderCategoryManager(); return }
        val searchCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(colors.fieldBackground, 13f)
        }
        searchCard.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_search); imageTintList=ColorStateList.valueOf(colors.secondaryText)
            setPadding(dp(12),dp(12),dp(12),dp(12)); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },LinearLayout.LayoutParams(dp(48),dp(48)))
        val search = EditText(this).apply {
            id=R.id.clipboard_search
            hint = "搜索剪贴板"
            isSingleLine = true
            textSize = 16f
            setText(searchQuery)
            setSelection(text.length)
            setTextColor(colors.primaryText)
            setHintTextColor(colors.secondaryText)
            background = null
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setPadding(0, 0, dp(4), 0)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    searchQuery = text.toString().trim()
                    refreshHistory()
                    true
                } else false
            }
        }
        searchCard.addView(search, LinearLayout.LayoutParams(0, dp(48), 1f))
        val clear=headerIcon(R.drawable.ic_close,"清空搜索") { search.setText("") }.apply { visibility=if(searchQuery.isEmpty()) View.GONE else View.VISIBLE }
        searchCard.addView(clear,LinearLayout.LayoutParams(dp(48),dp(48)))
        addCard(searchCard)
        search.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                searchQuery=s?.toString().orEmpty(); clear.visibility=if(searchQuery.isEmpty()) View.GONE else View.VISIBLE
                refreshHistory()
            }
            override fun afterTextChanged(s:Editable?)=Unit
        })
        val filterScroll=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false }
        val filters=LinearLayout(this).apply { id=R.id.clipboard_sources; gravity=Gravity.CENTER_VERTICAL; setPadding(0,dp(8),0,dp(8)) }
        filters.addView(headerIcon(R.drawable.ic_image,"添加图片或截图") {
            startActivity(Intent(this,com.floatclip.app.clipboard.ImageImportActivity::class.java))
        },LinearLayout.LayoutParams(dp(48),dp(48)))
        val sources=ClipboardSources.tabs(this,clipStore.entries())
        if(sourceFilter !in sources.map { it.id })sourceFilter=""
        sources.forEach { source ->
            val key=source.id;val name=source.name
            filters.addView(textView(name,14f,false,if(sourceFilter==key) colors.accent else colors.secondaryText).apply {
                gravity=Gravity.CENTER; setPadding(dp(13),0,dp(13),0); minimumHeight=dp(44)
                background=roundedBackground(if(sourceFilter==key) colors.actionBackground else Color.TRANSPARENT,11f)
                isSelected=sourceFilter==key; contentDescription="来源：$name"
                setOnClickListener { sourceFilter=key; historyCategory=ALL_CATEGORY; render() }
            },LinearLayout.LayoutParams(-2,dp(44)).apply { marginEnd=dp(4) })
        }
        filterScroll.addView(filters);content.addView(filterScroll)
        undoBar=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; visibility=View.GONE }
        content.addView(undoBar)
        historyList=LinearLayout(this).apply { id=R.id.clipboard_list; orientation=LinearLayout.VERTICAL }
        content.addView(historyList)
        refreshHistory(); showUndo()
    }

    private fun refreshHistory() {
        val target=historyList ?: return
        target.removeAllViews()
        val visibleHistory=clipStore.entries().filter { ClipboardQuery.matches(it,searchQuery,sourceFilter) }
            .filter { historyCategory==ALL_CATEGORY || it.category==historyCategory }.take(100)
        if (visibleHistory.isEmpty()) {
            target.addView(textView(if(searchQuery.isEmpty()) "这里还没有记录" else "没有匹配的记录",15f,false,colors.secondaryText).apply { gravity=Gravity.CENTER; setPadding(0,dp(28),0,dp(20)) })
            if(searchQuery.isEmpty() && sourceFilter.isEmpty()) target.addView(compactTextAction("收录当前剪贴板") { captureCurrentClipboard();refreshHistory() })
        } else {
            visibleHistory.forEach { addHistoryCard(it) }
        }
    }

    private fun renderPermissionGuide() {
        val overlay = Settings.canDrawOverlays(this)
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        addCard(card().apply {
            addView(settingRow("悬浮窗", "", if (overlay) "已授权" else "去授权", ::openOverlayPermission))
            addView(divider())
            addView(settingRow("无障碍", "可选，用于直接粘贴", if (PasteAccessibilityService.isConnected()) "已连接" else "设置", ::openAccessibilitySettings))
            addView(divider())
            addView(settingRow("电池优化", "", if (battery) "已放宽" else "设置") {
                FloatDialog.show(
                    this@MainActivity,
                    "电池优化",
                    "在系统列表找到 FloatClip，选择不优化，可减少后台被暂停。",
                    negative="取消",
                    positive="去设置",
                ) {
                    openSystemSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    true
                }
            })
            addView(divider())
            addView(settingRow("后台与自启动", "系统中手动设置", "›", ::openAppDetailsSettings))
        })
        spacer(12)
    }

    private fun openSystemSettings(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
                .onFailure { Toast.makeText(this, "请在系统设置中打开 FloatClip 应用详情", Toast.LENGTH_LONG).show() }
        }
    }

    private fun renderSettingsPage() {
        if (settingsSection.isNotEmpty()) {
            when (settingsSection) {
                "外观" -> renderAppearanceSettings()
                "备份与恢复" -> renderBackupSettings()
                "剪贴板共享" -> renderSyncSettings()
                else -> {
                    renderPermissionGuide()
                    addCard(card().apply {
                        val keepAlive = overlayPreferences.keepAliveEnabled()
                        addView(settingRow("清理最近任务后恢复悬浮窗", "强行停止应用后 Android 不允许自动复活", if (keepAlive) "已开启" else "已关闭") {
                            overlayPreferences.saveKeepAliveEnabled(!keepAlive)
                            render()
                        })
                        addView(divider())
                        addView(settingRow(
                            "详细常驻通知",
                            "关闭后仍保留 Android 11 要求的静默前台通知",
                            if(overlayPreferences.detailedNotification()) "已开启" else "已关闭",
                        ) {
                            overlayPreferences.saveDetailedNotification(!overlayPreferences.detailedNotification())
                            notifyOverlayAppearanceChanged()
                            render()
                        })
                        addView(divider())
                        addView(settingRow("系统通知设置", "", "›", ::openNotificationSettings))
                    })
                }
            }
            return
        }
        addCard(card().apply { addView(themeSelector()) })
        spacer(12)
        addCard(card().apply {
            addView(settingRow("悬浮窗","",if(overlayPreferences.overlayEnabled()) "已开启" else "已关闭") {
                if(overlayPreferences.overlayEnabled()) stopOverlay() else startOverlay()
            })
            addView(divider())
            addView(settingRow("设备特征色","",if(overlayPreferences.sourceColors()) "已开启" else "已关闭") { overlayPreferences.saveSourceColors(!overlayPreferences.sourceColors());render();notifyOverlayAppearanceChanged() })
            addView(divider())
            addView(settingRow("半透明色块","",if(overlayPreferences.translucentCards()) "已开启" else "已关闭") { overlayPreferences.saveTranslucentCards(!overlayPreferences.translucentCards());render();notifyOverlayAppearanceChanged() })
        })
        spacer(12)
        addCard(card().apply {
            addView(settingRow("外观", "", "›", R.drawable.ic_appearance) { settingsSection="外观"; render() })
            addView(divider())
            addView(settingRow("剪贴板共享", "", "›", R.drawable.ic_share) { settingsSection="剪贴板共享"; render() })
            addView(divider())
            addView(settingRow("权限与后台", "", "›", R.drawable.ic_shield) { settingsSection="权限与后台"; render() })
            addView(divider())
            addView(settingRow("备份与恢复", "", "›", R.drawable.ic_backup) { settingsSection="备份与恢复"; render() })
            addView(divider())
            addView(settingRow("分类","","›", R.drawable.ic_category) { currentPage=PAGE_CLIPBOARD;categoryManagement=true;render() })
            addView(divider())
            val updatePrefs=UpdatePreferences(this@MainActivity)
            addView(settingRow("更新通道",updatePrefs.channel().label,"›",R.drawable.ic_update) {
                val values=UpdateChannel.values().toList()
                FloatDialog.choices(
                    this@MainActivity,
                    "更新通道",
                    values.map { it.label },
                    values.indexOf(updatePrefs.channel()),
                ) { index ->
                    updatePrefs.saveChannel(values[index])
                    render()
                }
            })
            addView(divider())
            addView(settingRow("检查更新","","›", R.drawable.ic_update) { checkClientUpdate() })
            addView(divider())
            addView(settingRow("关于", "", "›", R.drawable.ic_info) {
                FloatDialog.show(
                    this@MainActivity,
                    "FloatClip ${versionName()}",
                    "Android 悬浮剪贴板",
                    negative="使用帮助",
                    positive="关闭",
                    onNegative={ showUsageHelp() },
                )
            })
        })
    }

    private fun renderSyncSettings() {
        val prefs=SyncPreferences(this)
        addCard(card().apply {
            fun toggle(label: String,on: Boolean,change: ()->Unit) {
                if(childCount>0)addView(divider())
                addView(settingRow(label,"",if(on) "已开启" else "已关闭") {runCatching{change();SyncRuntime.refresh(this@MainActivity)}.onFailure{Toast.makeText(this@MainActivity,"共享配置未保存",Toast.LENGTH_SHORT).show()};render()})
            }
            toggle("共享剪贴板",prefs.enabled()){prefs.save(enabled=!prefs.enabled())}
            toggle("自动连接已授权设备",prefs.reconnect()){prefs.save(reconnect=!prefs.reconnect())}
            toggle("清理最近任务后保持共享服务",prefs.keepAlive()){prefs.saveKeepAlive(!prefs.keepAlive())}
            toggle("ADB 后台采集增强",prefs.backgroundCapture()){prefs.saveBackgroundCapture(!prefs.backgroundCapture())}
            toggle("发送新复制的文字",prefs.sending()){prefs.save(sending=!prefs.sending())}
            toggle("接收文字到历史",prefs.receiving()){prefs.save(receiving=!prefs.receiving())}
            toggle("接收后写入系统剪贴板",prefs.applying()){prefs.save(applying=!prefs.applying())}
        })
        SyncRuntime.current()?.peers()?.forEach {peer ->
            spacer(12);addCard(card().apply {addView(settingRow(peer.device.name,"",if(peer.enabled)"共享中" else "已暂停"){SyncRuntime.current()?.peerEnabled(peer.key,!peer.enabled);window.decorView.postDelayed({render()},120)})})
        }
        spacer(12)
        addCard(card().apply {addView(textView(SyncRuntime.current()?.status?:"共享关闭时保留配对授权",14f,false,colors.secondaryText));addView(textView(if(SyncRuntime.current()?.backgroundCaptureConnected==true)"ADB 后台采集已连接；离开 App 后的新复制也可同步。" else if(prefs.backgroundCapture())"后台采集增强等待 ADB 激活；手机重启后需要重新激活。" else "普通模式在打开 App 或悬浮剪贴板时读取；可选择 ADB 增强后台采集。",13f,false,colors.secondaryText))})
    }

    private fun renderAppearanceSettings() {
        sectionTitle("主题")
        addCard(card().apply {
            addView(accentSelector())
            addView(divider())
            addView(settingRow(
                "边缘细线",
                "卡片与面板使用低对比度 1dp 边线",
                if(overlayPreferences.borderEnabled()) "已开启" else "已关闭",
            ) {
                overlayPreferences.saveBorderEnabled(!overlayPreferences.borderEnabled())
                notifyOverlayAppearanceChanged()
                render()
            })
            if(overlayPreferences.borderEnabled()) {
                addView(divider())
                addView(sliderSetting(
                    "边线强度",5,60,overlayPreferences.borderAlphaPercent(),"%"
                ) {
                    overlayPreferences.saveBorderAlphaPercent(it)
                    notifyOverlayAppearanceChanged()
                })
            }
        })

        sectionTitle("悬浮球")
        addCard(
            card().apply {
                addView(
                    sliderSetting(
                        "悬浮球大小",
                        42,
                        64,
                        overlayPreferences.bubbleSizeDp(),
                        "dp",
                    ) { overlayPreferences.saveBubbleSizeDp(it); notifyOverlayAppearanceChanged() },
                )
                addView(divider())
                addView(
                    sliderSetting(
                        "悬浮球透明度",
                        35,
                        100,
                        overlayPreferences.bubbleAlphaPercent(),
                        "%",
                    ) { overlayPreferences.saveBubbleAlphaPercent(it); notifyOverlayAppearanceChanged() },
                )
                addView(divider())
                addView(
                    sliderSetting(
                        "滑动灵敏度",
                        30,
                        120,
                        overlayPreferences.bubbleMotionSensitivityPercent(),
                        "%",
                    ) { overlayPreferences.saveBubbleMotionSensitivityPercent(it); notifyOverlayAppearanceChanged() },
                )
                addView(divider())
                addView(
                    sliderSetting(
                        "贴边半隐藏",
                        0,
                        55,
                        overlayPreferences.edgeHidePercent(),
                        "%",
                    ) { overlayPreferences.saveEdgeHidePercent(it); notifyOverlayAppearanceChanged() },
                )
            },
        )

        sectionTitle("展开面板")
        addCard(
            card().apply {
                addView(
                    sliderSetting(
                        "面板透明度",
                        45,
                        100,
                        overlayPreferences.panelAlphaPercent(),
                        "%",
                    ) { overlayPreferences.savePanelAlphaPercent(it); notifyOverlayAppearanceChanged() },
                )
                addView(divider())
                addView(
                    sliderSetting(
                        "区块透明度",
                        45,
                        100,
                        overlayPreferences.cardAlphaPercent(),
                        "%",
                    ) { overlayPreferences.saveCardAlphaPercent(it); notifyOverlayAppearanceChanged() },
                )
                addView(divider())
                addView(
                    sliderSetting(
                        "磨砂雾化",
                        0,
                        100,
                        overlayPreferences.frostStrengthPercent(),
                        "%",
                    ) { overlayPreferences.saveFrostStrengthPercent(it); notifyOverlayAppearanceChanged() },
                )
                addView(textView("Android 11 普通悬浮层使用半透明雾化；系统级实时背景模糊需要特权窗口能力。",12f,false,colors.secondaryText).apply {
                    setPadding(0,dp(2),0,dp(8))
                })
                addView(divider())
                addView(
                    sliderSetting(
                        "面板宽度",
                        280,
                        420,
                        overlayPreferences.panelWidthDp(),
                        "dp",
                    ) { overlayPreferences.savePanelWidthDp(it); notifyOverlayAppearanceChanged() },
                )
                addView(divider())
                addView(
                    sliderSetting(
                        "面板高度",
                        300,
                        620,
                        overlayPreferences.panelHeightDp(),
                        "dp",
                    ) { overlayPreferences.savePanelHeightDp(it); notifyOverlayAppearanceChanged() },
                )
                addView(divider())
                addView(
                    segmentedSetting(
                        "条目高度",
                        listOf(1 to "1 行", 2 to "2 行", 3 to "3 行"),
                        overlayPreferences.entryLineCount(),
                    ) { lines ->
                        overlayPreferences.saveEntryLineCount(lines)
                        notifyOverlayAppearanceChanged()
                        render()
                    },
                )
                spacerInside(this, 8)
                addView(
                    compactTextAction("恢复默认外观") {
                        overlayPreferences.resetAppearance()
                        notifyOverlayAppearanceChanged()
                        render()
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)),
                )
            },
        )

        sectionTitle("动画与交互")
        addCard(card().apply {
            addView(sliderSetting(
                "卡片按压缩放",94,100,overlayPreferences.cardPressScalePercent(),"%"
            ) { overlayPreferences.saveCardPressScalePercent(it) })
            addView(divider())
            addView(sliderSetting(
                "悬浮球按压缩放",90,100,overlayPreferences.bubblePressScalePercent(),"%"
            ) { overlayPreferences.saveBubblePressScalePercent(it); notifyOverlayAppearanceChanged() })
            addView(divider())
            addView(sliderSetting(
                "面板拖动缩放",97,100,overlayPreferences.panelPressScalePercent(),"%"
            ) { overlayPreferences.savePanelPressScalePercent(it); notifyOverlayAppearanceChanged() })
            addView(divider())
            addView(sliderSetting(
                "打开/收回动画",120,480,overlayPreferences.transitionDurationMs(),"ms"
            ) { overlayPreferences.saveTransitionDurationMs(it); notifyOverlayAppearanceChanged() })
            addView(divider())
            addView(sliderSetting(
                "开关动画",80,300,overlayPreferences.switchAnimationMs(),"ms"
            ) { overlayPreferences.saveSwitchAnimationMs(it) })
        })

    }

    private fun renderBackupSettings() {
        addCard(
            card().apply {
                addView(settingRow(
                    "6 位数字 PIN",
                    if (vaultSettings.hasPin()) "已设置" else "",
                    if (vaultSettings.hasPin()) "修改" else "设置",
                    ::showSetPinDialog,
                ))
                addView(divider())
                val backupTreeSelected = vaultSettings.backupTreeUri() != null
                val backupDetail = when {
                    !backupTreeSelected -> "未选择"
                    vaultSettings.backupWriteArmed() -> "自动备份已开启"
                    vaultSettings.hasPin() -> "写保护：请先恢复备份"
                    else -> "请先设置 PIN"
                }
                addView(settingRow(
                    "备份目录",
                    backupDetail,
                    if (backupTreeSelected) "更改" else "选择",
                    ::chooseBackupDirectory,
                ))
                addView(divider())
                addView(settingRow(
                    "从备份恢复",
                    "需要原目录和 PIN",
                    "恢复",
                    ::showRestorePinDialog,
                ))
            },
        )

    }

    private fun themeSelector(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val selected = overlayPreferences.themeMode()
        listOf(
            OverlayThemeMode.SYSTEM to "跟随系统",
            OverlayThemeMode.LIGHT to "浅色",
            OverlayThemeMode.DARK to "东京夜色",
        ).forEachIndexed { index, (mode, label) ->
            root.addView(
                themeChoice(label, selected == mode) {
                    overlayPreferences.saveThemeMode(mode)
                    notifyOverlayAppearanceChanged()
                    render()
                },
                LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    if (index > 0) marginStart = dp(7)
                },
            )
        }
        return root
    }

    private fun themeChoice(label: String, selected: Boolean, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(if (selected) colors.accent else colors.primaryText)
        background = roundedBackground(if (selected) colors.actionBackground else colors.fieldBackground, 12f, true)
        setOnClickListener { action() }
    }

    private fun accentSelector(): View = LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL
        setPadding(0,dp(5),0,dp(7))
        addView(textView("主题颜色",14.5f,false),LinearLayout.LayoutParams(-1,dp(30)))
        addView(HorizontalScrollView(this@MainActivity).apply {
            isHorizontalScrollBarEnabled=false
            overScrollMode=View.OVER_SCROLL_NEVER
            addView(LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.HORIZONTAL
                FloatClipAccent.choices.forEachIndexed { index,choice ->
                    val selected=FloatClipAccent.normalize(overlayPreferences.accentTheme())==choice.id
                    addView(TextView(this@MainActivity).apply {
                        text=choice.label
                        textSize=13f
                        gravity=Gravity.CENTER
                        val color=if(colors.dark) choice.dark else choice.light
                        setTextColor(if(selected) color else colors.secondaryText)
                        background=roundedBackground(
                            if(selected) colors.actionBackground else colors.fieldBackground,12f,true
                        )
                        setOnClickListener {
                            overlayPreferences.saveAccentTheme(choice.id)
                            notifyOverlayAppearanceChanged()
                            render()
                        }
                    },LinearLayout.LayoutParams(dp(62),dp(42)).apply { if(index>0)marginStart=dp(7) })
                }
            })
        },LinearLayout.LayoutParams(-1,dp(42)))
    }

    private fun segmentedSetting(
        title: String,
        choices: List<Pair<Int,String>>,
        selected: Int,
        onSelected: (Int) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(7), 0, dp(7))
        addView(textView(title, 14.5f, false), LinearLayout.LayoutParams(-1, dp(30)))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            choices.forEachIndexed { index, (value,label) ->
                val active=value==selected
                addView(TextView(this@MainActivity).apply {
                    text=label
                    textSize=13f
                    gravity=Gravity.CENTER
                    setTextColor(if(active) colors.accent else colors.secondaryText)
                    background=roundedBackground(if(active) colors.actionBackground else colors.fieldBackground,12f,true)
                    setOnClickListener { if(!active) onSelected(value) }
                },LinearLayout.LayoutParams(0,dp(42),1f).apply { if(index>0)marginStart=dp(7) })
            }
        })
    }

    private fun sliderSetting(
        title: String,
        minValue: Int,
        maxValue: Int,
        currentValue: Int,
        suffix: String,
        onChanged: (Int) -> Unit,
    ): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(textView(title, 14.5f, false), LinearLayout.LayoutParams(0, dp(30), 1f))
        val valueLabel = textView(getString(R.string.slider_value, currentValue, suffix), 12.5f, false, colors.secondaryText)
        header.addView(valueLabel)
        root.addView(header)
        root.addView(
            SeekBar(this).apply {
                max = maxValue - minValue
                progress = currentValue.coerceIn(minValue, maxValue) - minValue
                progressTintList = ColorStateList.valueOf(colors.accent)
                thumbTintList = ColorStateList.valueOf(colors.accent)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val value = minValue + progress
                        valueLabel.text = getString(R.string.slider_value, value, suffix)
                        if (fromUser) onChanged(value)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        notifyOverlayAppearanceChanged()
                    }
                })
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)),
        )
        return root
    }

    private fun historyCategorySelector(): View {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val categories = (listOf(ALL_CATEGORY) + categoryStore.categories()).distinct()
        if (historyCategory !in categories) historyCategory = ALL_CATEGORY
        categories.forEachIndexed { index, category ->
            val selected = category == historyCategory
            row.addView(
                TextView(this).apply {
                    text = category
                    textSize = 12.5f
                    gravity = Gravity.CENTER
                    setPadding(dp(14), 0, dp(14), 0)
                    setTextColor(if (selected) colors.accentTextOnFill else colors.secondaryText)
                    background = roundedBackground(if (selected) colors.accent else colors.fieldBackground, 12f, !selected)
                    setOnClickListener {
                        historyCategory = category
                        render()
                    }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply {
                    if (index > 0) marginStart = dp(7)
                },
            )
        }
        scroll.addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)))
        return scroll
    }

    private fun showCategoryPicker(entry: ClipEntry) {
        val categories = categoryStore.categories().distinct()
        val selected = categories.indexOf(entry.category).coerceAtLeast(0)
        FloatDialog.choices(this,"选择分类",categories,selected) { position ->
            clipStore.updateCategory(entry.id,categories[position])
            render()
        }
    }

    private fun chooseBackupDirectory() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_BACKUP_TREE)
    }

    private fun securePinField(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        isSingleLine = true
        gravity = Gravity.CENTER
        textSize = 18f
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(InputFilter.LengthFilter(6))
        setTextColor(colors.primaryText)
        setHintTextColor(colors.secondaryText)
        background = roundedBackground(colors.fieldBackground, 13f)
        setPadding(dp(13), 0, dp(13), 0)
    }

    private fun showSetPinDialog() {
        val first = securePinField("输入 6 位数字 PIN")
        val second = securePinField("再次输入 PIN")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(first, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            addView(second, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(10) })
            addView(textView("PIN 不会明文写入磁盘。忘记 PIN 后，便携备份无法恢复。", 12f, false, colors.secondaryText).apply { setPadding(0, dp(10), 0, 0) })
        }
        FloatDialog.show(
            this,
            "设置加密 PIN",
            custom=box,
            negative="取消",
            positive="保存",
        ) { handle ->
            val a = first.text?.toString().orEmpty()
            val b = second.text?.toString().orEmpty()
            if (!a.matches(Regex("\\d{6}")) || a != b) {
                Toast.makeText(this, "请输入两次相同的 6 位数字 PIN", Toast.LENGTH_SHORT).show()
                false
            } else {
                val wasWriteArmed = vaultSettings.backupWriteArmed()
                vaultSettings.savePin(a)
                val tree = vaultSettings.backupTreeUri()
                if (tree != null) {
                    Thread {
                        when {
                            wasWriteArmed -> vaultBackupManager.backup(tree, a, clipStore.exportJson())
                            !vaultBackupManager.hasBackup(tree) -> vaultBackupManager.initializeBackup(tree, a, clipStore.exportJson())
                        }
                    }.start()
                }
                render()
                true
            }
        }
    }

    private fun showRestorePinDialog() {
        val tree = vaultSettings.backupTreeUri()
        if (tree == null) {
            Toast.makeText(this, "请先选择保存 FloatClip.vault 的目录", Toast.LENGTH_LONG).show()
            return
        }
        val pin = securePinField("输入备份的 6 位 PIN")
        FloatDialog.show(
            this,
            "恢复加密备份",
            "恢复会合并为当前本地 vault 的新状态。",
            custom=pin,
            negative="取消",
            positive="恢复",
        ) { handle ->
            val value = pin.text?.toString().orEmpty()
            if (!value.matches(Regex("\\d{6}"))) {
                Toast.makeText(this, "请输入 6 位数字 PIN", Toast.LENGTH_SHORT).show()
                false
            } else {
                handle.primary?.isEnabled=false
                Thread {
                    val raw = vaultBackupManager.restore(tree, value)
                    runOnUiThread {
                        val ok = raw != null && clipStore.importJson(raw)
                        if (ok) {
                            vaultSettings.savePin(value)
                            vaultSettings.setBackupWriteArmed(true)
                            Toast.makeText(this, "剪贴板备份已恢复，自动加密备份已重新启用", Toast.LENGTH_SHORT).show()
                            handle.dialog.dismiss()
                            render()
                        } else {
                            handle.primary?.isEnabled=true
                            Toast.makeText(this, "恢复失败：PIN 不正确或备份已损坏", Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
                false
            }
        }
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, ClipboardOverlayService.NOTIFICATION_CHANNEL_ID)
        }
        startActivity(intent)
    }

    private fun matchesSearch(entry: ClipEntry): Boolean =
        searchQuery.isBlank() || entry.text.contains(searchQuery, ignoreCase = true) ||
            entry.category.contains(searchQuery, ignoreCase = true)

    private fun addHistoryCard(entry: ClipEntry) {
        val item=ClipboardCard(this,entry,colors.dark,colors.primaryText,colors.cardBackground)
        val wrapper = EntryGestureRow(this)
        wrapper.contentDescription=entry.text+"，来源："+entry.sourceDeviceName+(if(entry.pinned) "，已置顶" else "")+(if(entry.locked) "，已保护" else "")
        wrapper.bind(
            item,
            EntrySwipeActions(this, entry.pinned, colors.dark,
                onPin = { clipStore.togglePinned(entry.id); refreshHistory(); notifyOverlayAppearanceChanged() },
                onDelete = { deleteEntry(entry) },
            ),
            dp(148),
            EntryLockAction(this,entry.locked,colors.dark,overlayPreferences.accentTheme()) {
                clipStore.toggleLocked(entry.id); refreshHistory(); notifyOverlayAppearanceChanged()
            },
            dp(78),
        )
        wrapper.onSingleTap = {
            runCatching {
                if(entry.kind=="image")ClipboardImages.copy(this,clipStore,entry)
                else ClipboardWrites.copyByUser(this,getSystemService(ClipboardManager::class.java),entry.text)
            }.onSuccess { Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(this,it.message ?: "复制未完成",Toast.LENGTH_LONG).show() }
        }
        wrapper.onLongPress = {
            FloatDialog.actions(
                this,
                entry.sourceDeviceName,
                listOf(if(entry.kind=="image") "查看图片" else "查看全文", "更改分类", if(entry.locked) "取消保护" else "保护", if (entry.pinned) "取消置顶" else "置顶", "删除"),
            ) { which ->
                when (which) {
                    0 -> if(entry.kind=="image") runCatching { ClipboardImages.preview(this,clipStore,entry) }.onFailure {
                        Toast.makeText(this,it.message ?: "图片暂不可用",Toast.LENGTH_LONG).show()
                    } else FloatDialog.show(
                        this,
                        entry.sourceDeviceName,
                        entry.text,
                        negative="关闭",
                        positive="复制",
                    ) {
                        ClipboardWrites.copyByUser(this,getSystemService(ClipboardManager::class.java),entry.text)
                        true
                    }
                    1 -> showCategoryPicker(entry)
                    2 -> { clipStore.toggleLocked(entry.id); refreshHistory(); notifyOverlayAppearanceChanged() }
                    3 -> { clipStore.togglePinned(entry.id); refreshHistory(); notifyOverlayAppearanceChanged() }
                    else -> deleteEntry(entry)
                }
            }
        }
        historyList?.addView(wrapper, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    private fun deleteEntry(entry: ClipEntry) {
        clipStore.delete(entry.id);undoEntries=listOf(entry);refreshHistory();showUndo();notifyHistoryChanged()
    }
    private fun clearHistory() {
        runCatching { clipStore.clearAll() }.onSuccess { removed ->
            undoEntries=emptyList();clipStore.retainUndoImages(undoOwner,emptyList());refreshHistory();showUndo()
            if (removed.isEmpty()) {
                Toast.makeText(this,"没有可清除的普通记录；固定和置顶记录已保留",Toast.LENGTH_SHORT).show()
            } else {
                notifyHistoryChanged()
                Toast.makeText(this,"已清除 ${removed.size} 条普通记录，固定和置顶记录已保留",Toast.LENGTH_SHORT).show()
            }
        }.onFailure { Toast.makeText(this,"清除未完成，请保留本机数据并重试",Toast.LENGTH_LONG).show() }
    }
    private fun showUndo() {
        clipStore.retainUndoImages(undoOwner,undoEntries)
        val row=undoBar ?: return
        row.removeAllViews();row.visibility=if(undoEntries.isEmpty()) View.GONE else View.VISIBLE
        row.addView(textView(if(undoEntries.size==1) "记录已删除" else "已清除 ${undoEntries.size} 条记录",14f,false,colors.secondaryText),LinearLayout.LayoutParams(0,dp(48),1f))
        row.addView(compactTextAction("撤销") {
            runCatching { clipStore.restoreAll(undoEntries) }.onSuccess { remaining ->
                undoEntries=remaining;refreshHistory();showUndo();notifyHistoryChanged()
                if(remaining.isNotEmpty()) Toast.makeText(this,"空间不足，尚有 ${remaining.size} 条待恢复；新记录和受保护记录已保留",Toast.LENGTH_LONG).show()
            }
                .onFailure { Toast.makeText(this,it.message ?: "暂时无法恢复",Toast.LENGTH_LONG).show() }
        }.apply { id=R.id.clipboard_undo })
    }
    private fun notifyHistoryChanged() {
        sendBroadcast(Intent(ClipboardSyncService.HISTORY_CHANGED).setPackage(packageName),"$packageName.permission.INTERNAL")
    }
    private fun checkClientUpdate() {
        Toast.makeText(this,"正在检查更新…",Toast.LENGTH_SHORT).show()
        val selectedChannel=UpdatePreferences(this).channel()
        val wireChannel=when(selectedChannel) {
            UpdateChannel.PREVIEW -> com.floatclip.probe.ShareUpdates.Channel.PREVIEW
            UpdateChannel.DEV -> com.floatclip.probe.ShareUpdates.Channel.DEV
        }
        Thread {
            val result=runCatching { com.floatclip.probe.ShareUpdates.check(wireChannel) }
            runOnUiThread {
                if(isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { release ->
                    val build=packageManager.getPackageInfo(packageName,0).longVersionCode
                    val versionOrder=com.floatclip.probe.ShareUpdates.compareVersion(release.version,versionName())
                    val newer=release.build>build && versionOrder>=0
                    FloatDialog.show(
                        this,
                        if(newer) "发现 ${release.version}" else "当前版本 ${versionName()}",
                        if(newer) release.notes else if(versionOrder<0 || release.build<build) "当前为试用版本；"+selectedChannel.label+"通道已发布版本为 "+release.version else "已是"+selectedChannel.label+"通道最新版本（build $build）",
                        negative="关闭",
                        positive=if(newer) "下载" else null,
                        onPositive=if(newer) { { _ ->
                            startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(release.url)))
                            true
                        } } else null,
                    )
                }.onFailure {
                    FloatDialog.show(this,"检查更新失败",it.message ?: "更新服务暂不可用",negative=null,positive="关闭")
                }
            }
        }.start()
    }

    private fun installCategoryDrag(
        handle: View, row: EntryGestureRow, list: LinearLayout,
        items: MutableList<String>, category: String,
    ) {
        var downY = 0f
        var from = 0
        var target = 0
        var dragging = false
        var completing = false
        var scrollStart = 0
        var scroll: ScrollView? = null
        var latestRawY = 0f
        val stride = dp(54).toFloat()
        fun updatePosition() {
            val dy = latestRawY - downY + ((scroll?.scrollY ?: 0) - scrollStart)
            row.translationY = dy
            val nextTarget = (from + kotlin.math.round(dy / stride).toInt()).coerceIn(0, items.lastIndex)
            if (nextTarget != target) {
                target = nextTarget
                handle.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            for (i in 0 until list.childCount) {
                val child = list.getChildAt(i)
                if (child === row) continue
                val offset = when {
                    target > from && i in (from + 1)..target -> -stride
                    target < from && i in target until from -> stride
                    else -> 0f
                }
                if (child.tag != offset) {
                    child.tag = offset
                    child.animate().translationY(offset).setDuration(110L).start()
                }
            }
        }
        val autoScroll = object : Runnable {
            override fun run() {
                if (!dragging || !handle.isAttachedToWindow) return
                scroll?.let {
                    val pos = IntArray(2)
                    it.getLocationOnScreen(pos)
                    val delta = when {
                        latestRawY < pos[1] + dp(64) -> -dp(7)
                        latestRawY > pos[1] + it.height - dp(64) -> dp(7)
                        else -> 0
                    }
                    if (delta != 0) { it.scrollBy(0, delta); updatePosition() }
                }
                handle.postOnAnimation(this)
            }
        }
        handle.setOnClickListener {
            Toast.makeText(this, "上下拖动手柄调整顺序", Toast.LENGTH_SHORT).show()
        }
        handle.setOnTouchListener { _, event ->
            if (completing) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    row.cancelPendingGestures()
                    downY = event.rawY
                    latestRawY = downY
                    from = items.indexOf(category)
                    target = from
                    dragging = false
                    var ancestor = list.parent
                    while (ancestor != null && ancestor !is ScrollView) ancestor = ancestor.parent
                    scroll = ancestor as? ScrollView
                    scrollStart = scroll?.scrollY ?: 0
                    list.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    latestRawY = event.rawY
                    if (!dragging && kotlin.math.abs(latestRawY - downY) > ViewConfiguration.get(this).scaledTouchSlop) {
                        dragging = true
                        row.elevation = dp(12).toFloat()
                        row.scaleX = 1.025f
                        row.scaleY = 1.025f
                        handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        handle.postOnAnimation(autoScroll)
                    }
                    if (dragging) updatePosition()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    completing = true
                    handle.removeCallbacks(autoScroll)
                    val commit = dragging && event.actionMasked == MotionEvent.ACTION_UP
                    val views = (0 until list.childCount).map { list.getChildAt(it) }.toMutableList()
                    if (commit && target != from) {
                        views.add(target, views.removeAt(from))
                        items.add(target, items.removeAt(from))
                        categoryStore.replaceOrder(items)
                        notifyOverlayAppearanceChanged()
                        handle.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    if (commit && target != from) {
                        val oldY = views.associateWith { view ->
                            IntArray(2).also { view.getLocationOnScreen(it) }[1]
                        }
                        views.forEach { it.animate().cancel(); it.translationY = 0f; it.tag = null }
                        list.removeAllViews()
                        views.forEach { list.addView(it) }
                        list.post {
                            views.forEach { view ->
                                if (!view.isAttachedToWindow) return@forEach
                                val newY = IntArray(2).also { view.getLocationOnScreen(it) }[1]
                                view.translationY = (oldY.getValue(view) - newY).toFloat()
                                view.animate().translationY(0f).setDuration(180L).start()
                            }
                        }
                    } else {
                        views.forEach {
                            it.tag = null
                            it.animate().translationY(0f).setDuration(150L).start()
                        }
                    }
                    row.elevation = 0f
                    row.animate().scaleX(1f).scaleY(1f).setDuration(160L).start()
                    if (!dragging && event.actionMasked == MotionEvent.ACTION_UP) handle.performClick()
                    dragging = false
                    list.parent?.requestDisallowInterceptTouchEvent(false)
                    completing = false
                }
            }
            true
        }
    }

    private fun showCompatibilityDetails(bridgeReady: Boolean, lockState: String?) {
        val text = if (bridgeReady) {
            "当前 vivo ROM 与 FloatingBall 锁定信息匹配。FloatClip 只读取允许的语义主题资源，不调用 OEM 私有 AIDL。"
        } else {
            "当前以独立悬浮层运行。ROM Lock 状态：${lockState ?: "UNINITIALIZED"}。"
        }
        FloatDialog.show(this,"OriginOS 兼容",text,negative=null,positive="知道了")
    }

    private fun statusRow(label: String, value: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(textView(label, 14.5f, false), LinearLayout.LayoutParams(0, dp(38), 1f))
        row.addView(textView(value, 13f, false, if (value.contains("已") || value.contains("匹配")) colors.accent else colors.secondaryText))
        return row
    }

    private fun settingRow(
        title: String,
        subtitle: String,
        actionLabel: String,
        action: () -> Unit,
    ): View = settingRow(title, subtitle, actionLabel, null, action)

    private fun settingRow(
        title: String,
        subtitle: String,
        actionLabel: String,
        iconRes: Int?,
        action: () -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            minimumHeight = dp(56)
            background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(colors.actionBackground), null, null)
            setOnClickListener { action() }
        }
        if(iconRes!=null) row.addView(ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList=ColorStateList.valueOf(colors.secondaryText)
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },LinearLayout.LayoutParams(dp(22),dp(22)).apply { marginEnd=dp(12) })
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(textView(title, 15f, true))
            if (subtitle.isNotBlank()) addView(textView(subtitle, 12f, false, colors.secondaryText).apply {
                maxLines = 2
                setPadding(0, dp(4), dp(8), 0)
            })
        }
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        if (actionLabel == "已开启" || actionLabel == "已关闭") {
            row.addView(FluentSwitch(this).apply {
                contentDescription=title
                bind(
                    actionLabel=="已开启",
                    colors.accent,
                    colors.dividerColor,
                    colors.secondaryText,
                ) { action() }
            }, LinearLayout.LayoutParams(dp(44), dp(28)).apply { marginStart=dp(12) })
        } else {
            if (actionLabel != "›" && actionLabel != "设置" && actionLabel != "打开") {
                row.addView(textView(actionLabel, 12f, false, colors.secondaryText).apply {
                    setPadding(dp(8), 0, dp(8), 0)
                })
            }
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_chevron_right)
                imageTintList = ColorStateList.valueOf(colors.secondaryText)
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart=dp(8) })
        }
        return row
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = cardBackground()
        elevation = dp(1).toFloat()
    }

    private fun addCard(view: View, topMarginDp: Int = 0) {
        content.addView(
            view,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(topMarginDp)
            },
        )
    }

    private fun sectionTitle(text: String, compactTop: Boolean = false) {
        spacer(if (compactTop) 4 else 22)
        content.addView(textView(text, 16f, true))
        spacer(9)
    }

    private fun actionButton(label: String, primary: Boolean, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(12), 0, dp(12), 0)
        setTextColor(if (primary) colors.accentTextOnFill else colors.primaryText)
        background = roundedBackground(if (primary) colors.accent else colors.cardBackground, 12f, !primary)
        setOnClickListener { action() }
    }

    private fun compactTextAction(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12.5f
        gravity = Gravity.CENTER
        setTextColor(colors.accent)
        minimumHeight = dp(40)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        background = roundedBackground(colors.actionBackground, 12f)
        setOnClickListener { action() }
    }

    private fun textView(textValue: String, sizeSp: Float, bold: Boolean, color: Int = colors.primaryText): TextView = TextView(this).apply {
        text = textValue
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(colors.dividerColor)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun cardBackground(): GradientDrawable = roundedBackground(colors.cardBackground, 16f, true)

    private fun roundedBackground(color: Int, radiusDp: Float, border: Boolean = false): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (border && overlayPreferences.borderEnabled()) {
            val alpha=(255f*overlayPreferences.borderAlphaPercent()/100f).toInt().coerceIn(0,255)
            setStroke(dp(1), Color.argb(alpha,Color.red(this@MainActivity.colors.borderColor),Color.green(this@MainActivity.colors.borderColor),Color.blue(this@MainActivity.colors.borderColor)))
        }
    }

    private fun spacer(heightDp: Int) {
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(heightDp)))
    }

    private fun spacerInside(parent: LinearLayout, heightDp: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(heightDp)))
    }

    private fun openOverlayPermission() {
        FloatDialog.show(
            this,
            "允许显示悬浮窗",
            "请在接下来的系统页面找到 FloatClip，开启“显示在其他应用上层”。返回后会重新检查授权状态。",
            negative="暂不",
            positive="去授权",
        ) {
            openSystemSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            true
        }
    }

    private fun openAccessibilitySettings() {
        openSystemSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlayPermission()
            return
        }
        overlayPreferences.saveOverlayEnabled(true)
        startOverlayRuntime()
    }

    private fun startOverlayRuntime() {
        if (PasteAccessibilityService.ensureOverlayHosted()) return
        startForegroundService(overlayServiceIntent)
    }

    private fun stopOverlay() {
        overlayPreferences.saveOverlayEnabled(false)
        PasteAccessibilityService.releaseOverlayHosted()
        stopService(overlayServiceIntent)
    }

    private fun openAppDetailsSettings() {
        openSystemSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun notifyOverlayAppearanceChanged() {
        sendBroadcast(Intent(ClipboardOverlayService.ACTION_REFRESH_APPEARANCE).setPackage(packageName))
    }

    private fun resolveAppColors(): AppColors {
        val systemDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val dark = when (overlayPreferences.themeMode()) {
            OverlayThemeMode.SYSTEM -> systemDark
            OverlayThemeMode.LIGHT -> false
            OverlayThemeMode.DARK -> true
        }
        // Keep the app shell on the same token family as DeviceActivity / ShareDesign.
        return if (dark) {
            AppColors(
                pageBackground = Color.rgb(26, 27, 38),       // Tokyo Night #1A1B26
                cardBackground = Color.rgb(36, 40, 59),       // Tokyo Night #24283B
                primaryText = Color.rgb(192, 202, 245),     // Tokyo Night #C0CAF5
                secondaryText = Color.rgb(154, 165, 206),     // Tokyo Night #9AA5CE
                dividerColor = Color.rgb(65, 72, 104),       // Tokyo Night #414868
                accent = FloatClipAccent.color(overlayPreferences.accentTheme(), true),
                accentTextOnFill = Color.rgb(26, 27, 38),
                actionBackground = Color.rgb(41, 46, 66),       // Tokyo Night #292E42
                fieldBackground = Color.rgb(31, 35, 53),
                borderColor = Color.rgb(65, 72, 104),
                navigationBackground = Color.rgb(26, 27, 38),
                selectedNavigationBackground = Color.rgb(36, 59, 90),
                dark = true,
            )
        } else {
            AppColors(
                pageBackground = Color.rgb(244, 247, 252),    // #F4F7FC
                cardBackground = Color.WHITE,
                primaryText = Color.rgb(25, 40, 63),          // #19283F
                secondaryText = Color.rgb(89, 108, 133),      // #596C85
                dividerColor = Color.rgb(223, 231, 242),      // #DFE7F2
                accent = FloatClipAccent.color(overlayPreferences.accentTheme(), false),
                accentTextOnFill = Color.WHITE,
                actionBackground = Color.rgb(235, 242, 255),  // #EBF2FF
                fieldBackground = Color.rgb(240, 243, 248),
                borderColor = Color.rgb(223, 231, 242),
                navigationBackground = Color.rgb(244, 247, 252),
                selectedNavigationBackground = Color.rgb(235, 242, 255),
                dark = false,
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun applySystemBars() {
        window.statusBarColor = colors.pageBackground
        window.navigationBarColor = colors.navigationBackground
        @Suppress("DEPRECATION")
        run {
            window.decorView.systemUiVisibility = if (colors.dark) {
                0
            } else {
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0.5.1"
    }.getOrDefault("0.5.1")

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private data class AppColors(
        val pageBackground: Int,
        val cardBackground: Int,
        val primaryText: Int,
        val secondaryText: Int,
        val dividerColor: Int,
        val accent: Int,
        val accentTextOnFill: Int,
        val actionBackground: Int,
        val fieldBackground: Int,
        val borderColor: Int,
        val navigationBackground: Int,
        val selectedNavigationBackground: Int,
        val dark: Boolean,
    )

    companion object {
        private const val PAGE_CLIPBOARD = 1
        private const val PAGE_SETTINGS = 2
        private const val STATE_PAGE = "current_page"
        private const val ALL_CATEGORY = "全部"
        private const val REQUEST_BACKUP_TREE = 6201
    }
}
