package com.floatclip.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.floatclip.app.accessibility.PasteAccessibilityService
import com.floatclip.app.clipboard.ClipStore
import com.floatclip.app.integration.IntegrationRegistry
import com.floatclip.app.model.ClipEntry
import com.floatclip.app.overlay.ClipboardOverlayService
import com.floatclip.app.prefs.CategoryStore
import com.floatclip.app.prefs.OverlayPreferences

class MainActivity : Activity() {
    private lateinit var categoryStore: CategoryStore
    private lateinit var clipStore: ClipStore
    private lateinit var overlayPreferences: OverlayPreferences
    private lateinit var content: LinearLayout
    private var searchQuery: String = ""

    private val pageBackground = Color.rgb(247, 248, 250)
    private val cardBackground = Color.WHITE
    private val primaryText = Color.rgb(25, 27, 31)
    private val secondaryText = Color.rgb(111, 115, 124)
    private val dividerColor = Color.rgb(232, 234, 239)
    private val accentColor = Color.rgb(55, 100, 245)

    private val overlayServiceIntent by lazy(LazyThreadSafetyMode.NONE) {
        Intent(this, ClipboardOverlayService::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = pageBackground
        window.navigationBarColor = pageBackground
        @Suppress("DEPRECATION")
        run {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        IntegrationRegistry.initialize(this)
        categoryStore = CategoryStore(this)
        clipStore = ClipStore(this)
        overlayPreferences = OverlayPreferences(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) render()
    }

    private fun render() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(pageBackground)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(34))
        }
        scroll.addView(
            content,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        setContentView(scroll)

        content.addView(textView("FloatClip", 29f, true))
        content.addView(textView("0.3.0 · OriginOS 悬浮剪贴板", 13f, false, secondaryText))
        spacer(18)

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

        sectionTitle("设置")
        addCard(
            card().apply {
                addView(settingRow("悬浮窗权限", "允许 FloatClip 显示在其他应用上层", if (Settings.canDrawOverlays(this@MainActivity)) "已授权" else "打开", ::openOverlayPermission))
                addView(divider())
                addView(settingRow("一键粘贴", "点击剪贴板条目后直接写入当前输入框", if (PasteAccessibilityService.isConnected()) "已连接" else "设置", ::openAccessibilitySettings))
                addView(divider())
                addView(
                    settingRow(
                        "OriginOS 兼容",
                        if (bridgeReady) "FloatingBall ROM Lock 匹配，语义主题桥可用" else "${romLock?.state?.name ?: "UNINITIALIZED"} · 使用独立悬浮层",
                        "详情",
                    ) { showCompatibilityDetails(bridgeReady, romLock?.state?.name) },
                )
            },
        )

        sectionTitle("悬浮窗外观")
        addCard(
            card().apply {
                addView(textView("展开面板支持自由拖动，位置会自动记忆。调整后重新展开即可看到效果。", 12f, false, secondaryText))
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
                addView(divider())
                addView(
                    sliderSetting(
                        "悬浮球大小",
                        42,
                        64,
                        overlayPreferences.bubbleSizeDp(),
                        "dp",
                    ) { overlayPreferences.saveBubbleSizeDp(it) },
                )
                spacerInside(this, 8)
                addView(
                    compactTextAction("恢复默认外观") {
                        overlayPreferences.resetAppearance()
                        render()
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)),
                )
            },
        )

