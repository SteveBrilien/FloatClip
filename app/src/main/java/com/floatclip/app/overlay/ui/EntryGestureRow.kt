package com.floatclip.app.overlay.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.floatclip.app.prefs.OverlayPreferences
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * Owns the full gesture stream.
 * Left swipe reveals trailing actions; right swipe reveals the independent protection action.
 */
class EntryGestureRow(context: Context) : FrameLayout(context) {
    private val config = ViewConfiguration.get(context)
    private val appearance = OverlayPreferences(context)
    private var front: View? = null
    private var trailingActions: View? = null
    private var leadingActions: View? = null
    private var trailingWidth = 0
    private var leadingWidth = 0
    private var downX = 0f
    private var downY = 0f
    private var startTranslation = 0f
    private var horizontal = false
    private var vertical = false
    private var openedSide = 0 // -1 trailing, +1 leading, 0 closed
    private var beganOpen = false
    private var tracker: VelocityTracker? = null
    private var settling: ValueAnimator? = null
    var touchPassthrough: View? = null
    var onSingleTap: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null
    private val autoClose = Runnable { close() }
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (isAttachedToWindow) performClick()
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            if (!horizontal && !vertical && isAttachedToWindow) {
                press(false)
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onLongPress?.invoke()
            }
        }
    })

    init {
        detector.setOnDoubleTapListener(null)
        isClickable=true; isFocusable=true; isLongClickable=true
        setOnLongClickListener { onLongPress?.invoke(); true }
    }

    private fun press(down: Boolean) {
        front?.animate()?.cancel()
        val scale=if(down && ValueAnimator.areAnimatorsEnabled()) appearance.cardPressScalePercent()/100f else 1f
        val duration=if(down) (appearance.transitionDurationMs()*0.38f).toLong().coerceIn(55,180)
            else (appearance.transitionDurationMs()*0.68f).toLong().coerceIn(90,260)
        front?.animate()?.scaleX(scale)?.scaleY(scale)?.setDuration(duration)?.start()
    }

    fun bind(
        frontView: View,
        actionsView: View? = null,
        revealWidthPx: Int = 0,
        leadingActionsView: View? = null,
        leadingRevealWidthPx: Int = 0,
    ) {
        removeAllViews()
        front = frontView
        trailingActions = actionsView
        leadingActions = leadingActionsView
        trailingWidth = revealWidthPx
        leadingWidth = leadingRevealWidthPx
        actionsView?.visibility = INVISIBLE
        leadingActionsView?.visibility = INVISIBLE
        if (leadingActionsView != null) addView(leadingActionsView, LayoutParams(leadingRevealWidthPx, LayoutParams.MATCH_PARENT, Gravity.START))
        if (actionsView != null) addView(actionsView, LayoutParams(revealWidthPx, LayoutParams.MATCH_PARENT, Gravity.END))
        addView(frontView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        clipChildren = true
    }

    private fun translate(value: Float) {
        front?.translationX = value
        val trailingRevealed = (-value).toInt().coerceIn(0, trailingWidth)
        trailingActions?.apply {
            visibility = if (trailingRevealed == 0) INVISIBLE else VISIBLE
            clipBounds = Rect(trailingWidth - trailingRevealed, 0, trailingWidth, height)
        }
        val leadingRevealed = value.toInt().coerceIn(0, leadingWidth)
        leadingActions?.apply {
            visibility = if (leadingRevealed == 0) INVISIBLE else VISIBLE
            clipBounds = Rect(0, 0, leadingRevealed, height)
        }
    }

    fun close(animated: Boolean = true) = settle(0, animated)

    private fun settle(side: Int, animated: Boolean = true) {
        removeCallbacks(autoClose)
        settling?.cancel()
        openedSide = side
        if (side != 0) {
            active?.get()?.takeIf { it !== this }?.close()
            active = WeakReference(this)
        } else if (active?.get() === this) active = null
        val target = when(side) {
            -1 -> -trailingWidth.toFloat()
            1 -> leadingWidth.toFloat()
            else -> 0f
        }
        if (!animated) { translate(target); return }
        settling = ValueAnimator.ofFloat(front?.translationX ?: 0f, target).apply {
            duration = if(ValueAnimator.areAnimatorsEnabled()) appearance.transitionDurationMs().toLong().coerceIn(120,480) else 0L
            addUpdateListener { translate(it.animatedValue as Float) }
            start()
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return false
        active?.get()?.takeIf { it !== this }?.close()
        val pass = touchPassthrough
        if (pass != null && openedSide == 0) {
            val pos = IntArray(2)
            pass.getLocationOnScreen(pos)
            if (event.rawX >= pos[0] && event.rawX < pos[0] + pass.width &&
                event.rawY >= pos[1] && event.rawY < pos[1] + pass.height) return false
        }
        val tx=front?.translationX ?: 0f
        if (openedSide < 0 && event.x >= width + tx) { removeCallbacks(autoClose); return false }
        if (openedSide > 0 && event.x <= tx) { removeCallbacks(autoClose); return false }
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                settling?.cancel()
                removeCallbacks(autoClose)
                downX = event.x; downY = event.y
                startTranslation = front?.translationX ?: 0f
                beganOpen = openedSide != 0 || abs(startTranslation) > 1f
                if(!beganOpen) press(true)
                horizontal = false; vertical = false
                tracker?.recycle(); tracker = VelocityTracker.obtain()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val canMove=(dx<0 && trailingWidth>0)||(dx>0 && leadingWidth>0)||beganOpen
                if (canMove && !horizontal && !vertical && abs(dx) > config.scaledTouchSlop && abs(dx) > abs(dy) * 1.2f) {
                    horizontal = true
                    press(false)
                    cancelDetector(event)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    active?.get()?.takeIf { it !== this }?.close()
                } else if (!horizontal && abs(dy) > config.scaledTouchSlop && abs(dy) > abs(dx)) {
                    vertical = true; press(false); cancelDetector(event)
                }
                if (horizontal) {
                    // A reverse swipe closes the open side before a new gesture opens the other.
                    val min = if (startTranslation > 1f) 0f else -trailingWidth.toFloat()
                    val max = if (startTranslation < -1f) 0f else leadingWidth.toFloat()
                    translate((startTranslation + dx).coerceIn(min, max))
                }
            }
        }
        tracker?.addMovement(event)
        if (!horizontal && !vertical && !beganOpen) detector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            press(false)
            if (horizontal) {
                tracker?.computeCurrentVelocity(1000)
                val vx = tracker?.xVelocity ?: 0f
                val tx=front?.translationX ?: 0f
                val side = if(event.actionMasked==MotionEvent.ACTION_CANCEL) 0 else when {
                    vx < -config.scaledMinimumFlingVelocity -> if (tx < -1f && trailingWidth > 0) -1 else 0
                    vx > config.scaledMinimumFlingVelocity -> if (tx > 1f && leadingWidth > 0) 1 else 0
                    tx < -trailingWidth*0.45f && trailingWidth>0 -> -1
                    tx > leadingWidth*0.45f && leadingWidth>0 -> 1
                    else -> 0
                }
                settle(side)
            } else if (beganOpen) close()
            tracker?.recycle(); tracker = null
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun cancelDetector(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event)
        cancel.action = MotionEvent.ACTION_CANCEL
        detector.onTouchEvent(cancel)
        cancel.recycle()
    }

    override fun performClick(): Boolean {
        super.performClick()
        onSingleTap?.invoke()
        return true
    }

    fun cancelPendingGestures() {
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        cancelDetector(event); event.recycle()
        close(false); press(false)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(autoClose); settling?.cancel()
        val cancel = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        cancelDetector(cancel); cancel.recycle()
        tracker?.recycle(); tracker = null
        if (active?.get() === this) active = null
        super.onDetachedFromWindow()
    }

    companion object {
        private var active: WeakReference<EntryGestureRow>? = null
        fun closeOutside(rawX: Float,rawY: Float) {
            val row=active?.get() ?: return
            val bounds=Rect();row.getGlobalVisibleRect(bounds)
            if(!bounds.contains(rawX.toInt(),rawY.toInt())) row.close()
        }
    }
}
