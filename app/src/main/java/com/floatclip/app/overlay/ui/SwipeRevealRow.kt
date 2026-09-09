package com.floatclip.app.overlay.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

class SwipeRevealRow(context: Context) : FrameLayout(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val gesture = GestureDetector(context, GestureListener())
    private var front: View? = null
    private var actionWidth = 0
    private var downX = 0f
    private var downY = 0f
    private var startTranslation = 0f
    private var horizontal = false
    private var tracker: VelocityTracker? = null
    private var opened = false

    var onSingleTap: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null

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

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (opened && event.actionMasked == MotionEvent.ACTION_DOWN && event.x >= width - actionWidth) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                startTranslation = front?.translationX ?: 0f
                horizontal = false
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(event) }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(event)
                val dx = event.x - downX
                val dy = event.y - downY
                if (!horizontal && abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.15f) {
                    horizontal = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracker?.addMovement(event)
        }
        return horizontal
    }

    @SuppressLint("ClickableViewAccessibility") // confirmed single taps call performClick via GestureDetector
    override fun onTouchEvent(event: MotionEvent): Boolean {
        tracker?.addMovement(event)
        if (!horizontal) gesture.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (horizontal) {
                val dx = event.x - downX
                front?.translationX = (startTranslation + dx).coerceIn(-actionWidth.toFloat(), 0f)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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
                }
                tracker?.recycle()
                tracker = null
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (opened) close() else onSingleTap?.invoke()
        return true
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean = performClick()

        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleTap?.invoke()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            onLongPress?.invoke()
        }
    }
}
