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
import android.os.Binder
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.floatclip.app.R
import com.floatclip.app.accessibility.PasteAccessibilityService
import com.floatclip.app.clipboard.ClipStore
import com.floatclip.app.clipboard.ClipboardSources
import com.floatclip.app.clipboard.ClipboardRepository
import com.floatclip.app.integration.AccessibilityPasteBridge
import com.floatclip.app.integration.IntegrationRegistry
import com.floatclip.app.model.ClipEntry
import com.floatclip.probe.FloatClipAccent
import com.floatclip.app.prefs.CategoryStore
import com.floatclip.app.prefs.CategoryIcons
import com.floatclip.app.prefs.OverlayPreferences
import com.floatclip.app.overlay.ui.BorderDragFrameLayout
import com.floatclip.app.overlay.ui.BubbleMotionController
import com.floatclip.app.overlay.ui.EntrySwipeActions
import com.floatclip.app.overlay.ui.EntryGestureRow
import com.floatclip.app.overlay.ui.EntryLockAction
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
    private var entryActionLayer: View? = null
    private var listContainer: LinearLayout? = null
    private var categoryBar: LinearLayout? = null
    private var sourceBar: LinearLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelPinnedMode = false
    private var bubbleMotionAnimator: ValueAnimator? = null
    private var panelTransitionAnimator: ValueAnimator? = null
    private var foregroundStarted = false
    private var accessibilityHosted = false
    private val sharedHistoryChanged=object: BroadcastReceiver(){override fun onReceive(context: Context?,intent: Intent?){if(panelOpen&&!panelCollapsing){renderSourceBar();renderCategoryBar();renderEntries()}}}
    private val localBinder = LocalBinder()
    private var panelOpen = false
    private var panelCollapsing = false
    private var clipboardCaptureGeneration = 0
    private var activeCategory = ALL_CATEGORY
    private var activeSource = ""
    private var lastCapturedClipboard: String? = null
    private var captureFocusPending=false
    private val releaseCaptureFocus=Runnable { releaseClipboardFocus() }

    private val edgeHideRunnable = Runnable { hideBubbleAtEdge() }
    private val clipboardPollRunnable = object : Runnable {
        override fun run() {
            if (!panelOpen || panelCollapsing) return
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

    @SuppressLint("UnspecifiedRegisterReceiverFlag") // registration requires a package-scoped signature permission
    override fun onCreate() {
        super.onCreate()
        val historyFilter=IntentFilter(com.floatclip.app.sync.ClipboardSyncService.HISTORY_CHANGED)
        if(android.os.Build.VERSION.SDK_INT>=33)registerReceiver(sharedHistoryChanged,historyFilter,"$packageName.permission.INTERNAL",null,Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(sharedHistoryChanged,historyFilter,"$packageName.permission.INTERNAL",null)
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
            "$packageName.permission.INTERNAL",
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
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        if (intent?.action == ACTION_REFRESH_APPEARANCE) {
            handler.removeCallbacks(refreshAppearanceRunnable)
            handler.postDelayed(refreshAppearanceRunnable, 60L)
        } else if (bubbleView == null && panelView == null) {
            showBubble()
        }
        return if (overlayPreferences.keepAliveEnabled()) START_STICKY else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = localBinder

    override fun onUnbind(intent: Intent?): Boolean {
        accessibilityHosted = false
        return super.onUnbind(intent)
    }

    inner class LocalBinder : Binder() {
        fun useAccessibilityHost() {
            accessibilityHosted = true
            if (foregroundStarted) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                foregroundStarted = false
                // Keep the service alive through the accessibility binding, but clear the started state.
                stopSelf()
            }
        }
    }

    private fun ensureForeground() {
        if (foregroundStarted) return
        startForeground(NOTIFICATION_ID, createNotification())
        foregroundStarted = true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshSystemThemeIfNeeded(force = true)
    }

    override fun onDestroy() {
        releaseClipboardFocus()
        clipboardCaptureGeneration++
        handler.removeCallbacksAndMessages(null)
        bubbleMotion.cancel()
        bubbleMotionAnimator?.cancel()
        bubbleMotionAnimator = null
        panelTransitionAnimator?.cancel()
        panelTransitionAnimator = null
        bubbleView?.animate()?.cancel()
        panelView?.animate()?.cancel()
        panelSurfaceView?.animate()?.cancel()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        panelView?.let { runCatching { windowManager.removeView(it) } }
        panelScrimView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        panelOpen = false
        panelView = null
        panelSurfaceView = null
        panelScrimView = null
        panelScrimParams = null
        panelPinButton = null
        entryActionLayer = null
        runCatching { unregisterReceiver(appearanceReceiver) }
        runCatching { unregisterReceiver(sharedHistoryChanged) }
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
        releaseClipboardFocus()
        val reopenPanel = panelOpen && !panelCollapsing
        clipboardCaptureGeneration++
        handler.removeCallbacks(edgeHideRunnable)
        handler.removeCallbacks(clipboardPollRunnable)
        bubbleMotion.cancel()
        bubbleMotionAnimator?.cancel()
        bubbleMotionAnimator = null
        panelTransitionAnimator?.cancel()
        panelTransitionAnimator = null
        bubbleView?.animate()?.cancel()
        panelView?.animate()?.cancel()
        panelSurfaceView?.animate()?.cancel()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        panelView?.let { runCatching { windowManager.removeView(it) } }
        panelScrimView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        panelOpen = false
        panelView = null
        panelSurfaceView = null
        panelScrimView = null
        panelScrimParams = null
        panelPinButton = null
        entryActionLayer = null
        listContainer = null
        panelCollapsing = false
        showBubble()
        if (foregroundStarted) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, createNotification())
        if (reopenPanel) handler.postDelayed({ expandPanel(false) }, 20L)
    }

    private fun showBubble(clearPanelWindows: Boolean = true, deferAppearance: Boolean = false) {
        if (bubbleView != null) return
        if (clearPanelWindows) {
            handler.removeCallbacks(clipboardPollRunnable)
            panelView?.let { runCatching { windowManager.removeView(it) } }
            panelScrimView?.let { runCatching { windowManager.removeView(it) } }
            panelOpen = false
            panelView = null
            panelSurfaceView = null
            panelScrimView = null
            panelScrimParams = null
            panelPinButton = null
            entryActionLayer = null
            listContainer = null
            categoryBar = null
            sourceBar = null
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
            windowAnimations = 0
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
        if (!deferAppearance) {
            bubble.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(overlayPreferences.transitionDurationMs().toLong())
                .setInterpolator(PathInterpolator(0.2f, 0.8f, 0.2f, 1f))
                .withEndAction { scheduleEdgeHide() }
                .start()
        }
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
                        .scaleX(if (ValueAnimator.areAnimatorsEnabled()) overlayPreferences.bubblePressScalePercent()/100f else 1f)
                        .scaleY(if (ValueAnimator.areAnimatorsEnabled()) overlayPreferences.bubblePressScalePercent()/100f else 1f)
                        .setDuration((overlayPreferences.transitionDurationMs()*0.35f).toLong().coerceIn(55,160))
                        .setInterpolator(PathInterpolator(0.2f, 0.8f, 0.2f, 1f))
                        .start()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    addRawMovement(velocityTracker, event)
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) moved = true
                    if (!moved) return@setOnTouchListener true
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
                        .setDuration((overlayPreferences.transitionDurationMs()*0.55f).toLong().coerceIn(80,240))
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
        if (overlayPreferences.edgeHidePercent() <= 0 || bubbleView == null || panelOpen || panelCollapsing) return
        handler.postDelayed(edgeHideRunnable, EDGE_HIDE_DELAY_MS)
    }

    private fun hideBubbleAtEdge() {
        if (panelOpen || panelCollapsing) return
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
    private fun expandPanel(captureOnOpen:Boolean=true) {
        if (panelOpen || panelCollapsing) return
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

        if (panelView != null) {
            showRetainedPanel(captureOnOpen)
            return
        }
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
            windowAnimations = 0
            gravity = Gravity.START or Gravity.TOP
            x = panelX
            y = panelY
            alpha = 0f
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        panelParams = params

        val panelAlpha = percentAlpha(overlayPreferences.panelAlphaPercent())
        val frost=overlayPreferences.frostStrengthPercent()/100f
        val frostedPanel=if(palette.isDark) blendColor(palette.panelBackground,Color.WHITE,0.07f*frost)
            else blendColor(palette.panelBackground,Color.WHITE,0.22f*frost)
        val borderAlpha=percentAlpha(overlayPreferences.borderAlphaPercent())
        val panel = BorderDragFrameLayout(this).apply {
            isClickable = true
            background = roundedBackground(
                withAlpha(frostedPanel, panelAlpha),
                palette.panelCornerRadiusDp,
                if(!overlayPreferences.borderEnabled()) null else if (palette.isDark) Color.argb(borderAlpha,255,255,255) else Color.argb(borderAlpha,25,40,63),
            )
            elevation = dp(palette.elevationDp).toFloat()
            windowFocusChanged={focused->
                if(focused&&captureFocusPending)handler.post {
                    if(captureFocusPending&&panelOpen&&!panelCollapsing)captureCurrentClipboard(false)
                    releaseClipboardFocus()
                }
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
            alpha = 1f
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
            if (panelPinnedMode) accentColor(palette) else palette.secondaryText,
        ) { togglePanelPinnedMode() }
        panelPinButton = pinButton
        header.addView(pinButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        content.addView(header)

        val sourceScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(dp(2), dp(2), dp(2), dp(5))
        }
        sourceBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sourceScroll.addView(sourceBar, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
        content.addView(sourceScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(47)))

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
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            windowAnimations = 0
            alpha = 0f
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        panelScrimView = scrim
        panelScrimParams = scrimParams
        windowManager.addView(scrim, scrimParams)
        windowManager.addView(panel, params)

        showRetainedPanel(captureOnOpen)
    }

    /** Keep all three WindowManager surfaces attached; open/close never reallocates them. */
    private fun showRetainedPanel(captureOnOpen:Boolean=true) {
        val panel = panelView ?: return
        val params = panelParams ?: return
        panelOpen = true
        panel.interactionBlocked = false
        panel.isEnabled = true
        panel.animate().cancel()
        val animations=ValueAnimator.areAnimatorsEnabled()
        val duration=overlayPreferences.transitionDurationMs().toLong()
        panel.alpha = if(animations) 0f else 1f
        panel.scaleX = if(animations) 0.92f else 1f
        panel.scaleY = if(animations) 0.92f else 1f
        panel.translationX = 0f
        renderSourceBar()
        renderCategoryBar()
        renderEntries()
        params.alpha = 1f
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        windowManager.updateViewLayout(panel, params)
        if(animations) {
            panel.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(duration)
                .setInterpolator(PathInterpolator(0.2f,0.8f,0.2f,1f))
                .start()
        }
        panelScrimView?.let { scrim ->
            panelScrimParams?.let {
                it.alpha = 1f
                it.flags = if (panelPinnedMode) it.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    else it.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                windowManager.updateViewLayout(scrim, it)
            }
        }
        bubbleView?.let { bubble ->
            bubble.animate().cancel()
            bubbleParams?.let {
                it.alpha = 1f
                it.flags = it.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                windowManager.updateViewLayout(bubble, it)
            }
            if(animations) {
                bubble.alpha=1f; bubble.scaleX=1f; bubble.scaleY=1f
                bubble.animate().alpha(0f).scaleX(0.76f).scaleY(0.76f)
                    .setDuration(duration)
                    .setInterpolator(PathInterpolator(0.2f,0.8f,0.2f,1f))
                    .withEndAction {
                        bubbleParams?.let {
                            it.alpha=0f
                            windowManager.updateViewLayout(bubble,it)
                        }
                    }.start()
            } else {
                bubble.alpha=1f; bubble.scaleX=1f; bubble.scaleY=1f
                bubbleParams?.let {
                    it.alpha=0f
                    windowManager.updateViewLayout(bubble,it)
                }
            }
        }
        if(captureOnOpen)beginClipboardFocus()
    }

    /** Only an explicit open takes a short focus lease; idle/background never steals focus. */
    private fun beginClipboardFocus() {
        val panel=panelView?:return
        val params=panelParams?:return
        captureFocusPending=true
        panel.isFocusableInTouchMode=true
        params.flags=(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        windowManager.updateViewLayout(panel,params)
        panel.requestFocus()
        handler.removeCallbacks(releaseCaptureFocus)
        handler.postDelayed(releaseCaptureFocus,800L)
        if(panel.hasWindowFocus())handler.post {
            if(captureFocusPending&&panelOpen&&!panelCollapsing)captureCurrentClipboard(false)
            releaseClipboardFocus()
        }
    }

    private fun releaseClipboardFocus() {
        captureFocusPending=false
        handler.removeCallbacks(releaseCaptureFocus)
        val panel=panelView?:return
        val params=panelParams?:return
        params.flags=(params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) and
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM.inv()
        runCatching{windowManager.updateViewLayout(panel,params)}
        panel.clearFocus()
        panel.isFocusableInTouchMode=false
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
                val pressScale=if (ValueAnimator.areAnimatorsEnabled()) overlayPreferences.panelPressScalePercent()/100f else 1f
                panel.animate().scaleX(pressScale).scaleY(pressScale)
                    .setDuration((overlayPreferences.transitionDurationMs()*0.3f).toLong().coerceIn(50,150)).start()
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
                    .setDuration((overlayPreferences.transitionDurationMs()*0.6f).toLong().coerceIn(90,260))
                    .setInterpolator(PathInterpolator(0.2f, 0.8f, 0.2f, 1f))
                    .start()
                if (moved && !cancelled) overlayPreferences.savePanelPosition(windowParams.x, windowParams.y)
            }
        }
    }

    private var lastDeletedEntry: ClipEntry? = null
    private fun appendUndo(container: LinearLayout) {
        val removed=lastDeletedEntry ?: return
        val palette=themeProvider.palette()
        container.addView(TextView(this).apply {
            text="记录已删除 · 撤销";textSize=14f;gravity=Gravity.CENTER;minimumHeight=dp(48)
            setTextColor(palette.primaryText)
            setOnClickListener {
                runCatching { store.restore(removed) }.onSuccess { lastDeletedEntry=null;renderEntries() }
                    .onFailure { Toast.makeText(this@ClipboardOverlayService,"暂时无法恢复",Toast.LENGTH_SHORT).show() }
            }
        })
    }
    private fun deleteClip(entry: ClipEntry) {
        store.delete(entry.id);lastDeletedEntry=entry;dismissEntryActionLayer(animated=false);renderEntries()
    }
    private fun renderEntries() {
        if (!panelOpen || panelCollapsing) return
        val container = listContainer ?: return
        val palette = themeProvider.palette()
        container.removeAllViews()
        val allItems = store.entries()
        val sourceItems = if(activeSource.isEmpty()) allItems else allItems.filter { it.sourceDeviceId == activeSource }
        val items = if (activeCategory == ALL_CATEGORY) {
            sourceItems
        } else {
            sourceItems.filter { it.category == activeCategory }
        }
        if (items.isEmpty()) {
            val sourceName=ClipboardSources.tabs(this,allItems).firstOrNull{it.id==activeSource}?.name
            container.addView(TextView(this).apply {
                text = when {
                    allItems.isEmpty() -> getString(R.string.empty_history_hint)
                    activeSource.isNotEmpty() -> "${sourceName ?: "此设备"}还没有同步内容"
                    else -> "这个分类还没有内容"
                }
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(palette.secondaryText)
                setPadding(dp(10), dp(28), dp(10), dp(28))
            })
            appendUndo(container)
            return
        }
        items.forEach { container.addView(createClipRow(it)) }
        appendUndo(container)
    }

    private fun renderSourceBar() {
        val bar=sourceBar ?: return
        val palette=themeProvider.palette()
        val tabs=ClipboardSources.tabs(this,store.entries())
        if(activeSource !in tabs.map { it.id })activeSource=""
        bar.removeAllViews()
        tabs.forEachIndexed { index,source ->
            val selected=source.id==activeSource
            bar.addView(TextView(this).apply {
                text=source.name
                textSize=13f
                gravity=Gravity.CENTER
                maxLines=1
                ellipsize=android.text.TextUtils.TruncateAt.END
                setPadding(dp(14),0,dp(14),0)
                setTextColor(if(selected) accentColor(palette) else palette.secondaryText)
                val chipAlpha=if(overlayPreferences.translucentCards()) { if(palette.isDark) 145 else 155 } else { if(palette.isDark) 190 else 220 }
                background=roundedBackground(if(selected) withAlpha(accentColor(palette),if(palette.isDark)70 else 34) else withAlpha(palette.rowBackground,chipAlpha),12f)
                contentDescription="剪贴板来源：${source.name}"
                setOnClickListener {
                    if(activeSource!=source.id){
                        activeSource=source.id
                        activeCategory=ALL_CATEGORY
                        renderSourceBar();renderCategoryBar();renderEntries()
                    }
                }
            },LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(36)).apply{
                if(index>0)marginStart=dp(6)
            })
        }
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
                    setTextColor(if (selected) accentColor(palette) else palette.secondaryText)
                    val chipAlpha=if(overlayPreferences.translucentCards()) { if(palette.isDark) 145 else 155 } else { if(palette.isDark) 190 else 220 }
                    background = roundedBackground(
                        if (selected) withAlpha(accentColor(palette), if (palette.isDark) 70 else 34) else withAlpha(palette.rowBackground, chipAlpha),
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
        val wrapper = EntryGestureRow(this)
        val front=com.floatclip.app.overlay.ui.ClipboardCard(this,entry,palette.isDark,palette.primaryText,palette.rowBackground,true)
        wrapper.contentDescription=entry.text+"，来源："+entry.sourceDeviceName+(if(entry.pinned) "，已置顶" else "")+(if(entry.locked) "，已保护" else "")

        wrapper.bind(
            front,
            EntrySwipeActions(this, entry.pinned, palette.isDark,
                onPin = { store.togglePinned(entry.id); renderEntries() },
                onDelete = { deleteClip(entry) },
            ),
            dp(148),
            EntryLockAction(this,entry.locked,palette.isDark,overlayPreferences.accentTheme()) {
                store.toggleLocked(entry.id); renderEntries()
            },
            dp(78),
        )
        wrapper.onSingleTap = {
            val pasted=PasteAccessibilityService.paste(entry.text)
            if(!pasted) clipboard.writeText(entry.text)
            else com.floatclip.app.sync.SyncRuntime.userCopied(this,entry.text)
            Toast.makeText(this, if(pasted) "已粘贴" else "已复制", Toast.LENGTH_SHORT).show()
            if (!panelPinnedMode) collapsePanel()
        }
        wrapper.onLongPress = { showEntryMenu(entry, anchor = wrapper) }
        return wrapper.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, dp(3), 0, dp(3)) }
        }
    }

    private fun showEntryMenu(
        entry: ClipEntry, choosingCategory: Boolean = false,
        confirmingDelete: Boolean = false, anchor: View? = null,
    ) {
        if (!panelOpen) return
        val panel = panelView ?: return
        dismissEntryActionLayer(animated = false)
        panel.dragEnabled = false
        val palette = themeProvider.palette()
        val layer = FrameLayout(this).apply {
            isClickable = true
            setBackgroundColor(Color.argb(35, 0, 0, 0))
            setOnClickListener { dismissEntryActionLayer() }
        }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = roundedBackground(palette.panelBackground, 16f)
            isClickable = true
        }
        fun row(label: String, tint: Int = palette.primaryText, action: () -> Unit) {
            if (label == "删除" || label == "确认删除") sheet.addView(View(this).apply {
                setBackgroundColor(withAlpha(palette.secondaryText, 35))
            }, LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(10), dp(3), dp(10), dp(3)) })
            val icon = when {
                label == "复制" || label == "粘贴" -> R.drawable.ic_copy_paste
                label.contains("置顶") -> R.drawable.ic_pin
                label.contains("保护") -> R.drawable.ic_lock
                label == "删除" || label == "确认删除" -> R.drawable.ic_delete
                label.contains("返回") || label == "取消" -> R.drawable.ic_chevron_left
                choosingCategory && label in categoryStore.categories() -> CategoryIcons.resource(categoryStore.iconKey(label))
                else -> R.drawable.ic_folder
            }
            sheet.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(12), 0)
                minimumHeight = dp(48)
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), roundedBackground(withAlpha(accentColor(palette), 35), 9f))
                    addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                }
                addView(ImageView(this@ClipboardOverlayService).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(tint)
                }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })
                addView(TextView(this@ClipboardOverlayService).apply {
                    text = label
                    textSize = 14f
                    gravity = Gravity.CENTER_VERTICAL
                    setTextColor(tint)
                    setPadding(0, dp(10), 0, dp(10))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                setOnClickListener { action() }
                contentDescription = label
            }, LinearLayout.LayoutParams(-1, -2))
        }
        if (confirmingDelete) {
            sheet.addView(TextView(this).apply {
                text = "删除这条记录？"
                textSize = 14f
                setTextColor(palette.primaryText)
                setPadding(dp(14), dp(10), dp(10), dp(6))
            })
            row("取消") { dismissEntryActionLayer() }
            row("确认删除", Color.rgb(218, 78, 78)) {
                store.delete(entry.id)
                dismissEntryActionLayer(animated = false)
                renderEntries()
            }
        } else if (choosingCategory) {
            row("返回") { showEntryMenu(entry, anchor = anchor) }
            categoryStore.categories().distinct().forEach { category ->
                row(if (category == entry.category) "✓  $category" else category,
                    if (category == entry.category) accentColor(palette) else palette.primaryText) {
                    store.updateCategory(entry.id, category)
                    dismissEntryActionLayer(animated = false)
                    renderCategoryBar()
                    renderEntries()
                }
            }
        } else {
            row("复制") {
                clipboard.writeText(entry.text)
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
                dismissEntryActionLayer()
            }
            if (PasteAccessibilityService.isConnected()) row("粘贴") {
                dismissEntryActionLayer(animated = false)
                pasteEntry(entry)
            }
            row(if (entry.pinned) "取消置顶" else "置顶") {
                store.togglePinned(entry.id)
                dismissEntryActionLayer(animated = false)
                renderEntries()
            }
            row(if (entry.locked) "取消保护" else "保护") {
                store.toggleLocked(entry.id)
                dismissEntryActionLayer(animated = false)
                renderEntries()
            }
            row("更改分类") { showEntryMenu(entry, choosingCategory = true, anchor = anchor) }
            row("删除", Color.rgb(218, 78, 78)) {
                showEntryMenu(entry, confirmingDelete = true, anchor = anchor)
            }
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            background = roundedBackground(palette.panelBackground, 16f)
            addView(sheet)
        }
        val menuWidth = min(dp(216), panel.width - dp(28))
        val availableHeight = (panel.height - dp(24)).coerceAtLeast(dp(96))
        sheet.measure(View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val menuHeight = min(availableHeight, sheet.measuredHeight)
        val panelLocation = IntArray(2)
        val anchorLocation = IntArray(2)
        panel.getLocationOnScreen(panelLocation)
        anchor?.getLocationOnScreen(anchorLocation)
        val below = if (anchor != null) anchorLocation[1] - panelLocation[1] + anchor.height + dp(4) else dp(52)
        val above = if (anchor != null) anchorLocation[1] - panelLocation[1] - menuHeight - dp(4) else dp(52)
        val top = (if (below + menuHeight <= panel.height - dp(12)) below else above)
            .coerceIn(dp(8), max(dp(8), panel.height - menuHeight - dp(12)))
        layer.addView(scroll, FrameLayout.LayoutParams(menuWidth, menuHeight, Gravity.TOP or Gravity.END).apply {
            topMargin = top
            marginEnd = dp(10)
        })
        panel.addView(layer, FrameLayout.LayoutParams(-1, -1))
        entryActionLayer = layer
    }

    private fun dismissEntryActionLayer(animated: Boolean = true) {
        val layer = entryActionLayer ?: return
        entryActionLayer = null
        panelView?.dragEnabled = true
        layer.animate().cancel()
        if (!animated) {
            (layer.parent as? ViewGroup)?.removeView(layer)
            return
        }
        layer.animate()
            .alpha(0f)
            .setDuration(110L)
            .withEndAction { (layer.parent as? ViewGroup)?.removeView(layer) }
            .start()
    }

    private fun pasteEntry(entry: ClipEntry) {
        val pasted = AccessibilityPasteBridge.pasteText(entry.text)
        if(pasted)com.floatclip.app.sync.SyncRuntime.userCopied(this,entry.text)
        if (!pasted) {
            clipboard.writeText(entry.text)
            Toast.makeText(this, "已复制；启用一键粘贴后可直接粘贴", Toast.LENGTH_SHORT).show()
        }
        if (!panelPinnedMode) collapsePanel()
    }

    private fun captureCurrentClipboard(showFeedback: Boolean) {
        if (!panelOpen || panelCollapsing) return
        val panel = panelView ?: return
        val generation = ++clipboardCaptureGeneration
        val text = runCatching { clipboard.readCurrentText(forCapture = true) }.getOrNull()
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
            imageTintList = ColorStateList.valueOf(if (panelPinnedMode) accentColor(palette) else palette.secondaryText)
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

    private fun collapsePanel() {
        if (!panelOpen || panelCollapsing) return
        val panel = panelView ?: return
        releaseClipboardFocus()
        panelCollapsing = true
        panelOpen = false
        panel.interactionBlocked = true
        clipboardCaptureGeneration++
        handler.removeCallbacks(clipboardPollRunnable)
        handler.removeCallbacks(edgeHideRunnable)
        bubbleMotion.cancel()
        bubbleMotionAnimator?.cancel()
        bubbleMotionAnimator = null
        panel.animate().cancel()
        listContainer?.let { list ->
            for (i in 0 until list.childCount) (list.getChildAt(i) as? EntryGestureRow)?.cancelPendingGestures()
        }
        dismissEntryActionLayer(animated = false)
        val animations=ValueAnimator.areAnimatorsEnabled()
        val duration=overlayPreferences.transitionDurationMs().toLong()
        panelScrimView?.let { scrim ->
            panelScrimParams?.let {
                it.alpha = 0f
                it.flags = it.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                windowManager.updateViewLayout(scrim, it)
            }
        }
        bubbleView?.let { bubble ->
            bubble.animate().cancel()
            bubbleParams?.let {
                it.alpha = 1f
                it.flags = it.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                windowManager.updateViewLayout(bubble, it)
            }
            if(animations) {
                bubble.alpha=0f; bubble.scaleX=0.76f; bubble.scaleY=0.76f
                bubble.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(duration)
                    .setInterpolator(PathInterpolator(0.2f,0.8f,0.2f,1f))
                    .start()
            } else {
                bubble.alpha=1f; bubble.scaleX=1f; bubble.scaleY=1f
            }
        }
        fun finishCollapse() {
            panelParams?.let {
                it.alpha = 0f
                it.flags = it.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                windowManager.updateViewLayout(panel, it)
            }
            panel.alpha=1f;panel.scaleX=1f;panel.scaleY=1f
            panelCollapsing=false
        }
        if(animations) {
            panel.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f)
                .setDuration(duration)
                .setInterpolator(PathInterpolator(0.2f,0.8f,0.2f,1f))
                .withEndAction { finishCollapse() }.start()
        } else finishCollapse()
        // Keep the exact pre-open dock coordinate. Edge-hide runs only after a real ball drag.
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
            .setContentText(if(overlayPreferences.detailedNotification()) "悬浮剪贴板正在后台运行" else "")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setOngoing(true)
            .build()
    }

    private fun percentAlpha(percent: Int): Int = (255f * percent.coerceIn(0, 100) / 100f).toInt().coerceIn(0, 255)

    private fun accentColor(palette: OverlayPalette): Int =
        FloatClipAccent.color(overlayPreferences.accentTheme(), palette.isDark)

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
        private const val NOTIFICATION_ID = 2001
        private const val CLIPBOARD_POLL_MS = 650L
        private const val BUBBLE_APPEAR_MS = 240L
        private const val EDGE_HIDE_DELAY_MS = 900L
        private const val EDGE_HIDE_ANIMATION_MS = 300L
        private const val PANEL_APPEAR_MS = 300L
        private const val PANEL_DISMISS_MS = 420L
        private const val ALL_CATEGORY = "全部"
        private const val MENU_COPY = 1
        private const val MENU_PIN = 2
        private const val MENU_DELETE = 3
        private const val MENU_CATEGORY_BASE = 1000

    }
}
