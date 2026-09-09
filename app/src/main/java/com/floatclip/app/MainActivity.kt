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
import android.widget.TextView
import com.floatclip.app.accessibility.PasteAccessibilityService
import com.floatclip.app.clipboard.ClipStore
import com.floatclip.app.integration.IntegrationRegistry
import com.floatclip.app.model.ClipEntry
import com.floatclip.app.overlay.ClipboardOverlayService
import com.floatclip.app.prefs.CategoryStore

class MainActivity : Activity() {
    private lateinit var categoryStore: CategoryStore
    private lateinit var clipStore: ClipStore
    private lateinit var content: LinearLayout
    private var searchQuery: String = ""

    private val pageBackground = Color.rgb(246, 247, 249)
    private val cardBackground = Color.WHITE
    private val primaryText = Color.rgb(27, 29, 34)
    private val secondaryText = Color.rgb(112, 116, 126)
    private val dividerColor = Color.rgb(231, 233, 238)
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
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) render()
    }

    private fun render() {
        val scroll = ScrollView(this).apply { setBackgroundColor(pageBackground) }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(34))
        }
        scroll.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        setContentView(scroll)

        content.addView(textView("FloatClip", 30f, true))
        content.addView(textView("OriginOS 悬浮剪贴板", 14f, false, secondaryText))
        spacer(18)

        val romLock = IntegrationRegistry.romLockResult()
        val bridgeReady = IntegrationRegistry.originOsSystemBridge.isAvailable()
        val statusCard = card().apply {
            addView(textView("运行状态", 16f, true))
            spacerInside(this, 10)
            addView(statusRow("悬浮窗", if (Settings.canDrawOverlays(this@MainActivity)) "已授权" else "未授权"))
            addView(divider())
            addView(statusRow("一键粘贴", if (PasteAccessibilityService.isConnected()) "已连接" else "未启用"))
            addView(divider())
            addView(statusRow("OriginOS", if (bridgeReady) "ROM 已匹配" else "独立模式"))
        }
        addCard(statusCard)

        spacer(12)
        val runRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        runRow.addView(
            actionButton("启动悬浮剪贴板", true, ::startOverlay),
            LinearLayout.LayoutParams(0, dp(48), 1f),
        )
        val stopLp = LinearLayout.LayoutParams(dp(86), dp(48)).apply { marginStart = dp(10) }
        runRow.addView(actionButton("停止", false, ::stopOverlay), stopLp)
        content.addView(runRow)

        sectionTitle("设置")
        addCard(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
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

        sectionTitle("分类")
        val categoryCard = card()
        val categoryInput = EditText(this).apply {
            hint = "新分类"
            isSingleLine = true
            textSize = 15f
            setTextColor(primaryText)
            setHintTextColor(secondaryText)
            background = roundedBackground(Color.rgb(243, 244, 247), 13f)
            setPadding(dp(13), 0, dp(13), 0)
        }
        val addCategory = actionButton("添加", false) {
            if (categoryStore.add(categoryInput.text?.toString().orEmpty())) render()
        }
        val categoryInputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(categoryInput, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(addCategory, LinearLayout.LayoutParams(dp(74), dp(44)).apply { marginStart = dp(8) })
        }
        categoryCard.addView(categoryInputRow)

        val categories = categoryStore.categories().filterNot { it == CategoryStore.DEFAULT_CATEGORY }
        if (categories.isNotEmpty()) spacerInside(categoryCard, 8)
        categories.forEachIndexed { index, category ->
            if (index > 0) categoryCard.addView(divider())
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(textView(category, 15f, false), LinearLayout.LayoutParams(0, dp(44), 1f))
                addView(compactTextAction("删除") {
                    clipStore.moveCategoryToDefault(category)
                    categoryStore.delete(category)
                    render()
                }, LinearLayout.LayoutParams(dp(54), dp(36)))
            }
            categoryCard.addView(row)
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
            background = roundedBackground(Color.rgb(243, 244, 247), 13f)
            setPadding(dp(13), 0, dp(13), 0)
        }
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(search, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(actionButton("搜索", false) {
                searchQuery = search.text?.toString().orEmpty().trim()
                render()
            }, LinearLayout.LayoutParams(dp(74), dp(44)).apply { marginStart = dp(8) })
        }
        searchCard.addView(searchRow)
        addCard(searchCard)

        val visibleHistory = clipStore.entries().filter(::matchesSearch).take(30)
        if (visibleHistory.isEmpty()) {
            val empty = card().apply {
                gravity = Gravity.CENTER
                addView(textView("暂无匹配记录", 14f, false, secondaryText))
            }
            addCard(empty, topMarginDp = 10)
        } else {
            visibleHistory.forEach { addHistoryCard(it) }
        }

        spacer(18)
        content.addView(
            textView(
                "悬浮面板现在会在展开时自动同步当前系统剪贴板。vivo 输入法自己的历史属于系统输入法私有数据，后续通过 ROM 专项 Bridge 单独接入。",
                12f,
                false,
                secondaryText,
            ),
        )
    }

    private fun matchesSearch(entry: ClipEntry): Boolean =
        searchQuery.isBlank() || entry.text.contains(searchQuery, ignoreCase = true) ||
            entry.category.contains(searchQuery, ignoreCase = true)

    private fun addHistoryCard(entry: ClipEntry) {
        val card = card().apply {
            setOnClickListener {
                // History management stays non-destructive on the settings page.
            }
        }
        val body = TextView(this).apply {
            text = entry.text
            maxLines = 3
            textSize = 15f
            setTextColor(primaryText)
        }
        card.addView(body)
        spacerInside(card, 7)

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                textView("${entry.category}${if (entry.pinned) " · 已置顶" else ""}", 12f, false, secondaryText),
                LinearLayout.LayoutParams(0, dp(36), 1f),
            )
            addView(compactTextAction(if (entry.pinned) "取消置顶" else "置顶") {
                clipStore.togglePinned(entry.id)
                render()
            }, LinearLayout.LayoutParams(dp(72), dp(34)))
            addView(compactTextAction("删除") {
                clipStore.delete(entry.id)
                render()
            }, LinearLayout.LayoutParams(dp(54), dp(34)).apply { marginStart = dp(6) })
        }
        card.addView(footer)
        addCard(card, topMarginDp = 10)
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
        background = roundedBackground(cardBackground, 18f)
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

    private fun sectionTitle(text: String) {
        spacer(22)
        content.addView(textView(text, 16f, true))
        spacer(9)
    }

    private fun actionButton(label: String, primary: Boolean, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        setPadding(dp(12), 0, dp(12), 0)
        setTextColor(if (primary) Color.WHITE else primaryText)
        background = roundedBackground(if (primary) accentColor else cardBackground, 14f)
        if (!primary) elevation = dp(1).toFloat()
        setOnClickListener { action() }
    }

    private fun compactTextAction(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12.5f
        gravity = Gravity.CENTER
        setTextColor(accentColor)
        background = roundedBackground(Color.rgb(240, 244, 255), 11f)
        setOnClickListener { action() }
    }

    private fun textView(
        textValue: String,
        sizeSp: Float,
        bold: Boolean,
        color: Int = primaryText,
    ): TextView = TextView(this).apply {
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

    private fun roundedBackground(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun spacer(heightDp: Int) {
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(heightDp)))
    }

    private fun spacerInside(parent: LinearLayout, heightDp: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(heightDp)))
    }

    private fun openOverlayPermission() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
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
