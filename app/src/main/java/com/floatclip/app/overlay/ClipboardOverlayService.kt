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
import com.floatclip.app.clipboard.ClipboardImages
import com.floatclip.app.clipboard.ClipViewerActivity
import com.floatclip.app.clipboard.ClipImageViewerView
import com.floatclip.app.clipboard.ClipTextViewerView
import com.floatclip.app.notifications.FloatClipForeground
import com.floatclip.app.integration.AccessibilityPasteBridge
import com.floatclip.app.integration.IntegrationRegistry
import com.floatclip.app.model.ClipEntry
import com.floatclip.probe.FloatClipAccent
import com.floatclip.app.prefs.CategoryStore
import com.floatclip.app.prefs.CategoryIcons
import com.floatclip.app.prefs.OverlayPreferences
import com.floatclip.app.prefs.OverlayThemeMode
import com.floatclip.app.overlay.ui.BorderDragFrameLayout
import com.floatclip.app.overlay.ui.BubbleMotionController
import com.floatclip.app.overlay.ui.PanelGeometry
import com.floatclip.app.overlay.ui.OverlayWindows
import com.floatclip.app.ui.ClipboardHistoryList
import com.floatclip.app.ui.LatestSnapshotLoader
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
    private lateinit var overlayWindows:OverlayWindows
    private var stopping=false
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
    private var inlineGallery: ClipImageViewerView? = null
    private var inlineTextViewer: ClipTextViewerView? = null
    private var entryActionLayer: View? = null
    private var historyList: ClipboardHistoryList? = null
    private var historySnapshot=emptyList<ClipEntry>()
    private var historyLoaded=false
    private val historyWorker=java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task,"FloatClip history").apply { isDaemon=true }
    }
    private lateinit var historyLoader:LatestSnapshotLoader<List<ClipEntry>>
    private var panelContentView:View?=null
    private var restingPanelX=0
    private var restingPanelY=0
    private var imageImportButton: View? = null
    private var undoContainer: LinearLayout? = null
    private var categoryBar: LinearLayout? = null
    private var sourceBar: LinearLayout? = null
    private var categoryScrollView: HorizontalScrollView? = null
    private var sourceScrollView: HorizontalScrollView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelPinnedMode = false
    private var bubbleMotionAnimator: ValueAnimator? = null
    private var foregroundStarted = false
    private val sharedHistoryChanged=object: BroadcastReceiver(){override fun onReceive(context: Context?,intent: Intent?){if(panelOpen&&!panelCollapsing){renderSourceBar();renderCategoryBar();renderEntries()}}}
    private val localBinder = Binder()
    private var panelOpen = false
    private var panelCollapsing = false
    private var clipboardCaptureGeneration = 0
    private var activeCategory = ALL_CATEGORY
    private var activeSource = ""
    private var lastCapturedClipboard: String? = null
    private var lastCapturedImage=""
    private val undoOwner="overlay:"+java.util.UUID.randomUUID()
    private var captureFocusPending=false
    private val releaseCaptureFocus=Runnable { releaseClipboardFocus() }

    private val edgeHideRunnable = Runnable { hideBubbleAtEdge() }
    private var canRepairDetachedWindow = true
    private val restoreOverlayRunnable = Runnable(::restoreOverlayWindow)
    private val releaseIdlePanel = Runnable { if (!panelOpen && !panelCollapsing) releasePanelWindows() }
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
            when(intent?.action) {
                ACTION_REPOSITION_PANEL -> repositionVisiblePanel()
                ACTION_REFRESH_APPEARANCE -> {
                    handler.removeCallbacks(refreshAppearanceRunnable)
                    handler.post(refreshAppearanceRunnable)
                }
            }
        }
    }
    private var pendingViewerToken:String?=null
    private val viewerReturn=object:BroadcastReceiver() {
        override fun onReceive(context:Context?,intent:Intent?) {
            val token=intent?.getStringExtra(com.floatclip.app.clipboard.ClipViewerActivity.EXTRA_RETURN)
            if(!stopping && token!=null && token==pendingViewerToken) {
                pendingViewerToken=null
                if (canPresentOverlay() && intent.getBooleanExtra(ClipViewerActivity.EXTRA_SHOW_PANEL, true)) expandPanel(false)
                else queueOverlayRecovery()
            }
        }
    }
    private var lastSystemNight = false
    private var lastScreenSize: Point? = null
    private val systemAppearanceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> {
                    @Suppress("DEPRECATION")
                    val reason = intent.getStringExtra("reason")
                    if ((reason == "homekey" || reason == "recentapps") && !panelPinnedMode) {
                        closeInlineGallery()
                        collapsePanel()
                    }
                    // An explicitly pinned floating panel must survive Home and Recents.
                    // System/OEM transitions may detach the window; recover its presentation.
                    if ((reason == "homekey" || reason == "recentapps") && panelPinnedMode)
                        handler.postDelayed(restoreOverlayRunnable, 200L)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    handler.removeCallbacks(restoreOverlayRunnable)
                    ClipboardImages.trimThumbnailsForStandby()
                    if (panelView != null) {
                        releasePanelWindows()
                        showPanelPresentation(false)
                    }
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    canRepairDetachedWindow = true
                    refreshSystemThemeIfNeeded()
                    queueOverlayRecovery()
                }
                Intent.ACTION_CONFIGURATION_CHANGED -> refreshSystemThemeIfNeeded()
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag") // registration requires a package-scoped signature permission
    override fun onCreate() {
        super.onCreate()
        val historyFilter=IntentFilter(com.floatclip.app.sync.ClipboardSyncService.HISTORY_CHANGED)
        if(android.os.Build.VERSION.SDK_INT>=33)registerReceiver(sharedHistoryChanged,historyFilter,"$packageName.permission.INTERNAL",null,Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(sharedHistoryChanged,historyFilter,"$packageName.permission.INTERNAL",null)
        windowManager = getSystemService(WindowManager::class.java)
        overlayWindows = OverlayWindows(windowManager) { detached ->
            if (canRepairDetachedWindow && (detached === bubbleView || detached === panelView)) {
                canRepairDetachedWindow = false
                queueOverlayRecovery()
            }
        }
        store = ClipStore(this)
        historyLoader=LatestSnapshotLoader(historyWorker,java.util.concurrent.Executor { handler.post(it) },{ store.entries() }) { result ->
            result.onSuccess {
                historySnapshot=it;historyLoaded=true
                if(panelOpen && !panelCollapsing) {
                    renderSourceBar();renderCategoryBar();renderEntries(false)
                }
            }
        }
        clipboard = ClipboardRepository(this)
        categoryStore = CategoryStore(this)
        overlayPreferences = OverlayPreferences(this)
        IntegrationRegistry.initialize(this)
        themeProvider = AdaptiveOverlayThemeProvider(this, IntegrationRegistry.originOsSystemBridge)
        lastSystemNight = isSystemNight()
        lastScreenSize = screenSize()
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
            IntentFilter().apply {
                addAction(ACTION_REFRESH_APPEARANCE)
                addAction(ACTION_REPOSITION_PANEL)
            },
            "$packageName.permission.INTERNAL",
            null,
        )
        registerReceiver(
            systemAppearanceReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_CONFIGURATION_CHANGED)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                @Suppress("DEPRECATION")
                addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            },
        )
        val viewerFilter=IntentFilter(com.floatclip.app.clipboard.ClipViewerActivity.RETURN_ACTION)
        if(android.os.Build.VERSION.SDK_INT>=33)registerReceiver(viewerReturn,viewerFilter,"$packageName.permission.INTERNAL",null,Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(viewerReturn,viewerFilter,"$packageName.permission.INTERNAL",null)
        if (canPresentOverlay()) showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!overlayAllowed()) { stopSelf(); return START_NOT_STICKY }
        ensureForeground()
        canRepairDetachedWindow = true
        when (intent?.action) {
            ACTION_REPOSITION_PANEL -> repositionVisiblePanel()
            ACTION_REFRESH_APPEARANCE -> {
                handler.removeCallbacks(refreshAppearanceRunnable)
                handler.post(refreshAppearanceRunnable)
            }
            else -> restoreOverlayWindow()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        canRepairDetachedWindow = true
        if (bubbleView == null) {
            restoreOverlayWindow()
            if (bubbleView == null) queueOverlayRecovery()
        }
        return localBinder
    }

    private fun ensureForeground() {
        if (foregroundStarted) return
        FloatClipForeground.enter(this)
        foregroundStarted = true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val size = screenSize()
        if (size != lastScreenSize) {
            lastScreenSize = size
            handler.removeCallbacks(refreshAppearanceRunnable)
            handler.postDelayed(refreshAppearanceRunnable, 32L)
        } else refreshSystemThemeIfNeeded()
    }

    override fun onDestroy() {
        stopping=true
        if(::historyLoader.isInitialized)historyLoader.close()
        historyWorker.shutdownNow()
        if(::store.isInitialized)store.retainUndoImages(undoOwner,emptyList())
        releaseClipboardFocus()
        clipboardCaptureGeneration++
        handler.removeCallbacksAndMessages(null)
        if(::bubbleMotion.isInitialized)bubbleMotion.cancel()
        bubbleMotionAnimator?.cancel()
        bubbleMotionAnimator = null
        clearMotion(bubbleView)
        clearMotion(panelView)
        clearMotion(panelSurfaceView)
        if(::overlayWindows.isInitialized)overlayWindows.close()
        bubbleView = null
        panelOpen = false
        panelView = null
        panelSurfaceView = null
        panelScrimView = null
        panelScrimParams = null
        panelPinButton = null
        entryActionLayer = null
        pendingViewerToken=null
        closeInlineGallery()
        closeInlineTextViewer()
        runCatching { unregisterReceiver(viewerReturn) }
        runCatching { unregisterReceiver(appearanceReceiver) }
        runCatching { unregisterReceiver(sharedHistoryChanged) }
        runCatching { unregisterReceiver(systemAppearanceReceiver) }
        if (OverlayKeepAliveScheduler.shouldRun(this)) OverlayKeepAliveScheduler.schedule(this)
        if (foregroundStarted) {
            FloatClipForeground.leave(this)
            foregroundStarted=false
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (OverlayKeepAliveScheduler.shouldRun(this)) {
            canRepairDetachedWindow = true
            // Keep the current window attached. Only recover it if the system actually removed it.
            queueOverlayRecovery()
            OverlayKeepAliveScheduler.schedule(this)
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun isSystemNight(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun refreshSystemThemeIfNeeded() {
        if (overlayPreferences.themeMode() != OverlayThemeMode.SYSTEM &&
            overlayPreferences.bubbleThemeMode() != OverlayThemeMode.SYSTEM
        ) return
        val current = isSystemNight()
        if (current == lastSystemNight) return
        lastSystemNight = current
        handler.removeCallbacks(refreshAppearanceRunnable)
        handler.postDelayed(refreshAppearanceRunnable, 32L)
    }

    private fun overlayAllowed() = overlayPreferences.overlayEnabled() && android.provider.Settings.canDrawOverlays(this)

    private fun canPresentOverlay() = overlayAllowed() &&
        getSystemService(android.os.PowerManager::class.java).isInteractive &&
        !getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked

    private fun queueOverlayRecovery() {
        if (stopping) return
        handler.removeCallbacks(restoreOverlayRunnable)
        handler.postDelayed(restoreOverlayRunnable, 350L)
    }

    private fun restoreOverlayWindow() {
        if (stopping || !canPresentOverlay() || pendingViewerToken != null) return
        val visible = if (panelOpen) panelView else bubbleView
        if (overlayWindows.isVisible(visible)) return
        rebuildVisibleOverlay(reopenPanel = panelOpen && panelPinnedMode)
    }

    private fun rebuildVisibleOverlay(reopenPanel: Boolean = panelOpen && !panelCollapsing) {
        if (stopping || !canPresentOverlay()) return
        lastSystemNight = isSystemNight()
        handler.removeCallbacks(edgeHideRunnable)
        bubbleMotion.cancel()
        bubbleMotionAnimator?.cancel()
        bubbleMotionAnimator = null
        clearMotion(bubbleView)
        bubbleView?.let(overlayWindows::remove)
        bubbleView = null
        bubbleParams = null
        releasePanelWindows()
        showBubble()
        if (foregroundStarted) FloatClipForeground.refresh(this)
        if (reopenPanel) handler.postDelayed({ expandPanel(false) }, 20L)
    }

    private fun releasePanelWindows() {
        closeInlineGallery()
        closeInlineTextViewer()
        handler.removeCallbacks(releaseIdlePanel)
        handler.removeCallbacks(clipboardPollRunnable)
        presentationGeneration++
        clipboardCaptureGeneration++
        panelOpen = false
        panelCollapsing = false
        releaseClipboardFocus()
        historyList?.cancelVisibleGestures()
        clearMotion(panelView)
        clearMotion(panelSurfaceView)
        panelView?.let(overlayWindows::remove)
        panelScrimView?.let(overlayWindows::remove)
        panelView = null
        panelParams = null
        panelSurfaceView = null
        panelScrimView = null
        panelScrimParams = null
        panelPinButton = null
        entryActionLayer = null
        historyList = null
        historyLoader.invalidate()
        historySnapshot = emptyList()
        historyLoaded = false
        panelContentView = null
        imageImportButton = null
        undoContainer = null
        categoryBar = null
        sourceBar = null
        categoryScrollView = null
        sourceScrollView = null
    }

    private fun showBubble() {
        if (stopping || bubbleView != null || !canPresentOverlay()) return
        handler.removeCallbacks(edgeHideRunnable)
        bubbleMotion.cancel()
        bubbleMotionAnimator?.cancel()
        bubbleMotionAnimator = null

        val palette = themeProvider.bubblePalette()
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
            flags=flags or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
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
            alpha = 1f
            scaleX = 1f
            scaleY = 1f
        }
        installBubbleTouch(bubble, params)
        bubbleView = bubble
        if(!overlayWindows.add(bubble,params)) {
            bubbleView=null;bubbleParams=null;stopSelf();return
        }
        scheduleEdgeHide()
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
                        .setDuration((overlayPreferences.transitionDurationMs()*0.35f).toLong().coerceIn(0,1750))
                        .setInterpolator(overlayPreferences.motionInterpolator())
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
                    runCatching { overlayWindows.update(view, params) }
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
                        .setDuration((overlayPreferences.transitionDurationMs()*0.55f).toLong().coerceIn(0,2750))
                        .setInterpolator(overlayPreferences.motionInterpolator())
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
        if (stopping || panelOpen || panelCollapsing) return
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
            interpolator = overlayPreferences.motionInterpolator()
            addUpdateListener { animator ->
                params.x = lerp(startX, targetX, animator.animatedValue as Float)
                runCatching { overlayWindows.update(view, params) }
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
        if (!canPresentOverlay()) return
        handler.removeCallbacks(releaseIdlePanel)
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
            flags=flags or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            windowAnimations = 0
            gravity = Gravity.START or Gravity.TOP
            x = panelX
            y = panelY
            alpha = 0f
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        panelParams = params
        restingPanelX=panelX;restingPanelY=panelY

        val blurAvailable=android.os.Build.VERSION.SDK_INT>=31 &&
            runCatching { windowManager.isCrossWindowBlurEnabled }.getOrDefault(false)
        val blur=overlayPreferences.backgroundMode()=="frost" && blurAvailable
        if(android.os.Build.VERSION.SDK_INT>=31) {
            if(blur) {
                params.flags=params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                params.setBlurBehindRadius(dp(4 + overlayPreferences.frostStrengthPercent()*48/100))
            } else {
                params.flags=params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                params.setBlurBehindRadius(0)
            }
        }
        val panelAlpha = percentAlpha(overlayPreferences.surfacePanelAlphaPercent())
        val frost = if (overlayPreferences.backgroundMode() == "frost") overlayPreferences.frostStrengthPercent() / 100f else 0f
        val frostedPanel = blendColor(palette.panelBackground, Color.WHITE, (if (palette.isDark) 0.07f else 0.22f) * frost)
        val borderAlpha = percentAlpha(overlayPreferences.borderAlphaPercent())
        val borderColor = Color.argb(borderAlpha, 25, 40, 63)
        val panel = BorderDragFrameLayout(this).apply {
            isClickable = true
            // The noise texture was not blur. Real cross-window blur is controlled
            // by FLAG_BLUR_BEHIND on API 31+; use a plain tinted surface otherwise.
            background = roundedBackground(
                withAlpha(frostedPanel, panelAlpha),
                palette.panelCornerRadiusDp,
                borderColor.takeIf { !palette.isDark && overlayPreferences.overlayBorderEnabled() },
            )
            clipToOutline = true
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

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { panelContentView=it }
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

        header.addView(iconButton(R.drawable.ic_delete, "全部清除，保留固定和置顶记录", palette.secondaryText) {
            clearHistory()
        }.apply { id=R.id.clipboard_clear; tooltipText="全部清除（保留固定和置顶）" }, LinearLayout.LayoutParams(dp(40),dp(40)))

        val pinButton = iconButton(
            if (panelPinnedMode) R.drawable.ic_pin_filled else R.drawable.ic_pin,
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
        }.also { sourceScrollView=it }
        sourceBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sourceScroll.addView(sourceBar, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
        content.addView(sourceScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(47)))

        val history=ClipboardHistoryList(this).apply { automaticImageColumns=false }.also { historyList=it }
        undoContainer=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        history.attachHeader(undoContainer!!)
        content.addView(history,LinearLayout.LayoutParams(-1,0,1f))

        val categoryScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(dp(2), dp(5), dp(2), 0)
        }.also { categoryScrollView=it }
        categoryBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        categoryScroll.addView(categoryBar, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        content.addView(categoryScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(45)))

        installPanelDrag(panel, params)

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
        if(!overlayWindows.add(scrim,scrimParams) || !overlayWindows.add(panel,params)) {
            overlayWindows.remove(scrim);overlayWindows.remove(panel)
            panelView=null;panelParams=null;panelScrimView=null;panelScrimParams=null
            showPanelPresentation(false)
            return
        }

        showRetainedPanel(captureOnOpen)
    }

    /** Keep all three WindowManager surfaces attached; open/close never reallocates them. */
    private fun showRetainedPanel(captureOnOpen:Boolean=true) {
        val panel = panelView ?: return
        val params = panelParams ?: return
        panelOpen = true
        clearMotion(panel)
        val screen=screenSize()
        val ball=bubbleParams
        val placed=PanelGeometry.placement(screen.x,screen.y,
            PanelGeometry.Box(overlayPreferences.panelX(restingPanelX),overlayPreferences.panelY(restingPanelY),params.width,params.height),
            PanelGeometry.Box(ball?.x?:0,ball?.y?:0,ball?.width?:dp(50),ball?.height?:dp(50)),
            overlayPreferences.panelFollowsBubble(),dp(8),dp(16))
        restingPanelX=placed.x;restingPanelY=placed.y
        params.x=placed.x;params.y=placed.y
        renderSourceBar()
        renderCategoryBar()
        renderEntries()
        showPanelPresentation(true)
        applyPanelFocus(panel, params)
        if(captureOnOpen)beginClipboardFocus()
    }

    /** Settings change repositions the attached panel, without reallocating its window. */
    private fun repositionVisiblePanel() {
        val params = panelParams ?: return
        val panel = panelView ?: return
        val ball = bubbleParams
        val size = screenSize()
        val placement = PanelGeometry.placement(
            size.x, size.y,
            PanelGeometry.Box(overlayPreferences.panelX(restingPanelX),
                overlayPreferences.panelY(restingPanelY), params.width, params.height),
            PanelGeometry.Box(ball?.x ?: 0, ball?.y ?: 0,
                ball?.width ?: dp(50), ball?.height ?: dp(50)),
            overlayPreferences.panelFollowsBubble(), dp(8), dp(16)
        )
        restingPanelX = placement.x
        restingPanelY = placement.y
        params.x = placement.x
        params.y = placement.y
        overlayWindows.update(panel, params)
    }

    /** Only an explicit open takes a short focus lease; idle/background never steals focus. */
    private fun beginClipboardFocus() {
        val panel=panelView?:return
        val params=panelParams?:return
        captureFocusPending=true
        applyPanelFocus(panel, params)
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
        applyPanelFocus(panel, params)
    }

    private fun applyPanelFocus(panel: BorderDragFrameLayout, params: WindowManager.LayoutParams) {
        val handleBack = panelOpen && !panelPinnedMode
        val focusable = handleBack || captureFocusPending
        panel.onPanelBack = if (handleBack) ::collapsePanel else null
        panel.isFocusableInTouchMode = focusable
        params.flags = if (focusable) (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM else (params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) and
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM.inv()
        overlayWindows.update(panel, params)
        if (focusable) panel.requestFocus() else panel.clearFocus()
    }

    private fun installPanelDrag(
        panel: BorderDragFrameLayout,
        windowParams: WindowManager.LayoutParams,
    ) {
        panel.borderDragPx = dp(14)
        panel.topDragHeightPx = dp(50)
        panel.topActionReservePx = dp(90)
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
                val pressScale = if (ValueAnimator.areAnimatorsEnabled()) {
                    overlayPreferences.panelPressScalePercent() / 100f
                } else 1f
                panel.animate().scaleX(pressScale).scaleY(pressScale)
                    .setDuration((overlayPreferences.transitionDurationMs() * 0.3f).toLong().coerceIn(0, 1500))
                    .start()
            }

            override fun onDragMove(rawX: Float, rawY: Float) {
                val dx = rawX - downX
                val dy = rawY - downY
                if (!moved && abs(dx) <= dp(2) && abs(dy) <= dp(2)) return
                moved = true
                val screen = screenSize()
                windowParams.x = (startX + dx.toInt()).coerceIn(dp(8), max(dp(8), screen.x - windowParams.width - dp(8)))
                windowParams.y = (startY + dy.toInt()).coerceIn(dp(16), max(dp(16), screen.y - windowParams.height - dp(16)))
                runCatching { overlayWindows.update(panel, windowParams) }
            }

            override fun onDragEnd(rawX: Float, rawY: Float, cancelled: Boolean) {
                panel.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration((overlayPreferences.transitionDurationMs() * 0.6f).toLong().coerceIn(0, 3000))
                    .setInterpolator(overlayPreferences.motionInterpolator())
                    .start()
                if (!moved) return
                if (cancelled) {
                    windowParams.x = startX
                    windowParams.y = startY
                    runCatching { overlayWindows.update(panel, windowParams) }
                    return
                }
                restingPanelX = windowParams.x
                restingPanelY = windowParams.y
                overlayPreferences.savePanelPosition(windowParams.x, windowParams.y)
            }
        }
    }

    private var lastDeletedEntries: List<ClipEntry> = emptyList()
        set(value) {
            field=value
            // Pin undo attachments only when the undo state changes, never during filtering.
            if(::store.isInitialized)store.retainUndoImages(undoOwner,value)
        }
    private fun appendUndo(container: LinearLayout) {
        if(lastDeletedEntries.isEmpty()) return
        val palette=themeProvider.palette()
        container.addView(TextView(this).apply {
            id=R.id.clipboard_undo
            text=if(lastDeletedEntries.size==1) "记录已删除 · 撤销" else "已清除 ${lastDeletedEntries.size} 条 · 撤销"
            textSize=14f;gravity=Gravity.CENTER;minimumHeight=dp(48)
            setTextColor(palette.primaryText)
            setOnClickListener {
                runCatching { store.restoreAll(lastDeletedEntries) }.onSuccess { remaining ->
                    lastDeletedEntries=remaining;renderSourceBar();renderCategoryBar();renderEntries();notifyHistoryChanged()
                    if(remaining.isNotEmpty()) Toast.makeText(this@ClipboardOverlayService,"空间不足，尚有 ${remaining.size} 条待恢复；新记录和受保护记录已保留",Toast.LENGTH_LONG).show()
                }
                    .onFailure { Toast.makeText(this@ClipboardOverlayService,"暂时无法恢复",Toast.LENGTH_SHORT).show() }
            }
        })
    }
    private fun deleteClip(entry: ClipEntry) {
        store.delete(entry.id);lastDeletedEntries=listOf(entry);dismissEntryActionLayer(animated=false);renderEntries();notifyHistoryChanged()
    }
    private fun clearHistory() {
        runCatching { store.clearAll() }.onSuccess { removed ->
            lastDeletedEntries=emptyList();store.retainUndoImages(undoOwner,emptyList());dismissEntryActionLayer(animated=false);renderEntries()
            if(removed.isEmpty()) Toast.makeText(this,"没有可清除的普通记录；固定和置顶记录已保留",Toast.LENGTH_SHORT).show()
            else {
                dismissEntryActionLayer(animated=false)
                renderSourceBar();renderCategoryBar();renderEntries();notifyHistoryChanged()
                Toast.makeText(this,"已清除 ${removed.size} 条普通记录，固定和置顶记录已保留",Toast.LENGTH_SHORT).show()
            }
        }.onFailure { Toast.makeText(this,"清除未完成，请保留本机数据并重试",Toast.LENGTH_LONG).show() }
    }
    private fun notifyHistoryChanged() {
        sendBroadcast(Intent(com.floatclip.app.sync.ClipboardSyncService.HISTORY_CHANGED).setPackage(packageName),"$packageName.permission.INTERNAL")
    }

    private fun revealTab(scroll:HorizontalScrollView?, selected:View?) {
        if(scroll!=null && selected!=null)com.floatclip.app.ui.TabReveal.reveal(scroll,selected)
    }

    private fun renderEntries(reload:Boolean=true) {
        if (stopping || !panelOpen || panelCollapsing) return
        if(reload) { historyLoader.request();return }
        val undo=undoContainer ?: return
        val target=historyList ?: return
        val palette=themeProvider.palette()
        undo.removeAllViews();appendUndo(undo)
        val allItems=historySnapshot
        val sourceItems=when {
            activeSource.isEmpty() -> allItems
            activeSource==ClipboardSources.IMAGE_SCOPE -> allItems.filter {
                it.kind=="image" && (overlayPreferences.mergeRemoteImages() || it.sourceDeviceId=="local")
            }
            else -> allItems.filter { it.sourceDeviceId==activeSource }
        }
        val items=if(activeCategory==ALL_CATEGORY)sourceItems else sourceItems.filter { it.category==activeCategory }
        val sourceName=ClipboardSources.tabs(this,allItems).firstOrNull { it.id==activeSource }?.name
        val hint=when {
            !historyLoaded -> "正在读取…"
            allItems.isEmpty() -> getString(R.string.empty_history_hint)
            activeSource.isNotEmpty() -> (sourceName?:"此设备")+"还没有同步内容"
            else -> "这个分类还没有内容"
        }
        target.submit(items,overlayPreferences.twoColumnClipboard(),activeSource+"|"+activeCategory,hint,palette.secondaryText,::createClipRow)
        updateOverlayImageImportAction()
        val expectedSource=activeSource;val expectedCategory=activeCategory;val expectedSnapshot=historySnapshot
        handler.postDelayed({
            if(!panelOpen || activeSource!=expectedSource || activeCategory!=expectedCategory || historySnapshot!==expectedSnapshot)return@postDelayed
            val tabs=ClipboardSources.tabs(this,expectedSnapshot)
            val next=tabs.getOrNull((tabs.indexOfFirst{it.id==activeSource}+1)%tabs.size.coerceAtLeast(1))?.id
            expectedSnapshot.asSequence().filter { it.kind=="image" && (
                (it.category!=activeCategory && (activeSource.isEmpty() || it.sourceDeviceId==activeSource)) ||
                next==ClipboardSources.IMAGE_SCOPE || next.isNullOrEmpty() || it.sourceDeviceId==next) }
                .take(3).forEach { ClipboardImages.prefetch(store,it) }
        },200)
    }

    private fun updateOverlayImageImportAction() {
        val panel=panelView?:return
        val palette=themeProvider.palette()
        val visible=activeSource==ClipboardSources.IMAGE_SCOPE
        val existing=imageImportButton
        val button=if(existing?.parent===panel)existing else TextView(this).apply {
            text="+"
            textSize=26f
            gravity=Gravity.CENTER
            contentDescription="导入图片"
            setTextColor(Color.WHITE)
            background=circleBackground(accentColor(palette),palette)
            elevation=dp(3).toFloat()
            setOnClickListener {
                startActivity(Intent(this@ClipboardOverlayService,com.floatclip.app.clipboard.ImageImportActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }.also {
            imageImportButton=it
            panel.addView(it,FrameLayout.LayoutParams(dp(50),dp(50),Gravity.END or Gravity.BOTTOM).apply {
                marginEnd=dp(16);bottomMargin=dp(58)
            })
        }
        button.visibility=if(visible)View.VISIBLE else View.GONE
        historyList?.setPadding(0,0,0,if(visible)dp(66) else 0)
    }

    /** Follow the physical tab order: move to a right-hand tab from the right, and vice versa. */
    private fun animateHistoryTabSwitch(oldIndex:Int,newIndex:Int) {
        val history=historyList ?: return
        if(oldIndex==newIndex || oldIndex<0 || newIndex<0) return
        history.animate().cancel()
        if(!android.animation.ValueAnimator.areAnimatorsEnabled()) {
            history.translationX=0f;history.alpha=1f;return
        }
        val direction=if(newIndex>oldIndex) 1f else -1f
        history.translationX=direction*dp(24)
        history.alpha=.76f
        history.animate().translationX(0f).alpha(1f)
            .setDuration(overlayPreferences.switchAnimationMs().toLong().coerceIn(90,260))
            .setInterpolator(overlayPreferences.motionInterpolator()).start()
    }

    private fun renderSourceBar() {
        val bar=sourceBar ?: return
        val palette=themeProvider.palette()
        val tabs=ClipboardSources.tabs(this,historySnapshot)
        if(activeSource !in tabs.map { it.id })activeSource=""
        bar.removeAllViews()
        var selectedView:View?=null
        tabs.forEachIndexed { index,source ->
            val selected=source.id==activeSource
            val tab=TextView(this).apply {
                text=source.name
                textSize=13f
                gravity=Gravity.CENTER
                maxLines=1
                ellipsize=android.text.TextUtils.TruncateAt.END
                setPadding(dp(14),0,dp(14),0)
                setTextColor(if(selected && palette.isDark) palette.primaryText else if(selected) accentColor(palette) else palette.secondaryText)
                val chipAlpha=if(overlayPreferences.translucentCards()) { if(palette.isDark) 145 else 155 } else { if(palette.isDark) 190 else 220 }
                background=roundedBackground(if(selected) selectedTabBackground(palette) else withAlpha(palette.rowBackground,chipAlpha),12f)
                contentDescription="剪贴板来源：${source.name}"
                setOnClickListener {
                    if(activeSource!=source.id){
                        val previous=tabs.indexOfFirst { it.id==activeSource }
                        activeSource=source.id
                        activeCategory=ALL_CATEGORY
                        renderSourceBar();renderCategoryBar();renderEntries(false)
                        animateHistoryTabSwitch(previous,index)
                    }
                }
            }
            if(selected)selectedView=tab
            bar.addView(tab,LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(36)).apply{
                if(index>0)marginStart=dp(6)
            })
        }
        revealTab(sourceScrollView,selectedView)
    }

    private fun renderCategoryBar() {
        val bar = categoryBar ?: return
        val scroll = categoryScrollView ?: return
        val palette = themeProvider.palette()
        bar.removeAllViews()
        val categories = (listOf(ALL_CATEGORY) + categoryStore.categories()).distinct()
        if (activeCategory !in categories) activeCategory = ALL_CATEGORY
        var selectedView:View?=null
        categories.forEachIndexed { index, category ->
            val selected = category == activeCategory
            val tab=TextView(this).apply {
                    text = category
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding(dp(13), 0, dp(13), 0)
                    setTextColor(if (selected && palette.isDark) palette.primaryText else if (selected) accentColor(palette) else palette.secondaryText)
                    val chipAlpha=if(overlayPreferences.translucentCards()) { if(palette.isDark) 145 else 155 } else { if(palette.isDark) 190 else 220 }
                    background = roundedBackground(
                        if (selected) selectedTabBackground(palette) else withAlpha(palette.rowBackground, chipAlpha),
                        12f,
                    )
                    setOnClickListener {
                        if (activeCategory != category) {
                            val previous=categories.indexOf(activeCategory)
                            activeCategory = category
                            renderCategoryBar()
                            renderEntries(false)
                            animateHistoryTabSwitch(previous,index)
                        } else if (index >= 3) com.floatclip.app.ui.TabReveal.reveal(scroll, this, center = true)
                    }
                }
            if(selected)selectedView=tab
            bar.addView(
                tab,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
                    if (index > 0) marginStart = dp(6)
                },
            )
        }
        val center = categories.indexOf(activeCategory) >= 3
        val firstTab = bar.getChildAt(0)
        val lastTab = bar.getChildAt(bar.childCount - 1)
        firstTab?.measure(0, 0)
        lastTab?.measure(0, 0)
        val viewport = (scroll.width - scroll.paddingLeft - scroll.paddingRight).coerceAtLeast(0)
        // Never shift the first category chip away from the actual left edge.
        // Center later categories with scroll offset and optional trailing space only.
        val leadingSpace = 0
        val trailingSpace = if (center && lastTab != null) ((viewport - lastTab.measuredWidth) / 2).coerceAtLeast(0) else 0
        bar.setPaddingRelative(leadingSpace, 0, trailingSpace, 0)
        com.floatclip.app.ui.TabReveal.reveal(scroll, selectedView, center)
    }

    private fun createClipRow(entry: ClipEntry): View {
        val palette = themeProvider.palette()
        val wrapper = EntryGestureRow(this)
        val front=com.floatclip.app.overlay.ui.ClipboardCard(this,entry,palette.isDark,palette.primaryText,palette.rowBackground,true)
        wrapper.contentDescription=entry.text+"，来源："+entry.sourceDeviceName+(if(entry.pinned) "，已置顶" else "")+(if(entry.locked) "，已保护" else "")

        if(entry.kind=="image")wrapper.bind(front) else wrapper.bind(
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
        wrapper.onSingleTap = tap@{
            if(entry.kind=="image") {
                runCatching { openEntryViewer(entry) }
                    .onFailure { Toast.makeText(this,it.message ?: "图片预览未完成",Toast.LENGTH_LONG).show() }
                return@tap
            }
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

    private fun entryMenuBackground(palette: OverlayPalette): android.graphics.drawable.Drawable {
        val base = withAlpha(palette.panelBackground, percentAlpha(overlayPreferences.surfacePanelAlphaPercent()))
        val border = if (overlayPreferences.overlayBorderEnabled())
            withAlpha(palette.secondaryText, percentAlpha(overlayPreferences.borderAlphaPercent())) else null
        // Menu surface shares the panel's plain tint and never renders synthetic noise.
        return roundedBackground(base, palette.panelCornerRadiusDp, border)
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
            // The scroll container paints the themed translucent sheet once; avoid double tint.
            background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            isClickable = true
        }
        fun row(label: String, tint: Int = palette.primaryText, action: () -> Unit) {
            if (label == "删除" || label == "确认删除") sheet.addView(View(this).apply {
                setBackgroundColor(withAlpha(palette.secondaryText, 35))
            }, LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(10), dp(3), dp(10), dp(3)) })
            val icon = when {
                label == "全屏查看图片" || label == "查看图片" -> R.drawable.ic_image
                label == "查看全文" -> R.drawable.ic_document
                label == "复制" || label == "复制图片" -> R.drawable.ic_copy_paste
                label == "粘贴" -> R.drawable.ic_paste
                label.contains("置顶") -> R.drawable.ic_pin
                label.contains("保护") -> R.drawable.ic_lock
                label == "更改分类" -> R.drawable.ic_category
                label == "删除" || label == "确认删除" -> R.drawable.ic_delete
                label.contains("返回") -> R.drawable.ic_chevron_left
                label == "取消" -> R.drawable.ic_close
                choosingCategory && label.removePrefix("✓  ") in categoryStore.categories() ->
                    CategoryIcons.resource(categoryStore.iconKey(label.removePrefix("✓  ")))
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
            row("确认删除", Color.rgb(218, 78, 78)) { deleteClip(entry) }
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
            row(if(entry.kind=="image")"全屏查看图片" else "查看全文") {
                openEntryViewer(entry)
            }
            row(if(entry.kind=="image") "复制图片" else "复制") {
                if(entry.kind=="image")ClipboardImages.copyWithFeedback(this,store,entry)
                else runCatching { clipboard.writeText(entry.text) }
                    .onSuccess { Toast.makeText(this,"已复制",Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(this,it.message ?: "复制未完成",Toast.LENGTH_LONG).show() }
                dismissEntryActionLayer()
            }
            if (entry.kind=="text" && PasteAccessibilityService.isConnected()) row("粘贴") {
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
            background = entryMenuBackground(palette)
            clipToOutline = true
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

    private fun openEntryViewer(entry:ClipEntry) {
        if(entry.kind=="image") { openInlineGallery(entry);return }
        if(entry.kind=="text") { openInlineTextViewer(entry);return }
        val token=java.util.UUID.randomUUID().toString()
        pendingViewerToken=token
        dismissEntryActionLayer(animated=false)
        collapsePanel()
        runCatching { com.floatclip.app.clipboard.ClipViewerActivity.open(this,entry,token,activeSource) }.onFailure {
            pendingViewerToken=null
            expandPanel(false)
            Toast.makeText(this,it.message?:"无法打开预览",Toast.LENGTH_LONG).show()
        }
    }

    private fun openInlineTextViewer(entry: ClipEntry) {
        if(inlineTextViewer != null) return
        dismissEntryActionLayer(animated=false)
        val entries=store.entries().filter { it.kind=="text" &&
            (activeSource.isBlank() || it.sourceDeviceId==activeSource) }
        val viewer=ClipTextViewerView(this,store,
            if(entries.any { it.id==entry.id }) entries else listOf(entry),entry.id,::closeInlineTextViewer)
        val params=WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply { gravity=Gravity.START or Gravity.TOP;windowAnimations=0 }
        if(overlayWindows.add(viewer,params)) {
            inlineTextViewer=viewer;panelView?.dragEnabled=false
        }
    }

    private fun closeInlineTextViewer() {
        val viewer=inlineTextViewer ?: return
        inlineTextViewer=null;overlayWindows.remove(viewer)
        panelView?.dragEnabled=true
        if(panelOpen)renderEntries(false)
    }

    /** A separate upper overlay keeps the existing panel/window and its scroll position alive. */
    private fun openInlineGallery(entry: ClipEntry) {
        if(inlineGallery != null) return
        dismissEntryActionLayer(animated=false)
        val entries=store.entries()
        val candidates=entries.filter {
            it.kind=="image" && when(activeSource) {
                "" -> true
                ClipboardSources.IMAGE_SCOPE -> overlayPreferences.mergeRemoteImages() || it.sourceDeviceId=="local"
                else -> it.sourceDeviceId==activeSource
            }
        }
        val gallery=ClipImageViewerView(this,store,
            if(candidates.any { it.id==entry.id }) candidates else listOf(entry),
            entry.id,::closeInlineGallery)
        val params=WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity=Gravity.START or Gravity.TOP
            softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            windowAnimations=0
        }
        if(overlayWindows.add(gallery,params)) {
            inlineGallery=gallery
            panelView?.dragEnabled=false
        } else gallery.close()
    }

    private fun closeInlineGallery() {
        val gallery=inlineGallery ?: return
        inlineGallery=null
        gallery.close()
        overlayWindows.remove(gallery)
        panelView?.dragEnabled=true
        if(panelOpen) renderEntries(false)
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
        if(entry.kind!="text")return
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
        val imageClip=runCatching { clipboard.currentImageForCapture() }.getOrNull()
        if(imageClip!=null) {
            val event=ClipboardImages.event(imageClip)
            if(event==lastCapturedImage)return
            lastCapturedImage=event
            ClipboardImages.captureAsync(applicationContext,imageClip,store) { result ->
                result.onSuccess { entry ->
                    if(entry!=null) {
                        // Fresh image imports are synchronized in ClipboardImages.importSelected.
                        if(panelOpen&&!panelCollapsing){renderCategoryBar();renderEntries()}
                        notifyHistoryChanged()
                        if(showFeedback)Toast.makeText(this,"已收录图片",Toast.LENGTH_SHORT).show()
                    }
                }.onFailure {
                    lastCapturedImage=""
                    if(showFeedback)Toast.makeText(this,it.message ?: "图片未收录，请在前台重试",Toast.LENGTH_LONG).show()
                }
            }
            return
        }
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
        panelView?.let { panel -> panelParams?.let { applyPanelFocus(panel, it) } }

        scrim.animate().cancel()
        if (panelPinnedMode) {
            scrimParams.flags = scrimParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            scrimParams.flags = scrimParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        runCatching { overlayWindows.update(scrim, scrimParams) }

        button?.apply {
            setImageResource(if (panelPinnedMode) R.drawable.ic_pin_filled else R.drawable.ic_pin)
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
        background = android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(withAlpha(palette.secondaryText, 45)),
            null,
            roundedBackground(Color.WHITE, 12f)
        )
        contentDescription = description
        setOnClickListener { action() }
    }

    private fun collapsePanel() {
        if(stopping || !panelOpen)return
        panelOpen=false
        panelCollapsing=false
        releaseClipboardFocus()
        clipboardCaptureGeneration++
        handler.removeCallbacks(clipboardPollRunnable)
        handler.removeCallbacks(edgeHideRunnable)
        bubbleMotion.cancel()
        bubbleMotionAnimator?.cancel();bubbleMotionAnimator=null
        historyList?.cancelVisibleGestures()
        dismissEntryActionLayer(animated=false)
        showPanelPresentation(false)
        handler.postDelayed(releaseIdlePanel, 15_000L)
    }

    private fun clearMotion(view:View?) {
        view?.animate()?.setListener(null)?.withEndAction(null)?.cancel()
        view?.apply { alpha=1f;scaleX=1f;scaleY=1f;translationX=0f;translationY=0f }
    }

    private var presentationGeneration=0L
    /** Input/ball state commits first. A cancelled visual transition cannot own service visibility. */
    private fun showPanelPresentation(open:Boolean) {
        if(stopping)return
        val token=++presentationGeneration
        val panel=panelView
        val bubble=bubbleView
        val startScaleX=panel?.scaleX?:1f
        val startScaleY=panel?.scaleY?:1f
        val startAlpha=panel?.alpha?:1f
        val startX=panel?.translationX?:0f
        val startY=panel?.translationY?:0f
        clearMotion(panel);clearMotion(bubble)
        panel?.apply { interactionBlocked=!open;isEnabled=open }
        val duration=if(ValueAnimator.areAnimatorsEnabled())overlayPreferences.panelMotionDurationMs(open).toLong() else 0L
        val opacity=1f
        panel?.visibility=View.VISIBLE
        bubbleParams?.let { p -> if(bubble!=null) {
            val hideBubble=open
            p.alpha=if(hideBubble)0f else 1f
            p.flags=if(hideBubble)p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            overlayWindows.update(bubble,p)
        } }
        val pp=panelParams
        if(panel!=null && pp!=null) {
            if(android.os.Build.VERSION.SDK_INT>=31) {
                val blur=open && overlayPreferences.backgroundMode()=="frost" && runCatching {windowManager.isCrossWindowBlurEnabled}.getOrDefault(false)
                pp.flags=if(blur)pp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND else pp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
            }
            pp.alpha=if(open || duration>0)opacity else 0f
            pp.flags=(if(open)pp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() else pp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS.inv()
            overlayWindows.update(panel,pp)
            val ball=bubbleParams
            val sx=((ball?.width?:dp(50)).toFloat()/pp.width.coerceAtLeast(1)).coerceIn(.05f,1f)
            val sy=((ball?.height?:dp(50)).toFloat()/pp.height.coerceAtLeast(1)).coerceIn(.05f,1f)
            val dx=(ball?.x?:pp.x)+(ball?.width?:dp(50))/2f-pp.x-pp.width/2f
            val dy=(ball?.y?:pp.y)+(ball?.height?:dp(50))/2f-pp.y-pp.height/2f
            panel.pivotX=pp.width/2f;panel.pivotY=pp.height/2f
            if(duration>0) {
                panel.scaleX=if(open)sx else startScaleX;panel.scaleY=if(open)sy else startScaleY
                panel.translationX=if(open)dx else startX;panel.translationY=if(open)dy else startY
                panel.alpha=if(open)0f else startAlpha
                panel.animate().scaleX(if(open)1f else sx).scaleY(if(open)1f else sy)
                    .translationX(if(open)0f else dx).translationY(if(open)0f else dy).alpha(if(open)1f else 0f)
                    .setDuration(duration).setInterpolator(overlayPreferences.motionInterpolator())
                    .withEndAction {
                        if(token==presentationGeneration && !open && !panelOpen && panelView===panel) {
                            pp.alpha=0f;overlayWindows.update(panel,pp)
                            panel.visibility=View.INVISIBLE
                            clearMotion(panel)
                            bubbleParams?.let { bp -> if(bubble!=null) {
                                bp.alpha=1f;bp.flags=bp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                                overlayWindows.update(bubble,bp)
                            } }
                            scheduleEdgeHide()
                        }
                    }.start()
            } else if(!open) {
                panel.visibility=View.INVISIBLE
                clearMotion(panel)
            }
        }
        panelScrimView?.let { scrim -> panelScrimParams?.let {
            it.alpha=if(open)1f else 0f
            it.flags=if(open && !panelPinnedMode)it.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() else it.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            overlayWindows.update(scrim,it)
        } }
    }

    private fun percentAlpha(percent: Int): Int = (255f * percent.coerceIn(0, 100) / 100f).toInt().coerceIn(0, 255)

    private fun accentColor(palette: OverlayPalette): Int =
        FloatClipAccent.color(overlayPreferences.accentTheme(), palette.isDark)

    private fun selectedTabBackground(palette: OverlayPalette): Int {
        val accent=accentColor(palette)
        if(!palette.isDark) return withAlpha(accent, 75)
        val base=palette.panelBackground
        fun mix(a:Int,b:Int)=(a*.60f+b*.40f).toInt().coerceIn(0,255)
        return Color.argb(248,mix(Color.red(base),Color.red(accent)),
            mix(Color.green(base),Color.green(accent)),mix(Color.blue(base),Color.blue(accent)))
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    private fun circleBackground(color: Int, palette: OverlayPalette): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        if (!palette.isDark) setStroke(dp(1), Color.argb(38, 0, 0, 0))
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
        const val ACTION_RESTORE_OVERLAY = "com.floatclip.app.action.RESTORE_OVERLAY"
        const val ACTION_REFRESH_APPEARANCE = "com.floatclip.app.action.REFRESH_APPEARANCE"
        const val ACTION_REPOSITION_PANEL = "com.floatclip.app.action.REPOSITION_PANEL"
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
