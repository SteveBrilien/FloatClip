package com.floatclip.app.overlay.ui

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

class BorderDragFrameLayout(context: Context) : FrameLayout(context) {
    var interactionBlocked = false
    var dragEnabled = true
    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        if (interactionBlocked) true else super.dispatchTouchEvent(event)

    var borderDragPx: Int = 0
    var topDragHeightPx: Int = 0
    var topActionReservePx: Int = 0
    var listener: DragListener? = null
    private var dragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var dragDownRawX = 0f
    private var dragDownRawY = 0f
    private var dragMoved = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!dragEnabled) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            dragging = isDragZone(event.x, event.y)
            if (dragging) {
                dragDownRawX = event.rawX
                dragDownRawY = event.rawY
                dragMoved = false
                listener?.onDragStart(event.rawX, event.rawY)
                return true
            }
        }
        return dragging || super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.rawX - dragDownRawX) > touchSlop || abs(event.rawY - dragDownRawY) > touchSlop) {
                    dragMoved = true
                }
                listener?.onDragMove(event.rawX, event.rawY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP && !dragMoved) performClick()
                listener?.onDragEnd(event.rawX, event.rawY, event.actionMasked == MotionEvent.ACTION_CANCEL)
                dragging = false
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun isDragZone(x: Float, y: Float): Boolean {
        if (width <= 0 || height <= 0) return false
        if (x <= borderDragPx || x >= width - borderDragPx || y >= height - borderDragPx) return true
        return y <= topDragHeightPx && x < width - topActionReservePx
    }

    interface DragListener {
        fun onDragStart(rawX: Float, rawY: Float)
        fun onDragMove(rawX: Float, rawY: Float)
        fun onDragEnd(rawX: Float, rawY: Float, cancelled: Boolean)
    }
}
