package com.floatclip.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
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
    private val overlayServiceIntent by lazy(LazyThreadSafetyMode.NONE) {
        Intent(this, ClipboardOverlayService::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        scroll.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        setContentView(scroll)

        content.addView(textView("FloatClip", 26f, true))
        content.addView(textView("Android 11 / OriginOS 悬浮剪贴板", 15f, false))
        spacer(12)
        content.addView(textView("悬浮窗权限：${if (Settings.canDrawOverlays(this)) "已授权" else "未授权"}", 14f, false))
        content.addView(textView("一键粘贴：${if (PasteAccessibilityService.isConnected()) "已连接" else "未启用"}", 14f, false))
        val romLock = IntegrationRegistry.romLockResult()
        val bridgeStatus = if (IntegrationRegistry.originOsSystemBridge.isAvailable()) {
            "ROM 锁定匹配 · 语义资源桥已启用"
        } else {
            "独立模式 · ${romLock?.state?.name ?: "UNINITIALIZED"}"
        }
        content.addView(textView("OriginOS Bridge：$bridgeStatus", 13f, false))
        spacer(8)

        addButtonRow(
            button("悬浮窗权限", ::openOverlayPermission),
            button("一键粘贴", ::openAccessibilitySettings),
        )
        addButtonRow(
            button("启动悬浮剪贴板", ::startOverlay),
            button("停止", ::stopOverlay),
        )

        sectionTitle("自定义分类")
        val categoryInput = EditText(this).apply {
            hint = "新分类"
            isSingleLine = true
        }
        val addCategory = button("添加") {
            if (categoryStore.add(categoryInput.text?.toString().orEmpty())) render()
        }
        val categoryInputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(categoryInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(addCategory, LinearLayout.LayoutParams(dp(88), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        content.addView(categoryInputRow)

        categoryStore.categories().filterNot { it == CategoryStore.DEFAULT_CATEGORY }.forEach { category ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(textView(category, 15f, false), LinearLayout.LayoutParams(0, dp(48), 1f))
            row.addView(button("删除") {
                clipStore.moveCategoryToDefault(category)
                categoryStore.delete(category)
                render()
            })
            content.addView(row)
        }

        sectionTitle("历史管理")
        val search = EditText(this).apply {
            hint = "搜索内容或分类"
            isSingleLine = true
            setText(searchQuery)
            setSelection(text.length)
        }
        content.addView(search)
        content.addView(button("搜索") {
            searchQuery = search.text?.toString().orEmpty().trim()
            render()
        })

        val visibleHistory = clipStore.entries().filter(::matchesSearch).take(30)
        if (visibleHistory.isEmpty()) {
            content.addView(textView("暂无匹配记录", 14f, false))
        } else {
            visibleHistory.forEach(::addHistoryRow)
        }

        spacer(18)
        content.addView(
            textView(
                "Android 11 会限制普通后台 App 持续读取剪贴板。独立模式保留主动导入；自动采集与 OriginOS 原生主题复用由后续 SystemUI/特权桥接层提供。",
                12f,
                false,
            ),
        )
    }

    private fun matchesSearch(entry: ClipEntry): Boolean =
        searchQuery.isBlank() || entry.text.contains(searchQuery, ignoreCase = true) ||
            entry.category.contains(searchQuery, ignoreCase = true)

    private fun addHistoryRow(entry: ClipEntry) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        card.addView(textView(entry.text, 15f, false).apply { maxLines = 3 })
        card.addView(
            textView(
                "${entry.category}${if (entry.pinned) " · 已置顶" else ""}",
                12f,
                false,
            ),
        )
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button(if (entry.pinned) "取消置顶" else "置顶") {
            clipStore.togglePinned(entry.id)
            render()
        })
        actions.addView(button("删除") {
            clipStore.delete(entry.id)
            render()
        })
        card.addView(actions)
        content.addView(card)
    }

    private fun sectionTitle(text: String) {
        spacer(18)
        content.addView(textView(text, 18f, true))
        spacer(4)
    }

    private fun addButtonRow(vararg buttons: Button) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        buttons.forEach { row.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        content.addView(row)
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun textView(textValue: String, sizeSp: Float, bold: Boolean): TextView = TextView(this).apply {
        text = textValue
        textSize = sizeSp
        setTextColor(Color.rgb(32, 32, 36))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun spacer(heightDp: Int) {
        content.addView(TextView(this), LinearLayout.LayoutParams(1, dp(heightDp)))
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
}
