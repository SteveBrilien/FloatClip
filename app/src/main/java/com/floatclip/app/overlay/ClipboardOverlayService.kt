package com.floatclip.app.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.floatclip.app.R
import com.floatclip.app.accessibility.PasteAccessibilityService
import com.floatclip.app.clipboard.ClipStore
import com.floatclip.app.clipboard.ClipboardRepository
import com.floatclip.app.integration.AccessibilityPasteBridge
import com.floatclip.app.integration.IntegrationRegistry
import com.floatclip.app.model.ClipEntry
import com.floatclip.app.prefs.CategoryStore
import com.floatclip.app.prefs.OverlayPreferences
import com.floatclip.app.theme.AdaptiveOverlayThemeProvider
import com.floatclip.app.theme.OverlayThemeProvider
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ClipboardOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var store: ClipStore
    private lateinit var clipboard: ClipboardRepository
    private lateinit var categoryStore: CategoryStore
    private lateinit var overlayPreferences: OverlayPreferences
    private lateinit var themeProvider: OverlayThemeProvider
    private val handler = Handler(Looper.getMainLooper())

    private var bubbleView: View? = null
    private var panelView: View? = null
    private var listContainer: LinearLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelPinnedMode = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        store = ClipStore(this)
        clipboard = ClipboardRepository(this)
        categoryStore = CategoryStore(this)
        overlayPreferences = OverlayPreferences(this)
        IntegrationRegistry.initialize(this)
        themeProvider = AdaptiveOverlayThemeProvider(this, IntegrationRegistry.originOsSystemBridge)
        startForeground(NOTIFICATION_ID, createNotification())
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (bubbleView == null && panelView == null) showBubble()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val reopenPanel = panelView != null
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        panelView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        panelView = null
        listContainer = null
        showBubble()
        if (reopenPanel) expandPanel()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        panelView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        panelView = null
        super.onDestroy()
    }

    private fun showBubble() {
        panelView?.let { runCatching { windowManager.removeView(it) } }
        panelView = null
        listContainer = null

        val palette = themeProvider.palette()
        val screen = screenSize()
        val size = dp(48)
        val previous = bubbleParams
        val initialX = if (overlayPreferences.bubbleOnRight()) max(0, screen.x - size) else 0
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = previous?.x ?: initialX
            y = (previous?.y ?: overlayPreferences.bubbleY(dp(220)))
                .coerceIn(0, max(0, screen.y - size))
        }
        bubbleParams = params

        val bubble = TextView(this).apply {
            text = "▤"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(palette.primaryText)
            background = roundedBackground(palette.bubbleBackground, palette.bubbleCornerRadiusDp)
            elevation = dp(palette.elevationDp.toInt()).toFloat()
            contentDescription = "打开 FloatClip"
            setOnClickListener { expandPanel() }
        }
        installBubbleTouch(bubble, params)
        bubbleView = bubble
        windowManager.addView(bubble, params)
    }

    private fun installBubbleTouch(view: View, params: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > dp(5) || abs(dy) > dp(5)) moved = true
                    val screen = screenSize()
                    params.x = (startX + dx.toInt()).coerceIn(0, max(0, screen.x - params.width))
                    params.y = (startY + dy.toInt()).coerceIn(0, max(0, screen.y - params.height))
                    runCatching { windowManager.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) snapBubble(view, params)
                    else if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun snapBubble(view: View, params: WindowManager.LayoutParams) {
        val screen = screenSize()
        val onRight = params.x + params.width / 2 >= screen.x / 2
        params.x = if (onRight) max(0, screen.x - params.width) else 0
        params.y = params.y.coerceIn(0, max(0, screen.y - params.height))
        runCatching { windowManager.updateViewLayout(view, params) }
        overlayPreferences.saveBubble(params.y, onRight)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun expandPanel() {
        val bubble = bubbleView ?: return
        val oldBubbleParams = bubbleParams ?: return
        runCatching { windowManager.removeView(bubble) }
        bubbleView = null

        val palette = themeProvider.palette()
        val screen = screenSize()
        val panelWidth = min(dp(352), screen.x - dp(20))
        val panelX = if (oldBubbleParams.x < screen.x / 2) {
            dp(10)
        } else {
            max(dp(10), screen.x - panelWidth - dp(10))
        }
        val panelY = oldBubbleParams.y.coerceIn(dp(24), max(dp(24), screen.y - dp(430)))
        val params = if (panelPinnedMode) {
            WindowManager.LayoutParams(
                panelWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.START or Gravity.TOP
                x = panelX
                y = panelY
            }
        } else {
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.START or Gravity.TOP
                x = 0
                y = 0
            }
        }
        panelParams = params

        val panel = FrameLayout(this).apply {
            isFocusableInTouchMode = true
            isClickable = true
            background = roundedBackground(palette.panelBackground, palette.panelCornerRadiusDp)
            elevation = dp(palette.elevationDp.toInt()).toFloat()
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { }
        }

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(
            content,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
        )

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "剪贴板"
            textSize = 17f
            setTextColor(palette.primaryText)
            setPadding(dp(4), 0, 0, 0)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(40), 1f))

        val fixedToggle = iconTextButton(if (panelPinnedMode) "● 固定" else "○ 固定") {
            panelPinnedMode = !panelPinnedMode
            collapseAndReopenPanel()
        }
        header.addView(fixedToggle, LinearLayout.LayoutParams(dp(72), dp(36)))

        val close = iconTextButton("⌄") { collapsePanel() }.apply {
            textSize = 20f
            contentDescription = "收回"
        }
        header.addView(close, LinearLayout.LayoutParams(dp(38), dp(36)))
        content.addView(header)

        val categoryHint = TextView(this).apply {
            text = if (PasteAccessibilityService.isConnected()) {
                "打开即同步当前剪贴板 · 点击条目直接粘贴"
            } else {
                "打开即同步当前剪贴板 · 点击条目复制"
            }
            textSize = 11.5f
            setTextColor(palette.secondaryText)
            setPadding(dp(4), 0, dp(4), dp(7))
        }
        content.addView(categoryHint)

        val scroll = ScrollView(this)
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(
            listContainer,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
        )
        content.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(320)))
        val root: View = if (panelPinnedMode) {
            panel
        } else {
            FrameLayout(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                isClickable = true
                setOnClickListener { collapsePanel() }
                addView(
                    panel,
                    FrameLayout.LayoutParams(panelWidth, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                        leftMargin = panelX
                        topMargin = panelY
                    },
                )
            }
        }


        panelView = root
        windowManager.addView(root, params)
        renderEntries()
        handler.postDelayed({ captureCurrentClipboardWithTemporaryFocus(showFeedback = false) }, 80L)
    }

    private fun renderEntries() {
        val container = listContainer ?: return
        val palette = themeProvider.palette()
        container.removeAllViews()
        val items = store.entries()
        if (items.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.empty_history_hint)
                textSize = 15f
                setTextColor(palette.primaryText)
                setPadding(dp(10), dp(24), dp(10), dp(24))
            })
            return
        }
        items.forEach { container.addView(createClipRow(it)) }
    }

    private fun createClipRow(entry: ClipEntry): View {
        val palette = themeProvider.palette()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(8), dp(5), dp(8))
            background = roundedBackground(palette.rowBackground, palette.rowCornerRadiusDp)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { pasteEntry(entry) }
        }
        body.addView(TextView(this).apply {
            text = entry.text
            maxLines = 2
            textSize = 14.5f
            setTextColor(palette.primaryText)
        })
        val category = TextView(this).apply {
            text = entry.category
            textSize = 12f
            setTextColor(palette.secondaryText)
            setPadding(0, dp(4), 0, 0)
            setOnClickListener { cycleCategory(entry) }
        }
        body.addView(category)
        row.addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(
            iconTextButton(if (entry.pinned) "★" else "☆") {
                store.togglePinned(entry.id)
                renderEntries()
            },
            LinearLayout.LayoutParams(dp(40), dp(40)),
        )

        row.addView(
            iconTextButton("×") {
                store.delete(entry.id)
                renderEntries()
            }.apply { contentDescription = "删除" },
            LinearLayout.LayoutParams(dp(40), dp(40)),
        )

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.setMargins(0, dp(4), 0, dp(4))
        row.layoutParams = lp
        return row
    }

    private fun cycleCategory(entry: ClipEntry) {
        val categories = categoryStore.categories().ifEmpty { listOf(CategoryStore.DEFAULT_CATEGORY) }
        val current = categories.indexOf(entry.category).coerceAtLeast(0)
        val next = categories[(current + 1) % categories.size]
        store.updateCategory(entry.id, next)
        renderEntries()
    }

    private fun pasteEntry(entry: ClipEntry) {
        val pasted = AccessibilityPasteBridge.pasteText(entry.text)
        if (!pasted) {
            clipboard.writeText(entry.text)
            Toast.makeText(this, "已复制；启用一键粘贴服务后可直接粘贴", Toast.LENGTH_SHORT).show()
        }
        if (!panelPinnedMode) collapsePanel()
    }

    private fun captureCurrentClipboardWithTemporaryFocus(showFeedback: Boolean) {
        val root = panelView ?: return
        val params = panelParams ?: return
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        runCatching { windowManager.updateViewLayout(root, params) }
        root.requestFocus()

        handler.postDelayed({
            val text = runCatching { clipboard.readCurrentText() }.getOrNull()
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            runCatching { windowManager.updateViewLayout(root, params) }
            if (text != null && store.addText(text) != null) {
                renderEntries()
                if (showFeedback) Toast.makeText(this, "已加入 FloatClip", Toast.LENGTH_SHORT).show()
            } else if (showFeedback) {
                Toast.makeText(this, "未读取到内容；该 ROM 可能限制悬浮窗读取剪贴板", Toast.LENGTH_LONG).show()
            }
        }, CLIPBOARD_FOCUS_DELAY_MS)
    }

    private fun collapseAndReopenPanel() {
        panelView?.let { runCatching { windowManager.removeView(it) } }
        panelView = null
        listContainer = null
        showBubble()
        handler.postDelayed({ expandPanel() }, 40L)
    }

    private fun iconTextButton(label: String, action: () -> Unit): TextView {
        val palette = themeProvider.palette()
        return TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(palette.primaryText)
            background = roundedBackground(palette.rowBackground, 12f)
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { action() }
        }
    }

    private fun collapsePanel() {
        val panel = panelView
        if (panel != null) runCatching { windowManager.removeView(panel) }
        panelView = null
        listContainer = null
        showBubble()
    }

    private fun createNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "FloatClip 悬浮服务", NotificationManager.IMPORTANCE_LOW),
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("FloatClip 正在运行")
            .setContentText("点击悬浮球打开剪贴板")
            .setOngoing(true)
            .build()
    }

    private fun roundedBackground(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * resources.displayMetrics.density
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Point = Point().also { windowManager.defaultDisplay.getSize(it) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val CHANNEL_ID = "floatclip_overlay"
        private const val NOTIFICATION_ID = 2001
        private const val CLIPBOARD_FOCUS_DELAY_MS = 180L
    }
}
