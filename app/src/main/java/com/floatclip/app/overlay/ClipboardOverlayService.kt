package com.floatclip.app.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.animation.PathInterpolator
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
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
import com.floatclip.app.overlay.ui.BorderDragFrameLayout
import com.floatclip.app.overlay.ui.BubbleMotionController
import com.floatclip.app.overlay.ui.SwipeRevealRow
import com.floatclip.app.theme.AdaptiveOverlayThemeProvider
import com.floatclip.app.theme.OverlayPalette
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
    private lateinit var bubbleMotion: BubbleMotionController
    private val handler = Handler(Looper.getMainLooper())

    private var bubbleView: View? = null
    private var panelView: BorderDragFrameLayout? = null
    private var panelSurfaceView: View? = null
    private var panelScrimView: View? = null
    private var panelScrimParams: WindowManager.LayoutParams? = null
    private var panelPinButton: ImageView? = null
    private var listContainer: LinearLayout? = null
    private var categoryBar: LinearLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelPinnedMode = false
    private var bubbleMotionAnimator: ValueAnimator? = null
    private var panelCollapsing = false
    private var clipboardCaptureGeneration = 0
    private var activeCategory = ALL_CATEGORY
    private var lastCapturedClipboard: String? = null

    private val edgeHideRunnable = Runnable { hideBubbleAtEdge() }
    private val clipboardPollRunnable = object : Runnable {
        override fun run() {
            if (panelView == null) return
            captureCurrentClipboard(showFeedback = false)
            handler.postDelayed(this, CLIPBOARD_POLL_MS)
        }
    }
    private val refreshAppearanceRunnable = Runnable { rebuildVisibleOverlay() }
    private val appearanceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_REFRESH_APPEARANCE) return
            handler.removeCallbacks(refreshAppearanceRunnable)
            handler.postDelayed(refreshAppearanceRunnable, 80L)
        }
    }
    private var lastSystemNight = false
    private val systemAppearanceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_CONFIGURATION_CHANGED && intent?.action != Intent.ACTION_SCREEN_ON) return
            refreshSystemThemeIfNeeded()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag") // registration requires INTERNAL_BROADCAST_PERMISSION (signature)
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        store = ClipStore(this)
        clipboard = ClipboardRepository(this)
        categoryStore = CategoryStore(this)
        overlayPreferences = OverlayPreferences(this)
        IntegrationRegistry.initialize(this)
        themeProvider = AdaptiveOverlayThemeProvider(this, IntegrationRegistry.originOsSystemBridge)
        lastSystemNight = isSystemNight()
        bubbleMotion = BubbleMotionController(
            this,
            windowManager,
            ::screenSize,
            { overlayPreferences.bubbleMotionSensitivityPercent() },
        ) { y, onRight ->
            overlayPreferences.saveBubble(y, onRight)
            bubbleView?.animate()?.scaleX(1.018f)?.scaleY(1.018f)?.setDuration(70L)?.withEndAction {
                bubbleView?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(110L)?.start()
                scheduleEdgeHide()
            }?.start()
        }
        registerReceiver(
            appearanceReceiver,
            IntentFilter(ACTION_REFRESH_APPEARANCE),
            INTERNAL_BROADCAST_PERMISSION,
            null,
        )
        registerReceiver(
            systemAppearanceReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_CONFIGURATION_CHANGED)
                addAction(Intent.ACTION_SCREEN_ON)
            },
        )
        overlayPreferences.saveOverlayEnabled(true)
        startForeground(NOTIFICATION_ID, createNotification())
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH_APPEARANCE) {
            handler.removeCallbacks(refreshAppearanceRunnable)
            handler.postDelayed(refreshAppearanceRunnable, 60L)
        } else if (bubbleView == null && panelView == null) {
            showBubble()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshSystemThemeIfNeeded(force = true)
    }

    override fun onDestroy() {
        clipboardCaptureGeneration++
        handler.removeCallbacksAndMessages(null)
        bubbleMotion.cancel()
        bubbleMotionAnimator?.cancel()
        bubbleMotionAnimator = null
        bubbleView?.animate()?.cancel()
        panelSurfaceView?.animate()?.cancel()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        panelView?.let { runCatching { windowManager.removeView(it) } }
        panelScrimView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        panelView = null
        panelSurfaceView = null
        panelScrimView = null
        panelScrimParams = null
        panelPinButton = null
        runCatching { unregisterReceiver(appearanceReceiver) }
        runCatching { unregisterReceiver(systemAppearanceReceiver) }
        if (OverlayKeepAliveScheduler.shouldRun(this)) OverlayKeepAliveScheduler.schedule(this)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (OverlayKeepAliveScheduler.shouldRun(this)) OverlayKeepAliveScheduler.schedule(this)
        super.onTaskRemoved(rootIntent)
    }

    private fun isSystemNight(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun refreshSystemThemeIfNeeded(force: Boolean = false) {
        if (overlayPreferences.themeMode() != com.floatclip.app.prefs.OverlayThemeMode.SYSTEM) return
        val current = isSystemNight()
        if (!force && current == lastSystemNight) return
        lastSystemNight = current
        handler.removeCallbacks(refreshAppearanceRunnable)
        handler.postDelayed(refreshAppearanceRunnable, 32L)
    }

    private fun rebuildVisibleOverlay() {
        val reopenPanel = panelView != null
        clipboardCaptureGeneration++
        handler.removeCallbacks(edgeHideRunnable)
        handler.removeCallbacks(clipboardPollRunnable)
        bubbleMotion.cancel()
        bubbleMotionAnimator?.cancel()
        bubbleMotionAnimator = null
        bubbleView?.animate()?.cancel()
        panelSurfaceView?.animate()?.cancel()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        panelView?.let { runCatching { windowManager.removeView(it) } }
        panelScrimView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        panelView = null
        panelSurfaceView = null
        panelScrimView = null
        panelScrimParams = null
        panelPinButton = null
        listContainer = null
        panelCollapsing = false
        showBubble()
        if (reopenPanel) handler.postDelayed({ expandPanel() }, 20L)
    }

    private fun showBubble(clearPanelWindows: Boolean = true) {
        if (bubbleView != null) return
        if (clearPanelWindows) {
            handler.removeCallbacks(clipboardPollRunnable)
            panelView?.let { runCatching { windowManager.removeView(it) } }
            panelScrimView?.let { runCatching { windowManager.removeView(it) } }
            panelView = null
            panelSurfaceView = null
            panelScrimView = null
            panelScrimParams = null
            panelPinButton = null
            listContainer = null
            categoryBar = null
            panelCollapsing = false
        }
        handler.removeCallbacks(edgeHideRunnable)
        bubbleMotion.cancel()
        bubbleMotionAnimator?.cancel()
        bubbleMotionAnimator = null

        val palette = themeProvider.palette()
        val screen = screenSize()
        val size = dp(overlayPreferences.bubbleSizeDp())
        val onRight = overlayPreferences.bubbleOnRight()
        val initialX = if (onRight) max(0, screen.x - size) else 0
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = initialX
            y = overlayPreferences.bubbleY(dp(220)).coerceIn(0, max(0, screen.y - size))
        }
        bubbleParams = params

        val bubbleAlpha = percentAlpha(overlayPreferences.bubbleAlphaPercent())
        val bubble = ImageView(this).apply {
            setImageResource(R.drawable.ic_copy_paste)
            imageTintList = ColorStateList.valueOf(if (palette.isDark) Color.WHITE else Color.rgb(24, 26, 30))
            setPadding(dp(13), dp(13), dp(13), dp(13))
            background = circleBackground(withAlpha(palette.bubbleBackground, bubbleAlpha), palette)
            elevation = dp(8).toFloat()
            contentDescription = "打开 FloatClip"
            setOnClickListener { expandPanel() }
            alpha = 0f
            scaleX = 0.88f
            scaleY = 0.88f
        }
        installBubbleTouch(bubble, params)
        bubbleView = bubble
        windowManager.addView(bubble, params)
        bubble.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(BUBBLE_APPEAR_MS)
            .setInterpolator(PathInterpolator(0.2f, 0.8f, 0.2f, 1f))
            .withEndAction { scheduleEdgeHide() }
            .start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installBubbleTouch(view: View, params: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var velocityTracker: VelocityTracker? = null

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    handler.removeCallbacks(edgeHideRunnable)
                    bubbleMotion.cancel()
                    bubbleMotionAnimator?.cancel()
                    bubbleMotionAnimator = null
                    view.animate().cancel()
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().also { addRawMovement(it, event) }
                    view.animate()
                        .scaleX(0.965f)
                        .scaleY(0.965f)
                        .setDuration(85L)
                        .setInterpolator(PathInterpolator(0.2f, 0.8f, 0.2f, 1f))
                        .start()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    addRawMovement(velocityTracker, event)
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) moved = true
                    val screen = screenSize()
                    params.x = (startX + dx.toInt()).coerceIn(-params.width / 2, screen.x - params.width / 2)
                    params.y = (startY + dy.toInt()).coerceIn(0, max(0, screen.y - params.height))
                    runCatching { windowManager.updateViewLayout(view, params) }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    addRawMovement(velocityTracker, event)
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        val screen = screenSize()
                        overlayPreferences.saveBubble(
                            params.y.coerceIn(0, max(0, screen.y - params.height)),
                            params.x + params.width / 2 >= screen.x / 2,
                        )
                    }
                    view.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(120L)
                        .setInterpolator(PathInterpolator(0.2f, 0.8f, 0.2f, 1f))
                        .start()
                    if (moved && event.actionMasked == MotionEvent.ACTION_UP) {
                        velocityTracker?.computeCurrentVelocity(1000, dp(7200).toFloat())
                        val vx = velocityTracker?.xVelocity ?: 0f
                        val vy = velocityTracker?.yVelocity ?: 0f
                        bubbleMotion.release(view, params, vx, vy)
                    } else if (!moved && event.actionMasked == MotionEvent.ACTION_UP) {
                        view.performClick()
                    } else {
                        bubbleMotion.release(view, params, 0f, 0f)
                    }
                    velocityTracker?.recycle()
                    velocityTracker = null
                    true
                }

                else -> false
            }
        }
    }

    private fun addRawMovement(tracker: VelocityTracker?, event: MotionEvent) {
        if (tracker == null) return
        val copy = MotionEvent.obtain(event)
        copy.setLocation(event.rawX, event.rawY)
        tracker.addMovement(copy)
        copy.recycle()
    }

    private fun scheduleEdgeHide() {
        handler.removeCallbacks(edgeHideRunnable)
        if (overlayPreferences.edgeHidePercent() <= 0 || bubbleView == null) return
        handler.postDelayed(edgeHideRunnable, EDGE_HIDE_DELAY_MS)
    }

    private fun hideBubbleAtEdge() {
        val view = bubbleView ?: return
        val params = bubbleParams ?: return
        val screen = screenSize()
        val hidePercent = overlayPreferences.edgeHidePercent()
        if (hidePercent <= 0) return
        val hiddenPx = (params.width * hidePercent / 100f).toInt().coerceIn(0, params.width / 2 + dp(3))
        val onRight = overlayPreferences.bubbleOnRight()
        val targetX = if (onRight) screen.x - params.width + hiddenPx else -hiddenPx
        val startX = params.x
        if (startX == targetX) return

        bubbleMotionAnimator?.cancel()
        bubbleMotionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = EDGE_HIDE_ANIMATION_MS
            interpolator = PathInterpolator(0.25f, 0.8f, 0.25f, 1f)
            addUpdateListener { animator ->
                params.x = lerp(startX, targetX, animator.animatedValue as Float)
                runCatching { windowManager.updateViewLayout(view, params) }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    bubbleMotionAnimator = null
                }
            })
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun expandPanel() {
        val bubble = bubbleView ?: return
        bubbleParams?.let { current ->
            val screen = screenSize()
            overlayPreferences.saveBubble(
                current.y.coerceIn(0, max(0, screen.y - current.height)),
                current.x + current.width / 2 >= screen.x / 2,
            )
        }
        bubbleMotion.cancel()
        bubbleMotionAnimator?.cancel()
        bubbleMotionAnimator = null
        handler.removeCallbacks(edgeHideRunnable)
        bubble.animate().cancel()

        val palette = themeProvider.palette()
        val screen = screenSize()
        val panelWidth = min(dp(overlayPreferences.panelWidthDp()), screen.x - dp(20))
        val panelHeight = min(dp(overlayPreferences.panelHeightDp()), screen.y - dp(48))
        val defaultX = if (overlayPreferences.bubbleOnRight()) {
            max(dp(10), screen.x - panelWidth - dp(10))
        } else {
            dp(10)
        }
        val defaultY = overlayPreferences.bubbleY(dp(220))
            .coerceIn(dp(24), max(dp(24), screen.y - panelHeight - dp(24)))
        val panelX = overlayPreferences.panelX(defaultX).coerceIn(dp(8), max(dp(8), screen.x - panelWidth - dp(8)))
        val panelY = overlayPreferences.panelY(defaultY).coerceIn(dp(16), max(dp(16), screen.y - panelHeight - dp(16)))

        val params = WindowManager.LayoutParams(
            panelWidth,
            panelHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = panelX
            y = panelY
        }
        panelParams = params

        val panelAlpha = percentAlpha(overlayPreferences.panelAlphaPercent())
        val panel = BorderDragFrameLayout(this).apply {
            isClickable = true
            background = roundedBackground(
                withAlpha(palette.panelBackground, panelAlpha),
                palette.panelCornerRadiusDp,
                if (palette.isDark) Color.argb(92, 255, 255, 255) else Color.argb(46, 0, 0, 0),
            )
            elevation = dp(palette.elevationDp).toFloat()
            setPadding(dp(8), dp(8), dp(8), dp(8))
            alpha = 0f
            scaleX = 0.955f
            scaleY = 0.955f
            translationX = if (overlayPreferences.bubbleOnRight()) dp(10).toFloat() else -dp(10).toFloat()
        }
        panelView = panel
        panelSurfaceView = panel
        panelCollapsing = false

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
        header.addView(passiveIcon(R.drawable.ic_drag, "边框与标题空白处可拖动"), LinearLayout.LayoutParams(dp(34), dp(40)))
        header.addView(TextView(this).apply {
            text = "剪贴板"
            textSize = 17f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(palette.primaryText)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(40), 1f))

        val pinButton = iconButton(
            R.drawable.ic_pin,
            if (panelPinnedMode) "退出固定模式" else "固定模式",
            if (panelPinnedMode) ACCENT_BLUE else palette.secondaryText,
        ) { togglePanelPinnedMode() }
        panelPinButton = pinButton
        header.addView(pinButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        content.addView(header)

        val hint = TextView(this).apply {
            text = if (PasteAccessibilityService.isConnected()) {
                "点击粘贴 · 双击置顶 · 左滑操作 · 长按更多 · 边框拖动"
            } else {
                "点击复制 · 双击置顶 · 左滑操作 · 长按更多 · 边框拖动"
            }
            textSize = 11.2f
            setTextColor(palette.secondaryText)
            setPadding(dp(6), 0, dp(6), dp(5))
        }
        content.addView(hint)

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

        val categoryScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(dp(2), dp(5), dp(2), 0)
        }
        categoryBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        categoryScroll.addView(categoryBar, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        content.addView(categoryScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(45)))

        installPanelDrag(panel, params, panelWidth, panelHeight)

        val scrim = View(this).apply {
            // Touch catcher only. A visual dim layer cannot cover OriginOS system bars uniformly,
            // which created the apparent brightness jump around status/navigation areas.
            setBackgroundColor(Color.TRANSPARENT)
            alpha = 1f
            isClickable = true
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP && !panelPinnedMode) collapsePanel()
                true
            }
        }
        val scrimParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                if (panelPinnedMode) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.START or Gravity.TOP }
        panelScrimView = scrim
        panelScrimParams = scrimParams
        windowManager.addView(scrim, scrimParams)
        windowManager.addView(panel, params)

        renderCategoryBar()
        renderEntries()

        panel.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationX(0f)
            .setDuration(PANEL_APPEAR_MS)
            .setInterpolator(PathInterpolator(0.16f, 0.95f, 0.22f, 1f))
            .start()
        bubble.animate()
            .alpha(0f)
            .scaleX(0.82f)
            .scaleY(0.82f)
            .setDuration(180L)
            .setInterpolator(PathInterpolator(0.4f, 0f, 0.8f, 0.2f))
            .withEndAction { runCatching { windowManager.removeView(bubble) } }
            .start()
        bubbleView = null
        handler.removeCallbacks(clipboardPollRunnable)
        handler.postDelayed(clipboardPollRunnable, 120L)
    }

    private fun installPanelDrag(
        panel: BorderDragFrameLayout,
        windowParams: WindowManager.LayoutParams,
        panelWidth: Int,
        panelHeight: Int,
    ) {
        panel.borderDragPx = dp(14)
        panel.topDragHeightPx = dp(50)
        panel.topActionReservePx = dp(50)
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        panel.listener = object : BorderDragFrameLayout.DragListener {
            override fun onDragStart(rawX: Float, rawY: Float) {
                downX = rawX
                downY = rawY
                startX = windowParams.x
                startY = windowParams.y
                moved = false
                panel.animate().cancel()
                panel.animate().scaleX(0.995f).scaleY(0.995f).setDuration(70L).start()
            }

            override fun onDragMove(rawX: Float, rawY: Float) {
                val dx = rawX - downX
                val dy = rawY - downY
                if (abs(dx) > dp(2) || abs(dy) > dp(2)) moved = true
                val screen = screenSize()
                windowParams.x = (startX + dx.toInt()).coerceIn(dp(8), max(dp(8), screen.x - panelWidth - dp(8)))
                windowParams.y = (startY + dy.toInt()).coerceIn(dp(16), max(dp(16), screen.y - panelHeight - dp(16)))
                runCatching { windowManager.updateViewLayout(panel, windowParams) }
            }

            override fun onDragEnd(rawX: Float, rawY: Float, cancelled: Boolean) {
                panel.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(135L)
                    .setInterpolator(PathInterpolator(0.2f, 0.8f, 0.2f, 1f))
                    .start()
                if (moved && !cancelled) overlayPreferences.savePanelPosition(windowParams.x, windowParams.y)
            }
        }
    }

    private fun renderEntries() {
        val container = listContainer ?: return
        val palette = themeProvider.palette()
        container.removeAllViews()
        val allItems = store.entries()
        val items = if (activeCategory == ALL_CATEGORY) {
            allItems
        } else {
            allItems.filter { it.category == activeCategory }
        }
        if (items.isEmpty()) {
            container.addView(TextView(this).apply {
                text = if (allItems.isEmpty()) getString(R.string.empty_history_hint) else "这个分类还没有内容"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(palette.secondaryText)
                setPadding(dp(10), dp(28), dp(10), dp(28))
            })
            return
        }
        items.forEach { container.addView(createClipRow(it)) }
    }

    private fun renderCategoryBar() {
        val bar = categoryBar ?: return
        val palette = themeProvider.palette()
        bar.removeAllViews()
        val categories = listOf(ALL_CATEGORY) + categoryStore.categories()
        if (activeCategory !in categories) activeCategory = ALL_CATEGORY
        categories.distinct().forEachIndexed { index, category ->
            val selected = category == activeCategory
            bar.addView(
                TextView(this).apply {
                    text = category
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding(dp(13), 0, dp(13), 0)
                    setTextColor(if (selected) if (palette.isDark) Color.rgb(20, 27, 43) else Color.WHITE else palette.secondaryText)
                    background = roundedBackground(
                        if (selected) ACCENT_BLUE else withAlpha(palette.rowBackground, if (palette.isDark) 175 else 205),
                        12f,
                    )
                    setOnClickListener {
                        if (activeCategory != category) {
                            activeCategory = category
                            renderCategoryBar()
                            renderEntries()
                        }
                    }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
                    if (index > 0) marginStart = dp(6)
                },
            )
        }
    }

    private fun createClipRow(entry: ClipEntry): View {
        val palette = themeProvider.palette()
        val alpha = percentAlpha(overlayPreferences.panelAlphaPercent())
        val wrapper = SwipeRevealRow(this)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(
            revealAction(if (entry.pinned) "取消" else "置顶", ACCENT_BLUE) {
                store.togglePinned(entry.id)
                renderEntries()
            },
            LinearLayout.LayoutParams(dp(66), ViewGroup.LayoutParams.MATCH_PARENT),
        )
        actions.addView(
            revealAction("删除", Color.rgb(218, 78, 78)) {
                store.delete(entry.id)
                renderEntries()
            },
            LinearLayout.LayoutParams(dp(66), ViewGroup.LayoutParams.MATCH_PARENT),
        )

        val pinnedColor = if (entry.pinned) {
            blendColor(palette.rowBackground, if (palette.isDark) Color.WHITE else ACCENT_BLUE, if (palette.isDark) 0.08f else 0.10f)
        } else {
            palette.rowBackground
        }
        val front = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(11), dp(10))
            background = roundedBackground(
                withAlpha(pinnedColor, min(252, alpha + 18)),
                palette.rowCornerRadiusDp,
            )
        }
        front.addView(TextView(this).apply {
            text = entry.text
            maxLines = 3
            textSize = 14.5f
            setTextColor(palette.primaryText)
        })
        front.addView(TextView(this).apply {
            text = buildString {
                append(entry.category)
                if (entry.pinned) append(" · 已置顶")
            }
            textSize = 11.3f
            setTextColor(if (entry.pinned) ACCENT_BLUE else palette.secondaryText)
            setPadding(0, dp(5), 0, 0)
        })

        wrapper.bind(front, actions, dp(132))
        wrapper.onSingleTap = { pasteEntry(entry) }
        wrapper.onDoubleTap = {
            store.togglePinned(entry.id)
            renderEntries()
        }
        wrapper.onLongPress = { showEntryMenu(wrapper, entry) }
        return wrapper.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, dp(3), 0, dp(3)) }
        }
    }

    private fun revealAction(label: String, color: Int, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = roundedBackground(withAlpha(color, 225), 13f)
        setOnClickListener { action() }
    }

    private fun showEntryMenu(anchor: View, entry: ClipEntry) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_COPY, 0, "复制")
        popup.menu.add(0, MENU_PIN, 1, if (entry.pinned) "取消置顶" else "置顶")
        val categorySubmenu = popup.menu.addSubMenu("分类")
        val categoryIds = mutableMapOf<Int, String>()
        categoryStore.categories().forEachIndexed { index, category ->
            val id = MENU_CATEGORY_BASE + index
            categoryIds[id] = category
            categorySubmenu.add(1, id, index, if (category == entry.category) "✓ $category" else category)
        }
        popup.menu.add(0, MENU_DELETE, 3, "删除")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_COPY -> {
                    clipboard.writeText(entry.text)
                    Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
                    true
                }
                MENU_PIN -> {
                    store.togglePinned(entry.id)
                    renderEntries()
                    true
                }
                MENU_DELETE -> {
                    store.delete(entry.id)
                    renderEntries()
                    true
                }
                else -> categoryIds[item.itemId]?.let { category ->
                    store.updateCategory(entry.id, category)
                    activeCategory = if (activeCategory == ALL_CATEGORY) ALL_CATEGORY else category
                    renderCategoryBar()
                    renderEntries()
                    true
                } ?: false
            }
        }
        popup.show()
    }

    private fun pasteEntry(entry: ClipEntry) {
        val pasted = AccessibilityPasteBridge.pasteText(entry.text)
        if (!pasted) {
            clipboard.writeText(entry.text)
            Toast.makeText(this, "已复制；启用一键粘贴后可直接粘贴", Toast.LENGTH_SHORT).show()
        }
        if (!panelPinnedMode) collapsePanel()
    }

    private fun captureCurrentClipboard(showFeedback: Boolean) {
        val panel = panelView ?: return
        val generation = ++clipboardCaptureGeneration
        val text = runCatching { clipboard.readCurrentText() }.getOrNull()
        if (generation != clipboardCaptureGeneration || panelView !== panel) return
        if (!text.isNullOrBlank() && text != lastCapturedClipboard) {
            lastCapturedClipboard = text
            if (store.addText(text) != null) {
                renderCategoryBar()
                renderEntries()
                if (showFeedback) Toast.makeText(this, "已加入 FloatClip", Toast.LENGTH_SHORT).show()
            }
        } else if (showFeedback && text.isNullOrBlank()) {
            Toast.makeText(this, "暂未读取到新的剪贴板内容", Toast.LENGTH_SHORT).show()
        }
    }

    private fun togglePanelPinnedMode() {
        val scrim = panelScrimView ?: return
        val scrimParams = panelScrimParams ?: return
        val button = panelPinButton
        val palette = themeProvider.palette()
        panelPinnedMode = !panelPinnedMode

        scrim.animate().cancel()
        if (panelPinnedMode) {
            scrimParams.flags = scrimParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            scrimParams.flags = scrimParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        runCatching { windowManager.updateViewLayout(scrim, scrimParams) }

        button?.apply {
            imageTintList = ColorStateList.valueOf(if (panelPinnedMode) ACCENT_BLUE else palette.secondaryText)
            contentDescription = if (panelPinnedMode) "退出固定模式" else "固定模式"
        }
    }

    private fun passiveIcon(drawableRes: Int, description: String): ImageView = ImageView(this).apply {
        setImageResource(drawableRes)
        imageTintList = ColorStateList.valueOf(themeProvider.palette().secondaryText)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        contentDescription = description
    }

    private fun iconButton(drawableRes: Int, description: String, tint: Int, action: () -> Unit): ImageView = ImageView(this).apply {
        val palette = themeProvider.palette()
        setImageResource(drawableRes)
        imageTintList = ColorStateList.valueOf(tint)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = roundedBackground(withAlpha(palette.rowBackground, if (palette.isDark) 210 else 190), 12f)
        contentDescription = description
        setOnClickListener { action() }
    }

    private fun collapsePanel(animated: Boolean = true) {
        if (panelCollapsing) return
        val panel = panelView ?: return
        val scrim = panelScrimView
        panelCollapsing = true
        clipboardCaptureGeneration++
        handler.removeCallbacks(clipboardPollRunnable)

        val finish = {
            runCatching { windowManager.removeView(panel) }
            scrim?.let { runCatching { windowManager.removeView(it) } }
            if (panelView === panel) panelView = null
            panelSurfaceView = null
            panelScrimView = null
            panelScrimParams = null
            panelPinButton = null
            listContainer = null
            categoryBar = null
            panelCollapsing = false
            if (bubbleView == null) showBubble(clearPanelWindows = false)
        }
        if (!animated) {
            finish()
            return
        }

        handler.postDelayed({
            if (panelCollapsing && bubbleView == null) showBubble(clearPanelWindows = false)
        }, 130L)
        scrim?.animate()?.cancel()
        panel.animate().cancel()
        panel.animate()
            .alpha(0f)
            .scaleX(0.985f)
            .scaleY(0.985f)
            .translationX(if (overlayPreferences.bubbleOnRight()) dp(4).toFloat() else -dp(4).toFloat())
            .setDuration(PANEL_DISMISS_MS)
            .setInterpolator(PathInterpolator(0.22f, 0f, 0.2f, 1f))
            .withEndAction(finish)
            .start()
    }

    private fun createNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "FloatClip 后台运行",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "保持悬浮剪贴板稳定运行"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        manager.createNotificationChannel(channel)
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_copy_paste)
            .setContentTitle("FloatClip")
            .setContentText("")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setOngoing(true)
            .build()
    }

    private fun percentAlpha(percent: Int): Int = (255f * percent.coerceIn(0, 100) / 100f).toInt().coerceIn(0, 255)

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    private fun circleBackground(color: Int, palette: OverlayPalette): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(1), if (palette.isDark) Color.argb(74, 255, 255, 255) else Color.argb(38, 0, 0, 0))
    }

    private fun roundedBackground(color: Int, radiusDp: Float, borderColor: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * resources.displayMetrics.density
        if (borderColor != null) setStroke(dp(1), borderColor)
    }

    private fun lerp(from: Int, to: Int, fraction: Float): Int = (from + (to - from) * fraction).toInt()

    private fun blendColor(base: Int, overlay: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(base) + (Color.red(overlay) - Color.red(base)) * f).toInt(),
            (Color.green(base) + (Color.green(overlay) - Color.green(base)) * f).toInt(),
            (Color.blue(base) + (Color.blue(overlay) - Color.blue(base)) * f).toInt(),
        )
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Point = Point().also { windowManager.defaultDisplay.getSize(it) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_REFRESH_APPEARANCE = "com.floatclip.app.action.REFRESH_APPEARANCE"
        const val NOTIFICATION_CHANNEL_ID = "floatclip_overlay_quiet_v2"
        const val INTERNAL_BROADCAST_PERMISSION = "com.floatclip.app.permission.INTERNAL"
        private const val NOTIFICATION_ID = 2001
        private const val CLIPBOARD_POLL_MS = 650L
        private const val BUBBLE_APPEAR_MS = 240L
        private const val EDGE_HIDE_DELAY_MS = 900L
        private const val EDGE_HIDE_ANIMATION_MS = 300L
        private const val PANEL_APPEAR_MS = 300L
        private const val PANEL_DISMISS_MS = 380L
        private const val ALL_CATEGORY = "全部"
        private const val MENU_COPY = 1
        private const val MENU_PIN = 2
        private const val MENU_DELETE = 3
        private const val MENU_CATEGORY_BASE = 1000
        private val ACCENT_BLUE = Color.rgb(92, 132, 247)
    }
}
