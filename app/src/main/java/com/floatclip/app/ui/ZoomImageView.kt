package com.floatclip.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Shared by clipboard, overlay and file previews. Zoom affects pixels, never surrounding controls. */
class ZoomImageView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private var zoom = 1f
    private var minimum = 1f
    private var maximum = 8f
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var pointer = -1
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomAt(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { performClick(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoomAt(if (zoom > minimum * 1.1f) minimum else min(maximum, minimum * 3f), e.x, e.y)
            return true
        }
    })
    init { isFocusable = true; contentDescription = "图片预览，双指缩放，拖动查看，双击切换缩放" }
    fun setImageBitmap(value: Bitmap) { bitmap = value; reset(); invalidate() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { reset() }
    private fun reset() {
        val image = bitmap ?: return
        if (width <= 0 || height <= 0) return
        minimum = min(width.toFloat() / image.width, height.toFloat() / image.height)
        maximum = max(minimum * 8, 4f)
        // Long screenshots open at readable width and start at the top; zooming out fits the whole image.
        zoom = if (image.height > image.width * 2.2f) width.toFloat() / image.width else minimum
        zoom = zoom.coerceIn(minimum, maximum)
        offsetX = (width - image.width * zoom) / 2; offsetY = if (image.height * zoom > height) 0f else (height - image.height * zoom) / 2
        constrain()
    }
    private fun zoomAt(target: Float, focusX: Float, focusY: Float) {
        val next = target.coerceIn(minimum, maximum); val ratio = next / zoom
        offsetX = focusX - (focusX - offsetX) * ratio; offsetY = focusY - (focusY - offsetY) * ratio; zoom = next
        constrain(); invalidate()
    }
    private fun constrain() {
        val image = bitmap ?: return
        val w = image.width * zoom; val h = image.height * zoom
        offsetX = if (w <= width) (width - w) / 2 else offsetX.coerceIn(width - w, 0f)
        offsetY = if (h <= height) (height - h) / 2 else offsetY.coerceIn(height - h, 0f)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bitmap?.let { matrix.setScale(zoom, zoom); matrix.postTranslate(offsetX, offsetY); canvas.drawBitmap(it, matrix, paint) }
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    @SuppressLint("ClickableViewAccessibility") // GestureDetector routes recognized taps through performClick().
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scale.onTouchEvent(event); gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pointer = event.getPointerId(0); lastX = event.x; lastY = event.y }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointer)
                if (index >= 0) {
                    val px = event.getX(index); val py = event.getY(index)
                    if (!scale.isInProgress && event.pointerCount == 1) { offsetX += px - lastX; offsetY += py - lastY; constrain(); invalidate() }
                    lastX = px; lastY = py
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val next = if (event.actionIndex == 0) 1 else 0
                pointer = event.getPointerId(next); lastX = event.getX(next); lastY = event.getY(next)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { pointer = -1; parent?.requestDisallowInterceptTouchEvent(false) }
        }
        return true
    }
}
