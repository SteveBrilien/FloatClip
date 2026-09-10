package com.floatclip.app.overlay.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.hypot

class SwipeRevealRow(context: Context) : FrameLayout(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressSlop = touchSlop * 1.8f
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val handler = Handler(Looper.getMainLooper())
    private var front: View? = null
    private var actionWidth = 0
    private var downX = 0f
    private var downY = 0f
    private var startTranslation = 0f
    private var horizontal = false
    private var tracker: VelocityTracker? = null
    private var opened = false
    private var longPressTriggered = false
    private var longPressScheduled = false

    var onSingleTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null

    private val longPressRunnable = Runnable {
        longPressScheduled = false
        if (horizontal || longPressTriggered || opened) return@Runnable
        longPressTriggered = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongPress?.invoke()
    }

    fun bind(frontView: View, actionsView: View, revealWidthPx: Int) {
        removeAllViews()
        actionWidth = revealWidthPx
        addView(
            actionsView,
            LayoutParams(revealWidthPx, LayoutParams.MATCH_PARENT, Gravity.END),
        )
        addView(frontView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        front = frontView
    }

    fun close(animated: Boolean = true) {
        opened = false
        val view = front ?: return
        if (animated) view.animate().translationX(0f).setDuration(190L).start() else view.translationX = 0f
    }

    private fun beginGesture(event: MotionEvent) {
        downX = event.x
        downY = event.y
        startTranslation = front?.translationX ?: 0f
        horizontal = false
        longPressTriggered = false
        tracker?.recycle()
        tracker = VelocityTracker.obtain().also { it.addMovement(event) }
        scheduleLongPress()
    }

    private fun scheduleLongPress() {
        cancelScheduledLongPress()
        longPressScheduled = true
        handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
    }

    private fun cancelScheduledLongPress() {
        if (longPressScheduled) handler.removeCallbacks(longPressRunnable)
        longPressScheduled = false
    }

    private fun movedBeyondLongPressSlop(event: MotionEvent): Boolean =
        hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) > longPressSlop

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (opened && event.actionMasked == MotionEvent.ACTION_DOWN && event.x >= width - actionWidth) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginGesture(event)
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(event)
                val dx = event.x - downX
                val dy = event.y - downY
                if (movedBeyondLongPressSlop(event)) cancelScheduledLongPress()
                if (!horizontal && abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.15f) {
                    horizontal = true
                    cancelScheduledLongPress()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.addMovement(event)
                cancelScheduledLongPress()
            }
        }
        return horizontal
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && tracker == null) beginGesture(event)
        tracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (movedBeyondLongPressSlop(event)) cancelScheduledLongPress()
                if (horizontal) {
                    val dx = event.x - downX
                    front?.translationX = (startTranslation + dx).coerceIn(-actionWidth.toFloat(), 0f)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelScheduledLongPress()
                if (horizontal) {
                    tracker?.computeCurrentVelocity(1000)
                    val vx = tracker?.xVelocity ?: 0f
                    val current = front?.translationX ?: 0f
                    opened = when {
                        event.actionMasked == MotionEvent.ACTION_CANCEL -> false
                        vx < -minFling -> true
                        vx > minFling -> false
                        else -> current < -actionWidth * 0.42f
                    }
                    front?.animate()
                        ?.translationX(if (opened) -actionWidth.toFloat() else 0f)
                        ?.setDuration(210L)
                        ?.start()
                    horizontal = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                } else if (
                    event.actionMasked == MotionEvent.ACTION_UP &&
                    !longPressTriggered &&
                    !movedBeyondLongPressSlop(event)
                ) {
                    performClick()
                }
                tracker?.recycle()
                tracker = null
                longPressTriggered = false
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (opened) close() else onSingleTap?.invoke()
        return true
    }

    override fun onDetachedFromWindow() {
        cancelScheduledLongPress()
        tracker?.recycle()
        tracker = null
        super.onDetachedFromWindow()
    }

    companion object {
        // Slightly shorter than Android's default 500 ms while still avoiding accidental activation.
        private const val LONG_PRESS_MS = 360L
    }
}
