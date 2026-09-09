package com.floatclip.app.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.res.ColorStateList
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
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
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
    private var panelSurfaceView: View? = null
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
        panelSurfaceView = null
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
        panelSurfaceView = null
        super.onDestroy()
    }

    private fun showBubble() {
        panelView?.let { runCatching { windowManager.removeView(it) } }
        panelView = null
        panelSurfaceView = null
        listContainer = null

        val screen = screenSize()
        val sizeDp = overlayPreferences.bubbleSizeDp()
        val size = dp(sizeDp)
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

        val bubble = ImageView(this).apply {
            setImageResource(R.drawable.ic_copy_paste)
            imageTintList = ColorStateList.valueOf(Color.rgb(20, 20, 22))
            setPadding(dp(13), dp(13), dp(13), dp(13))
            background = circleBackground(Color.argb(224, 255, 255, 255))
            elevation = dp(10).toFloat()
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
        val panelWidth = min(dp(overlayPreferences.panelWidthDp()), screen.x - dp(20))
        val panelHeight = min(dp(overlayPreferences.panelHeightDp()), screen.y - dp(48))
        val defaultX = if (oldBubbleParams.x < screen.x / 2) dp(10) else max(dp(10), screen.x - panelWidth - dp(10))
        val defaultY = oldBubbleParams.y.coerceIn(dp(24), max(dp(24), screen.y - panelHeight - dp(24)))
        val panelX = overlayPreferences.panelX(defaultX).coerceIn(dp(8), max(dp(8), screen.x - panelWidth - dp(8)))
        val panelY = overlayPreferences.panelY(defaultY).coerceIn(dp(16), max(dp(16), screen.y - panelHeight - dp(16)))

        val params = if (panelPinnedMode) {
            WindowManager.LayoutParams(
                panelWidth,
                panelHeight,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
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

        val panelAlpha = (255f * overlayPreferences.panelAlphaPercent() / 100f).toInt().coerceIn(0, 255)
        val panel = FrameLayout(this).apply {
            isFocusableInTouchMode = true
            isClickable = true
            background = roundedBackground(withAlpha(palette.panelBackground, panelAlpha), palette.panelCornerRadiusDp, true)
            elevation = dp(14).toFloat()
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { }
        }
        panelSurfaceView = panel

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(
            content,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), 0)
        }
        header.addView(
            passiveIcon(R.drawable.ic_drag, "拖动面板"),
            LinearLayout.LayoutParams(dp(34), dp(40)),
        )
        val title = TextView(this).apply {
            text = "剪贴板"
            textSize = 17f
            setTextColor(palette.primaryText)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(42), 1f))

        header.addView(
            iconButton(
                R.drawable.ic_pin,
                if (panelPinnedMode) "退出固定模式" else "固定模式",
                if (panelPinnedMode) Color.rgb(55, 100, 245) else palette.primaryText,
            ) {
                panelPinnedMode = !panelPinnedMode
                collapseAndReopenPanel()
            },
            LinearLayout.LayoutParams(dp(40), dp(40)),
        )
        header.addView(
            iconButton(R.drawable.ic_chevron_down, "收回", palette.primaryText) { collapsePanel() },
            LinearLayout.LayoutParams(dp(40), dp(40)),
        )
        content.addView(header)

        installPanelDrag(header, panel, params, panelWidth, panelHeight)

        val categoryHint = TextView(this).apply {
            text = if (PasteAccessibilityService.isConnected()) {
                "自动同步 · 点击即粘贴 · 拖住顶部移动"
            } else {
                "自动同步 · 点击即复制 · 拖住顶部移动"
            }
            textSize = 11.5f
            setTextColor(palette.secondaryText)
            setPadding(dp(6), 0, dp(6), dp(6))
        }
        content.addView(categoryHint)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(1), 0, dp(4))
        }
        scroll.addView(
            listContainer,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        content.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val root: View = if (panelPinnedMode) {
            panel
        } else {
            FrameLayout(this).apply {
                setBackgroundColor(Color.argb(10, 0, 0, 0))
                isClickable = true
                setOnClickListener { collapsePanel() }
                addView(
                    panel,
                    FrameLayout.LayoutParams(panelWidth, panelHeight).apply {
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

    @SuppressLint("ClickableViewAccessibility")
    private fun installPanelDrag(
        dragTarget: View,
        panel: View,
        windowParams: WindowManager.LayoutParams,
        panelWidth: Int,
        panelHeight: Int,
    ) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        dragTarget.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    if (panelPinnedMode) {
                        startX = windowParams.x
                        startY = windowParams.y
                    } else {
                        val lp = panel.layoutParams as FrameLayout.LayoutParams
                        startX = lp.leftMargin
                        startY = lp.topMargin
                    }
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > dp(3) || abs(dy) > dp(3)) moved = true
                    val screen = screenSize()
                    val nextX = (startX + dx.toInt()).coerceIn(dp(8), max(dp(8), screen.x - panelWidth - dp(8)))
                    val nextY = (startY + dy.toInt()).coerceIn(dp(16), max(dp(16), screen.y - panelHeight - dp(16)))
                    if (panelPinnedMode) {
                        windowParams.x = nextX
                        windowParams.y = nextY
                        panelView?.let { runCatching { windowManager.updateViewLayout(it, windowParams) } }
                    } else {
                        val lp = panel.layoutParams as FrameLayout.LayoutParams
                        lp.leftMargin = nextX
                        lp.topMargin = nextY
                        panel.layoutParams = lp
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val currentX: Int
                    val currentY: Int
                    if (panelPinnedMode) {
                        currentX = windowParams.x
                        currentY = windowParams.y
                    } else {
                        val lp = panel.layoutParams as FrameLayout.LayoutParams
                        currentX = lp.leftMargin
                        currentY = lp.topMargin
                    }
                    if (moved) overlayPreferences.savePanelPosition(currentX, currentY)
                    true
                }
                else -> false
            }
        }
    }

    private fun renderEntries() {
        val container = listContainer ?: return
        val palette = themeProvider.palette()
        container.removeAllViews()
        val items = store.entries()
        if (items.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.empty_history_hint)
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(palette.secondaryText)
                setPadding(dp(10), dp(28), dp(10), dp(28))
            })
            return
        }
        items.forEach { container.addView(createClipRow(it)) }
    }

    private fun createClipRow(entry: ClipEntry): View {
        val palette = themeProvider.palette()
        val alpha = (255f * overlayPreferences.panelAlphaPercent() / 100f).toInt().coerceIn(0, 255)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(8), dp(4), dp(8))
            background = roundedBackground(withAlpha(palette.rowBackground, min(250, alpha + 18)), palette.rowCornerRadiusDp)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), 0, dp(4), 0)
            setOnClickListener { pasteEntry(entry) }
        }
        body.addView(TextView(this).apply {
            text = entry.text
            maxLines = 3
            textSize = 14.5f
            setTextColor(palette.primaryText)
        })
        body.addView(TextView(this).apply {
            text = entry.category
            textSize = 11.5f
            setTextColor(palette.secondaryText)
            setPadding(0, dp(4), 0, 0)
            setOnClickListener { cycleCategory(entry) }
        })
        row.addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(
            iconButton(
                R.drawable.ic_pin,
                if (entry.pinned) "取消置顶" else "置顶",
                if (entry.pinned) Color.rgb(55, 100, 245) else palette.secondaryText,
            ) {
                store.togglePinned(entry.id)
                renderEntries()
            },
            LinearLayout.LayoutParams(dp(38), dp(38)),
        )
        row.addView(
            iconButton(R.drawable.ic_delete, "删除", palette.secondaryText) {
                store.delete(entry.id)
                renderEntries()
            },
            LinearLayout.LayoutParams(dp(38), dp(38)),
        )

        return row.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, dp(3), 0, dp(3)) }
        }
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
            Toast.makeText(this, "已复制；启用一键粘贴后可直接粘贴", Toast.LENGTH_SHORT).show()
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
        panelSurfaceView = null
        listContainer = null
        showBubble()
        handler.postDelayed({ expandPanel() }, 40L)
    }

    private fun passiveIcon(drawableRes: Int, description: String): ImageView = ImageView(this).apply {
        setImageResource(drawableRes)
        imageTintList = ColorStateList.valueOf(themeProvider.palette().secondaryText)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        contentDescription = description
    }

    private fun iconButton(drawableRes: Int, description: String, tint: Int, action: () -> Unit): ImageView = ImageView(this).apply {
        setImageResource(drawableRes)
        imageTintList = ColorStateList.valueOf(tint)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = roundedBackground(withAlpha(themeProvider.palette().rowBackground, 170), 12f)
        contentDescription = description
        setOnClickListener { action() }
    }

    private fun collapsePanel() {
        panelView?.let { runCatching { windowManager.removeView(it) } }
        panelView = null
        panelSurfaceView = null
        listContainer = null
        showBubble()
    }

    private fun createNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "FloatClip 悬浮服务", NotificationManager.IMPORTANCE_LOW),
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_copy_paste)
            .setContentTitle("FloatClip 正在运行")
            .setContentText("点击悬浮球打开剪贴板")
            .setOngoing(true)
            .build()
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    private fun circleBackground(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(1), Color.argb(32, 0, 0, 0))
    }

    private fun roundedBackground(color: Int, radiusDp: Float, border: Boolean = false): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * resources.displayMetrics.density
        if (border) setStroke(dp(1), Color.argb(34, 0, 0, 0))
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
