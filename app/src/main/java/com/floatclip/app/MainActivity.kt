package com.floatclip.app

import android.app.Activity
import android.content.ClipData
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
import com.floatclip.app.overlay.ui.SwipeRevealRow
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
import com.floatclip.app.integration.IntegrationRegistry
import com.floatclip.app.model.ClipEntry
import com.floatclip.app.overlay.ClipboardOverlayService
import com.floatclip.app.prefs.CategoryStore
import com.floatclip.app.prefs.OverlayPreferences
import com.floatclip.app.prefs.OverlayThemeMode
import com.floatclip.app.security.VaultSettings

class MainActivity : Activity() {
    private lateinit var categoryStore: CategoryStore
    private lateinit var clipStore: ClipStore
    private lateinit var overlayPreferences: OverlayPreferences
    private lateinit var vaultSettings: VaultSettings
    private lateinit var vaultBackupManager: VaultBackupManager
    private lateinit var content: LinearLayout
    private lateinit var colors: AppColors
    private var searchQuery: String = ""
    private var currentPage: Int = PAGE_STATUS
    private var historyCategory: String = ALL_CATEGORY

    private val overlayServiceIntent by lazy(LazyThreadSafetyMode.NONE) {
        Intent(this, ClipboardOverlayService::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        IntegrationRegistry.initialize(this)
        categoryStore = CategoryStore(this)
        clipStore = ClipStore(this)
        overlayPreferences = OverlayPreferences(this)
        vaultSettings = VaultSettings(this)
        vaultBackupManager = VaultBackupManager(this)
        currentPage = savedInstanceState?.getInt(STATE_PAGE, PAGE_STATUS) ?: PAGE_STATUS
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_PAGE, currentPage)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) render()
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
            setPadding(dp(18), dp(20), dp(18), dp(28))
        }
        scroll.addView(
            content,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottomNavigation(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)))
        setContentView(root)

        renderHeader()
        when (currentPage) {
            PAGE_CLIPBOARD -> renderClipboardPage()
            PAGE_SETTINGS -> renderSettingsPage()
            else -> renderStatusPage()
        }
    }

    private fun renderHeader() {
        content.addView(textView("FloatClip", 29f, true))
        val pageName = when (currentPage) {
            PAGE_CLIPBOARD -> "剪贴板"
            PAGE_SETTINGS -> "设置"
            else -> "运行状态"
        }
        content.addView(textView("${versionName()} · OriginOS 悬浮剪贴板 · $pageName", 13f, false, colors.secondaryText))
        spacer(18)
    }

    private fun renderStatusPage() {
        renderPermissionGuide()
        val romLock = IntegrationRegistry.romLockResult()
        val bridgeReady = IntegrationRegistry.originOsSystemBridge.isAvailable()

        addCard(
            card().apply {
                addView(textView("运行状态", 16f, true))
                spacerInside(this, 8)
                addView(statusRow("悬浮窗", if (Settings.canDrawOverlays(this@MainActivity)) "已授权" else "未授权"))
                addView(divider())
                addView(statusRow("一键粘贴", if (PasteAccessibilityService.isConnected()) "已连接" else "未启用"))
                addView(divider())
                addView(statusRow("OriginOS", if (bridgeReady) "ROM 已匹配" else "独立模式"))
            },
        )

        spacer(12)
        val runRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        runRow.addView(actionButton("启动悬浮剪贴板", true, ::startOverlay), LinearLayout.LayoutParams(0, dp(48), 1f))
        runRow.addView(
            actionButton("停止", false, ::stopOverlay),
            LinearLayout.LayoutParams(dp(86), dp(48)).apply { marginStart = dp(10) },
        )
        content.addView(runRow)

        sectionTitle("工作方式")
        addCard(
            card().apply {
                addView(infoRow("普通模式", "点击面板外空白区域收回，不把这次触摸穿透到底层应用。"))
                addView(divider())
                addView(infoRow("固定模式", "面板保持展开，可继续操作底层应用并连续粘贴多个条目。"))
                addView(divider())
                addView(infoRow("贴边待机", "悬浮球吸附到最近屏幕边缘后会缓慢半隐藏，触摸后立即恢复交互。"))
            },
        )

        sectionTitle("兼容状态")
        addCard(
            card().apply {
                addView(
                    settingRow(
                        "OriginOS 兼容",
                        if (bridgeReady) "FloatingBall ROM Lock 匹配，语义主题桥可用" else "${romLock?.state?.name ?: "UNINITIALIZED"} · 使用独立悬浮层",
                        "详情",
                    ) { showCompatibilityDetails(bridgeReady, romLock?.state?.name) },
                )
            },
        )
    }

    private fun renderClipboardPage() {
        sectionTitle("分类", compactTop = true)
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
            if (categoryStore.add(categoryInput.text?.toString().orEmpty())) render()
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
            categoryCard.addView(textView("拖动右侧手柄排序 · 左滑删除分类", 11.5f, false, colors.secondaryText))
            spacerInside(categoryCard, 7)

            val dragItems = categories.toMutableList()
            val dragList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            categories.forEach { category ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), 0, dp(7), 0)
                    background = roundedBackground(colors.fieldBackground, 13f, true)
                    addView(textView(category, 15f, false), LinearLayout.LayoutParams(0, dp(48), 1f))
                }
                val handle = ImageView(this).apply {
                    setImageResource(R.drawable.ic_drag)
                    imageTintList = ColorStateList.valueOf(colors.secondaryText)
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    contentDescription = "拖动 $category 排序"
                }
                row.addView(handle, LinearLayout.LayoutParams(dp(48), dp(48)))
                val wrapper = SwipeRevealRow(this)
                wrapper.bind(row, swipeDelete {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("删除分类“$category”？")
                        .setMessage("词条会保留并移到未分类。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除") { _, _ ->
                            clipStore.moveCategoryToDefault(category)
                            categoryStore.delete(category)
                            notifyOverlayAppearanceChanged()
                            render()
                        }.show()
                }, dp(72))
                wrapper.touchPassthrough = handle
                dragList.addView(wrapper, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(6) })
                installCategoryDrag(handle, wrapper, dragList, dragItems, category)
            }
            categoryCard.addView(dragList)
        }
        addCard(categoryCard)

        sectionTitle("历史")
        content.addView(textView("单击复制 · 双击置顶 · 长按更多 · 左滑删除", 11.5f, false, colors.secondaryText))
        content.addView(historyCategorySelector())
        spacer(9)
        val searchCard = card()
        val search = EditText(this).apply {
            hint = "搜索内容或分类"
            isSingleLine = true
            textSize = 15f
            setText(searchQuery)
            setSelection(text.length)
            setTextColor(colors.primaryText)
            setHintTextColor(colors.secondaryText)
            background = roundedBackground(colors.fieldBackground, 13f)
            setPadding(dp(13), 0, dp(13), 0)
        }
        searchCard.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(search, LinearLayout.LayoutParams(0, dp(44), 1f))
                addView(
                    actionButton("搜索", false) {
                        searchQuery = search.text?.toString().orEmpty().trim()
                        render()
                    },
                    LinearLayout.LayoutParams(dp(74), dp(44)).apply { marginStart = dp(8) },
                )
            },
        )
        addCard(searchCard)

        val visibleHistory = clipStore.entries()
            .filter { historyCategory == ALL_CATEGORY || it.category == historyCategory }
            .filter(::matchesSearch)
            .take(80)
        if (visibleHistory.isEmpty()) {
            addCard(
                card().apply {
                    gravity = Gravity.CENTER
                    addView(textView("暂无匹配记录", 14f, false, colors.secondaryText))
                },
                topMarginDp = 10,
            )
        } else {
            visibleHistory.forEach { addHistoryCard(it) }
        }
    }

    private fun renderPermissionGuide() {
        val overlay = Settings.canDrawOverlays(this)
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        addCard(card().apply {
            addView(textView("授权与后台运行", 16f, true))
            spacerInside(this, 6)
            addView(textView("系统“未请求权限”不包含以下特殊访问权限。按需要开启即可。", 12f, false, colors.secondaryText))
            addView(settingRow("悬浮窗", "显示悬浮球和剪贴板面板", if (overlay) "已授权" else "去授权", ::openOverlayPermission))
            addView(divider())
            addView(settingRow("无障碍（可选）", "提供更多菜单中的直接粘贴；未开启也能复制", if (PasteAccessibilityService.isConnected()) "已连接" else "去设置", ::openAccessibilitySettings))
            addView(divider())
            addView(settingRow("电池优化", "在系统列表中找到 FloatClip，按需选择不优化", if (battery) "已放宽" else "去设置") {
                openSystemSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            })
            addView(divider())
            addView(settingRow("OriginOS 后台与自启动", "到电量/后台耗电管理允许后台运行；自启动需在系统设置确认", "去设置", ::openAppDetailsSettings))
            addView(textView("后台与自启动状态需手动确认。无障碍不等于后台剪贴板读取权限；强行停止后需重新打开应用。", 11.5f, false, colors.secondaryText))
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
        renderPermissionGuide()
        sectionTitle("启动与恢复", compactTop = true)
        addCard(
            card().apply {
                val keepAlive = overlayPreferences.keepAliveEnabled()
                addView(
                    settingRow(
                        "后台常驻",
                        "任务清理后尝试恢复，并在重启/应用更新后恢复已启用的悬浮球",
                        if (keepAlive) "已开启" else "已关闭",
                    ) {
                        overlayPreferences.saveKeepAliveEnabled(!keepAlive)
                        if (overlayPreferences.overlayEnabled()) {
                            startOverlayRuntime()
                        }
                        render()
                    },
                )
            },
        )

        sectionTitle("主题")
        addCard(
            card().apply {
                addView(textView("界面与悬浮剪贴板可跟随系统浅色 / 深色模式，也可以手动锁定。", 12f, false, colors.secondaryText))
                spacerInside(this, 12)
                addView(themeSelector())
            },
        )

        sectionTitle("悬浮球")
        addCard(
            card().apply {
                addView(textView("拖动松手后采用缓动吸附；停靠片刻后按设定比例半隐藏。", 12f, false, colors.secondaryText))
                spacerInside(this, 10)
                addView(
                    sliderSetting(
                        "悬浮球大小",
                        42,
                        64,
                        overlayPreferences.bubbleSizeDp(),
                        "dp",
                    ) { overlayPreferences.saveBubbleSizeDp(it) },
                )
                addView(divider())
                addView(
                    sliderSetting(
                        "悬浮球透明度",
                        35,
                        100,
                        overlayPreferences.bubbleAlphaPercent(),
                        "%",
                    ) { overlayPreferences.saveBubbleAlphaPercent(it) },
                )
                addView(divider())
                addView(
                    sliderSetting(
                        "滑动灵敏度",
                        30,
                        120,
                        overlayPreferences.bubbleMotionSensitivityPercent(),
                        "%",
                    ) { overlayPreferences.saveBubbleMotionSensitivityPercent(it) },
                )
                addView(divider())
                addView(
                    sliderSetting(
                        "贴边半隐藏",
                        0,
                        55,
                        overlayPreferences.edgeHidePercent(),
                        "%",
                    ) { overlayPreferences.saveEdgeHidePercent(it) },
                )
            },
        )

        sectionTitle("展开面板")
        addCard(
            card().apply {
                addView(textView("面板支持自由拖动并记忆位置；外观调整在松开滑块后同步到正在运行的悬浮层。", 12f, false, colors.secondaryText))
                spacerInside(this, 10)
                addView(
                    sliderSetting(
                        "面板透明度",
                        45,
                        100,
                        overlayPreferences.panelAlphaPercent(),
                        "%",
                    ) { overlayPreferences.savePanelAlphaPercent(it) },
                )
                addView(divider())
                addView(
                    sliderSetting(
                        "面板宽度",
                        280,
                        420,
                        overlayPreferences.panelWidthDp(),
                        "dp",
                    ) { overlayPreferences.savePanelWidthDp(it) },
                )
                addView(divider())
                addView(
                    sliderSetting(
                        "面板高度",
                        300,
                        620,
                        overlayPreferences.panelHeightDp(),
                        "dp",
                    ) { overlayPreferences.savePanelHeightDp(it) },
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

        sectionTitle("数据安全与备份")
        addCard(
            card().apply {
                addView(infoRow("本地加密", "剪贴板主数据使用 Android Keystore + AES-GCM 加密保存；升级应用不会丢失。"))
                addView(divider())
                addView(settingRow(
                    "6 位数字 PIN",
                    if (vaultSettings.hasPin()) "已设置；用于便携备份和未来端到端同步" else "用于可迁移的加密备份；不会明文保存",
                    if (vaultSettings.hasPin()) "修改" else "设置",
                    ::showSetPinDialog,
                ))
                addView(divider())
                val backupTreeSelected = vaultSettings.backupTreeUri() != null
                val backupDetail = when {
                    !backupTreeSelected -> "选择共享目录后，卸载应用也不会删除备份文件"
                    vaultSettings.backupWriteArmed() -> "已选择；FloatClip.vault 会在本地数据变化后自动加密更新"
                    vaultSettings.hasPin() -> "已选择；当前为写保护，请先恢复已有备份或完成安全初始化"
                    else -> "已选择；设置 6 位 PIN 后再初始化加密备份"
                }
                addView(settingRow(
                    "持久化备份目录",
                    backupDetail,
                    if (backupTreeSelected) "更改" else "选择",
                    ::chooseBackupDirectory,
                ))
                addView(divider())
                addView(settingRow(
                    "从备份恢复",
                    "重装后重新选择原目录并输入 PIN 即可恢复",
                    "恢复",
                    ::showRestorePinDialog,
                ))
            },
        )

        sectionTitle("端到端同步（预留）")
        addCard(syncEndpointCard())

        sectionTitle("后台运行")
        addCard(
            card().apply {
                if (PasteAccessibilityService.isConnected()) {
                    addView(infoRow("运行方式", "无障碍托管模式：一键粘贴服务由系统绑定，并同时托管悬浮层；FloatClip 自己不需要前台服务常驻通知。"))
                    spacerInside(this, 8)
                    addView(
                        compactTextAction("打开无障碍设置") { openAccessibilitySettings() },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)),
                    )
                } else {
                    addView(infoRow("运行方式", "标准前台服务模式：Android 要求显示常驻通知。启用一键粘贴后可切换到无障碍托管模式，并移除 FloatClip 的前台服务通知。"))
                    spacerInside(this, 8)
                    addView(
                        LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            addView(
                                compactTextAction("启用无障碍托管") { openAccessibilitySettings() },
                                LinearLayout.LayoutParams(0, dp(38), 1f),
                            )
                            addView(
                                compactTextAction("通知设置") { openNotificationSettings() },
                                LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(7) },
                            )
                        },
                    )
                }
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
            OverlayThemeMode.DARK to "深色",
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
        setTextColor(if (selected) colors.accentTextOnFill else colors.primaryText)
        background = roundedBackground(if (selected) colors.accent else colors.fieldBackground, 12f, !selected)
        setOnClickListener { action() }
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

    private fun bottomNavigation(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setBackgroundColor(colors.navigationBackground)
        }
        listOf("状态", "剪贴板", "设置").forEachIndexed { index, label ->
            val selected = currentPage == index
            bar.addView(
                TextView(this).apply {
                    text = label
                    textSize = 13.5f
                    gravity = Gravity.CENTER
                    setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(if (selected) colors.accent else colors.secondaryText)
                    background = if (selected) roundedBackground(colors.selectedNavigationBackground, 14f) else null
                    setOnClickListener {
                        if (currentPage != index) {
                            currentPage = index
                            render()
                        }
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (index > 0) marginStart = dp(6)
                },
            )
        }
        return bar
    }

    private fun infoRow(title: String, detail: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8), 0, dp(8))
        addView(textView(title, 14.5f, false))
        addView(textView(detail, 12f, false, colors.secondaryText).apply { maxLines = 3 })
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
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("选择分类")
            .setSingleChoiceItems(categories.toTypedArray(), selected, null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.listView.setOnItemClickListener { _, _, position, _ ->
                clipStore.updateCategory(entry.id, categories[position])
                dialog.dismiss()
                render()
            }
        }
        dialog.show()
    }

    private fun syncEndpointCard(): View {
        val root = card()
        root.addView(textView("应用不内置任何同步域名。留空即关闭；未来同步只发送客户端加密后的 vault envelope，服务端不应接触明文。", 12f, false, colors.secondaryText))
        spacerInside(root, 10)
        val input = EditText(this).apply {
            hint = "https://你的同步服务地址"
            isSingleLine = true
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(vaultSettings.syncEndpoint())
            setSelection(text.length)
            setTextColor(colors.primaryText)
            setHintTextColor(colors.secondaryText)
            background = roundedBackground(colors.fieldBackground, 13f)
            setPadding(dp(13), 0, dp(13), 0)
        }
        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(input, LinearLayout.LayoutParams(0, dp(44), 1f))
                addView(
                    actionButton("保存", false) {
                        val value = input.text?.toString().orEmpty().trim()
                        if (!isValidSyncEndpoint(value)) {
                            Toast.makeText(this@MainActivity, "仅允许 HTTPS 地址，且 URL 中不能包含账号密码", Toast.LENGTH_LONG).show()
                        } else {
                            vaultSettings.saveSyncEndpoint(value)
                            Toast.makeText(this@MainActivity, if (value.isBlank()) "同步端点已清空" else "同步端点已保存到本机", Toast.LENGTH_SHORT).show()
                        }
                    },
                    LinearLayout.LayoutParams(dp(68), dp(44)).apply { marginStart = dp(8) },
                )
            },
        )
        spacerInside(root, 8)
        root.addView(textView("当前版本仅完成端点配置和端到端加密格式，不会自动上传剪贴板。", 11.5f, false, colors.secondaryText))
        return root
    }

    private fun isValidSyncEndpoint(value: String): Boolean {
        if (value.isBlank()) return true
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo.isNullOrBlank()
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
            setPadding(dp(22), dp(4), dp(22), 0)
            addView(first, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            addView(second, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(10) })
            addView(textView("PIN 不会明文写入磁盘。忘记 PIN 后，便携备份无法恢复。", 11.5f, false, colors.secondaryText).apply { setPadding(0, dp(10), 0, 0) })
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("设置加密 PIN")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val a = first.text?.toString().orEmpty()
                val b = second.text?.toString().orEmpty()
                if (!a.matches(Regex("\\d{6}")) || a != b) {
                    Toast.makeText(this, "请输入两次相同的 6 位数字 PIN", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val wasWriteArmed = vaultSettings.backupWriteArmed()
                vaultSettings.savePin(a)
                val tree = vaultSettings.backupTreeUri()
                if (tree != null) {
                    Thread {
                        when {
                            // Normal PIN rotation: the already trusted portable vault is immediately
                            // re-encrypted so the new PIN is valid even if the app is uninstalled next.
                            wasWriteArmed -> vaultBackupManager.backup(tree, a, clipStore.exportJson())
                            // Reinstall/reselect safety: never overwrite a pre-existing untrusted vault.
                            !vaultBackupManager.hasBackup(tree) ->
                                vaultBackupManager.initializeBackup(tree, a, clipStore.exportJson())
                        }
                    }.start()
                }
                dialog.dismiss()
                render()
            }
        }
        dialog.show()
    }

    private fun showRestorePinDialog() {
        val tree = vaultSettings.backupTreeUri()
        if (tree == null) {
            Toast.makeText(this, "请先选择保存 FloatClip.vault 的目录", Toast.LENGTH_LONG).show()
            return
        }
        val pin = securePinField("输入备份的 6 位 PIN")
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("恢复加密备份")
            .setMessage("恢复会合并为当前本地 vault 的新状态。")
            .setView(pin)
            .setNegativeButton("取消", null)
            .setPositiveButton("恢复", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val value = pin.text?.toString().orEmpty()
                if (!value.matches(Regex("\\d{6}"))) {
                    Toast.makeText(this, "请输入 6 位数字 PIN", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val button = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                button.isEnabled = false
                Thread {
                    val raw = vaultBackupManager.restore(tree, value)
                    runOnUiThread {
                        val ok = raw != null && clipStore.importJson(raw)
                        if (ok) {
                            vaultSettings.savePin(value)
                            // A successful authenticated restore is the explicit point at which this
                            // surviving backup becomes safe for subsequent automatic updates.
                            vaultSettings.setBackupWriteArmed(true)
                            Toast.makeText(this, "剪贴板备份已恢复，自动加密备份已重新启用", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                            render()
                        } else {
                            button.isEnabled = true
                            Toast.makeText(this, "恢复失败：PIN 不正确或备份已损坏", Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
        }
        dialog.show()
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

    private fun swipeDelete(action: () -> Unit): View = TextView(this).apply {
        text = "删除"
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = roundedBackground(Color.rgb(210, 65, 75), 14f)
        setOnClickListener { action() }
    }

    private fun addHistoryCard(entry: ClipEntry) {
        val item = card()
        item.addView(textView(entry.text, 15f, false).apply { maxLines = 3 })
        spacerInside(item, 7)
        item.addView(textView("${entry.category}${if (entry.pinned) " · 已置顶" else ""}", 12f, false, colors.secondaryText))
        val wrapper = SwipeRevealRow(this)
        wrapper.bind(item, swipeDelete {
            android.app.AlertDialog.Builder(this)
                .setTitle("删除这条记录？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除") { _, _ -> clipStore.delete(entry.id); render() }
                .show()
        }, dp(72))
        wrapper.onSingleTap = {
            getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("FloatClip", entry.text))
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
        }
        wrapper.onDoubleTap = { clipStore.togglePinned(entry.id); render(); notifyOverlayAppearanceChanged() }
        wrapper.onLongPress = {
            android.app.AlertDialog.Builder(this)
                .setTitle("词条选项")
                .setItems(arrayOf("更改分类", if (entry.pinned) "取消置顶" else "置顶")) { _, which ->
                    if (which == 0) showCategoryPicker(entry)
                    else { clipStore.togglePinned(entry.id); render(); notifyOverlayAppearanceChanged() }
                }.show()
        }
        content.addView(wrapper, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun installCategoryDrag(
        handle: View, row: SwipeRevealRow, list: LinearLayout,
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
            target = (from + kotlin.math.round(dy / stride).toInt()).coerceIn(0, items.lastIndex)
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
                    row.close(false)
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
                    views.forEach { it.animate().cancel(); it.translationY = 0f; it.tag = null }
                    list.removeAllViews()
                    views.forEach { list.addView(it) }
                    row.elevation = 0f
                    row.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
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
        android.app.AlertDialog.Builder(this)
            .setTitle("OriginOS 兼容")
            .setMessage(text)
            .setPositiveButton("知道了", null)
            .show()
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

    private fun settingRow(title: String, subtitle: String, actionLabel: String, action: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(textView(title, 15f, false))
            addView(textView(subtitle, 12f, false, colors.secondaryText).apply { maxLines = 2 })
        }
        row.addView(labels, LinearLayout.LayoutParams(0, dp(58), 1f))
        row.addView(compactTextAction(actionLabel, action), LinearLayout.LayoutParams(dp(68), dp(36)))
        return row
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = cardBackground()
        elevation = 0f
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
        background = roundedBackground(if (primary) colors.accent else colors.cardBackground, 14f, !primary)
        setOnClickListener { action() }
    }

    private fun compactTextAction(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12.5f
        gravity = Gravity.CENTER
        setTextColor(colors.accent)
        background = roundedBackground(colors.actionBackground, 11f)
        setOnClickListener { action() }
    }

    private fun textView(textValue: String, sizeSp: Float, bold: Boolean, color: Int = colors.primaryText): TextView = TextView(this).apply {
        text = textValue
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(colors.dividerColor)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun cardBackground(): GradientDrawable = roundedBackground(colors.cardBackground, 18f, true)

    private fun roundedBackground(color: Int, radiusDp: Float, border: Boolean = false): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (border) setStroke(dp(1), this@MainActivity.colors.borderColor)
    }

    private fun spacer(heightDp: Int) {
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(heightDp)))
    }

    private fun spacerInside(parent: LinearLayout, heightDp: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(heightDp)))
    }

    private fun openOverlayPermission() {
        android.app.AlertDialog.Builder(this)
            .setTitle("允许显示悬浮窗")
            .setMessage("请在接下来的系统页面找到 FloatClip，开启“显示在其他应用上层”。返回后会重新检查授权状态。")
            .setNegativeButton("暂不", null)
            .setPositiveButton("去授权") { _, _ ->
                openSystemSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }.show()
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
        return if (dark) {
            AppColors(
                pageBackground = Color.rgb(18, 19, 22),
                cardBackground = Color.rgb(29, 31, 36),
                primaryText = Color.rgb(244, 245, 247),
                secondaryText = Color.rgb(166, 170, 180),
                dividerColor = Color.rgb(52, 55, 62),
                accent = Color.rgb(122, 157, 255),
                accentTextOnFill = Color.rgb(14, 22, 42),
                actionBackground = Color.rgb(37, 47, 70),
                fieldBackground = Color.rgb(38, 40, 46),
                borderColor = Color.rgb(53, 56, 63),
                navigationBackground = Color.rgb(23, 25, 29),
                selectedNavigationBackground = Color.rgb(35, 42, 58),
                dark = true,
            )
        } else {
            AppColors(
                pageBackground = Color.rgb(247, 248, 250),
                cardBackground = Color.WHITE,
                primaryText = Color.rgb(25, 27, 31),
                secondaryText = Color.rgb(105, 110, 120),
                dividerColor = Color.rgb(230, 232, 237),
                accent = Color.rgb(55, 100, 245),
                accentTextOnFill = Color.WHITE,
                actionBackground = Color.rgb(241, 245, 255),
                fieldBackground = Color.rgb(243, 244, 247),
                borderColor = Color.rgb(233, 235, 240),
                navigationBackground = Color.rgb(252, 252, 253),
                selectedNavigationBackground = Color.rgb(238, 243, 255),
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
        private const val PAGE_STATUS = 0
        private const val PAGE_CLIPBOARD = 1
        private const val PAGE_SETTINGS = 2
        private const val STATE_PAGE = "current_page"
        private const val ALL_CATEGORY = "全部"
        private const val REQUEST_BACKUP_TREE = 6201
    }
}
