package com.floatclip.app.overlay.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/** One owner for tap / double-tap / long-press; no hidden swipe actions. */
class EntryGestureRow(context: Context) : FrameLayout(context) {
    var touchPassthrough: View? = null
    var onSingleTap: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null
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
            if (isAttachedToWindow) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onLongPress?.invoke()
            }
        }
    })

    fun bind(front: View) {
        removeAllViews()
        addView(front, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return false
        touchPassthrough?.let {
            val pos = IntArray(2)
            it.getLocationOnScreen(pos)
            if (event.rawX >= pos[0] && event.rawX < pos[0] + it.width &&
                event.rawY >= pos[1] && event.rawY < pos[1] + it.height) return false
        }
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        return true
    }

    fun cancelPendingGestures() {
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        detector.onTouchEvent(event)
        event.recycle()
    }

    override fun performClick(): Boolean {
        super.performClick()
        onSingleTap?.invoke()
        return true
    }

    override fun onDetachedFromWindow() {
        cancelPendingGestures()
        super.onDetachedFromWindow()
    }
}
