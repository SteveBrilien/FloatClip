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
import java.lang.ref.WeakReference
import kotlin.math.abs

/** Own the front's complete touch stream; covered action buttons never receive DOWN. */
class SwipeRevealRow(context: Context) : FrameLayout(context) {
    private val config = ViewConfiguration.get(context)
    private var front: View? = null
    private var actions: View? = null
    private var actionWidth = 0
    private var downX = 0f
    private var downY = 0f
    private var startTranslation = 0f
    private var horizontal = false
    private var vertical = false
    private var opened = false
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
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (isAttachedToWindow) performClick()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onDoubleTap?.invoke()
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            if (!horizontal && !vertical && isAttachedToWindow) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onLongPress?.invoke()
            }
        }
    })

    fun bind(frontView: View, actionsView: View, revealWidthPx: Int) {
        removeAllViews()
        front = frontView
        actions = actionsView
        actionWidth = revealWidthPx
        actionsView.visibility = INVISIBLE
        addView(actionsView, LayoutParams(revealWidthPx, LayoutParams.MATCH_PARENT, Gravity.END))
        addView(frontView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        clipChildren = true
    }

    private fun translate(value: Float) {
        front?.translationX = value
        val revealed = (-value).toInt().coerceIn(0, actionWidth)
        actions?.apply {
            visibility = if (revealed == 0) INVISIBLE else VISIBLE
            // Clip even while moving: translucent front must never reveal covered buttons.
            clipBounds = Rect(actionWidth - revealed, 0, actionWidth, height)
        }
    }

    fun close(animated: Boolean = true) = settle(false, animated)

    private fun settle(open: Boolean, animated: Boolean = true) {
        removeCallbacks(autoClose)
        settling?.cancel()
        opened = open
        if (open) {
            active?.get()?.takeIf { it !== this }?.close()
            active = WeakReference(this)
            postDelayed(autoClose, 4500L)
        } else if (active?.get() === this) active = null
        val target = if (open) -actionWidth.toFloat() else 0f
        if (!animated) { translate(target); return }
        settling = ValueAnimator.ofFloat(front?.translationX ?: 0f, target).apply {
            duration = 180L
            addUpdateListener { translate(it.animatedValue as Float) }
            start()
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return false
        active?.get()?.takeIf { it !== this }?.close()
        val pass = touchPassthrough
        if (pass != null && !opened) {
            val pos = IntArray(2)
            pass.getLocationOnScreen(pos)
            if (event.rawX >= pos[0] && event.rawX < pos[0] + pass.width &&
                event.rawY >= pos[1] && event.rawY < pos[1] + pass.height) return false
        }
        // Only a fully revealed action can handle its own gesture.
        if (opened && event.x >= width + (front?.translationX ?: 0f)) {
            removeCallbacks(autoClose)
            postDelayed(autoClose, 4500L)
            return false
        }
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                settling?.cancel()
                removeCallbacks(autoClose)
                downX = event.x
                downY = event.y
                startTranslation = front?.translationX ?: 0f
                beganOpen = opened || startTranslation < -1f
                horizontal = false
                vertical = false
                tracker?.recycle()
                tracker = VelocityTracker.obtain()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!horizontal && !vertical && abs(dx) > config.scaledTouchSlop && abs(dx) > abs(dy) * 1.2f) {
                    horizontal = true
                    cancelDetector(event)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    active?.get()?.takeIf { it !== this }?.close()
                } else if (!horizontal && abs(dy) > config.scaledTouchSlop && abs(dy) > abs(dx)) {
                    vertical = true
                    cancelDetector(event)
                }
                if (horizontal) translate((startTranslation + dx).coerceIn(-actionWidth.toFloat(), 0f))
            }
        }
        tracker?.addMovement(event)
        if (!horizontal && !vertical && !beganOpen) detector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (horizontal) {
                tracker?.computeCurrentVelocity(1000)
                val vx = tracker?.xVelocity ?: 0f
                val open = event.actionMasked != MotionEvent.ACTION_CANCEL && when {
                    vx < -config.scaledMinimumFlingVelocity -> true
                    vx > config.scaledMinimumFlingVelocity -> false
                    else -> (front?.translationX ?: 0f) < -actionWidth * 0.45f
                }
                settle(open)
            } else if (beganOpen) close()
            tracker?.recycle()
            tracker = null
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

    override fun onDetachedFromWindow() {
        removeCallbacks(autoClose)
        settling?.cancel()
        val cancel = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        cancelDetector(cancel)
        cancel.recycle()
        tracker?.recycle()
        tracker = null
        if (active?.get() === this) active = null
        super.onDetachedFromWindow()
    }

    companion object { private var active: WeakReference<SwipeRevealRow>? = null }
}