        sectionTitle("分类")
        val categoryCard = card()
        val categoryInput = EditText(this).apply {
            hint = "新分类"
            isSingleLine = true
            textSize = 15f
            setTextColor(primaryText)
            setHintTextColor(secondaryText)
            background = roundedBackground(Color.rgb(244, 245, 248), 13f)
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
        if (categories.isNotEmpty()) spacerInside(categoryCard, 8)
        categories.forEachIndexed { index, category ->
            if (index > 0) categoryCard.addView(divider())
            categoryCard.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(textView(category, 15f, false), LinearLayout.LayoutParams(0, dp(44), 1f))
                    addView(
                        compactTextAction("删除") {
                            clipStore.moveCategoryToDefault(category)
                            categoryStore.delete(category)
                            render()
                        },
                        LinearLayout.LayoutParams(dp(54), dp(36)),
                    )
                },
            )
        }
        addCard(categoryCard)

        sectionTitle("历史")
        val searchCard = card()
        val search = EditText(this).apply {
            hint = "搜索内容或分类"
            isSingleLine = true
            textSize = 15f
            setText(searchQuery)
            setSelection(text.length)
            setTextColor(primaryText)
            setHintTextColor(secondaryText)
            background = roundedBackground(Color.rgb(244, 245, 248), 13f)
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

        val visibleHistory = clipStore.entries().filter(::matchesSearch).take(30)
        if (visibleHistory.isEmpty()) {
            addCard(
                card().apply {
                    gravity = Gravity.CENTER
                    addView(textView("暂无匹配记录", 14f, false, secondaryText))
                },
                topMarginDp = 10,
            )
        } else {
            visibleHistory.forEach { addHistoryCard(it) }
        }

        spacer(18)
        content.addView(
            textView(
                "普通模式点击空白处只收回、不穿透；固定模式可边操作底层应用边连续粘贴。展开面板会自动同步当前系统剪贴板。",
                12f,
                false,
                secondaryText,
            ),
        )
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
        val valueLabel = textView("$currentValue$suffix", 12.5f, false, secondaryText)
        header.addView(valueLabel)
        root.addView(header)
        root.addView(
            SeekBar(this).apply {
                max = maxValue - minValue
                progress = currentValue.coerceIn(minValue, maxValue) - minValue
                progressTintList = android.content.res.ColorStateList.valueOf(accentColor)
                thumbTintList = android.content.res.ColorStateList.valueOf(accentColor)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val value = minValue + progress
                        valueLabel.text = "$value$suffix"
                        if (fromUser) onChanged(value)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)),
        )
        return root
    }

    private fun matchesSearch(entry: ClipEntry): Boolean =
        searchQuery.isBlank() || entry.text.contains(searchQuery, ignoreCase = true) ||
            entry.category.contains(searchQuery, ignoreCase = true)

    private fun addHistoryCard(entry: ClipEntry) {
        val item = card()
        item.addView(TextView(this).apply {
            text = entry.text
            maxLines = 3
            textSize = 15f
            setTextColor(primaryText)
        })
        spacerInside(item, 7)
        item.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    textView("${entry.category}${if (entry.pinned) " · 已置顶" else ""}", 12f, false, secondaryText),
                    LinearLayout.LayoutParams(0, dp(36), 1f),
                )
                addView(
                    compactTextAction(if (entry.pinned) "取消置顶" else "置顶") {
                        clipStore.togglePinned(entry.id)
                        render()
                    },
                    LinearLayout.LayoutParams(dp(72), dp(34)),
                )
                addView(
                    compactTextAction("删除") {
                        clipStore.delete(entry.id)
                        render()
                    },
                    LinearLayout.LayoutParams(dp(54), dp(34)).apply { marginStart = dp(6) },
                )
            },
        )
        addCard(item, topMarginDp = 10)
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
        row.addView(textView(value, 13f, false, if (value.contains("已") || value.contains("匹配")) accentColor else secondaryText))
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
            addView(textView(subtitle, 12f, false, secondaryText).apply { maxLines = 2 })
        }
        row.addView(labels, LinearLayout.LayoutParams(0, dp(58), 1f))
        row.addView(compactTextAction(actionLabel, action), LinearLayout.LayoutParams(dp(62), dp(36)))
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

    private fun sectionTitle(text: String) {
        spacer(22)
        content.addView(textView(text, 16f, true))
        spacer(9)
    }

    private fun actionButton(label: String, primary: Boolean, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(12), 0, dp(12), 0)
        setTextColor(if (primary) Color.WHITE else primaryText)
        background = roundedBackground(if (primary) accentColor else cardBackground, 14f, !primary)
        setOnClickListener { action() }
    }

    private fun compactTextAction(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12.5f
        gravity = Gravity.CENTER
        setTextColor(accentColor)
        background = roundedBackground(Color.rgb(242, 245, 255), 11f)
        setOnClickListener { action() }
    }

    private fun textView(textValue: String, sizeSp: Float, bold: Boolean, color: Int = primaryText): TextView = TextView(this).apply {
        text = textValue
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(dividerColor)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun cardBackground(): GradientDrawable = roundedBackground(cardBackground, 18f, true)

    private fun roundedBackground(color: Int, radiusDp: Float, border: Boolean = false): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (border) setStroke(dp(1), Color.rgb(235, 237, 241))
    }

    private fun spacer(heightDp: Int) {
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(heightDp)))
    }

    private fun spacerInside(parent: LinearLayout, heightDp: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(heightDp)))
    }

    private fun openOverlayPermission() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlayPermission()
            return
        }
        startForegroundService(overlayServiceIntent)
    }

    private fun stopOverlay() {
        stopService(overlayServiceIntent)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()
}
