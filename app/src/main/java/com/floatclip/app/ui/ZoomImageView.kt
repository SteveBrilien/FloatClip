package com.floatclip.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Shared by clipboard and file previews; all gestures use original-image coordinates. */
class ZoomImageView(context: Context, private var fillHeight: Boolean = false) : View(context) {
    var onHorizontalPage: ((Int) -> Boolean)? = null
    var onVerticalPage: ((Int) -> Boolean)? = null
    var onViewportChanged: ((Rect, Float) -> Unit)? = null
    private var bitmap: Bitmap? = null
    private var originalWidth = 0
    private var originalHeight = 0
    private var detail: Bitmap? = null
    private var detailRect = Rect()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val imageDestination = RectF()
    private val detailDestination = RectF()
    private var zoom = 1f
    private var minimum = 1f
    private var baseZoom = 1f
    private var categoryEdgesAtDown = 0
    private var maximum = 8f
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var pointer = -1
    private var downX = 0f
    private var downY = 0f
    private var hadMultiplePointers = false
    private var viewportPending = false
    private val updateViewport = Runnable {
        viewportPending = false
        if (originalWidth > 0 && originalHeight > 0 && width > 0 && height > 0) {
            onViewportChanged?.invoke(
                com.floatclip.app.clipboard.ClipImageSource.viewport(
                    originalWidth, originalHeight, width, height, zoom, offsetX, offsetY), zoom)
        }
    }
    private fun viewportChanged() {
        if (onViewportChanged != null && !viewportPending) {
            viewportPending = true
            postDelayed(updateViewport, 60)
        }
    }
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomAt(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { performClick(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoomAt(if (zoom > minimum * 1.1f) minimum else min(maximum, minimum * 3f), e.x, e.y)
            return true
        }
    })
    init { isFocusable = true; contentDescription = "图片预览，双指缩放，拖动查看，双击切换缩放" }

    /** Update only after decoding the actual original, never trust imported metadata alone. */
    fun useDecodedDimensions(sourceWidth:Int,sourceHeight:Int) {
        if(sourceWidth <= 0 || sourceHeight <= 0)return
        // A portrait-orientation flag does not imply that height-fit will fit the viewport.
        // Always select the smaller width/height scale for the initial view.
        fillHeight=false
        originalWidth=sourceWidth;originalHeight=sourceHeight
        reset();invalidate()
    }
    fun setImageBitmap(value: Bitmap) = setImageBitmap(value, value.width, value.height)
    fun setImageBitmap(value: Bitmap, sourceWidth: Int, sourceHeight: Int) {
        val reset = originalWidth != sourceWidth || originalHeight != sourceHeight
        bitmap = value
        originalWidth = sourceWidth
        originalHeight = sourceHeight
        if (reset) { detail = null; reset() }
        invalidate()
    }
    fun setImageDimensions(sourceWidth: Int, sourceHeight: Int) {
        originalWidth = sourceWidth; originalHeight = sourceHeight
        reset(); invalidate()
    }
    fun setImageRegion(value: Bitmap, rect: Rect) {
        detail = value; detailRect = Rect(rect); invalidate()
    }
    fun clearImages() { bitmap = null; detail = null; onViewportChanged = null; removeCallbacks(updateViewport); viewportPending = false }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { reset() }
    private fun reset() {
        if (originalWidth <= 0 || originalHeight <= 0 || width <= 0 || height <= 0) return
        minimum = min(width.toFloat() / originalWidth, height.toFloat() / originalHeight)
        maximum = max(minimum * 8, 4f)
        baseZoom = minimum // fit-center, never crop either dimension by default
        zoom = baseZoom
        offsetX = (width - originalWidth * zoom) / 2
        offsetY = if (originalHeight * zoom > height) 0f else (height - originalHeight * zoom) / 2
        constrain()
    }
    private fun zoomAt(target: Float, focusX: Float, focusY: Float) {
        val next = target.coerceIn(minimum, maximum); val ratio = next / zoom
        offsetX = focusX - (focusX - offsetX) * ratio; offsetY = focusY - (focusY - offsetY) * ratio; zoom = next
        constrain(); invalidate()
    }
    private fun constrain() {
        if (originalWidth <= 0 || originalHeight <= 0) return
        val w = originalWidth * zoom; val h = originalHeight * zoom
        offsetX = if (w <= width) (width - w) / 2 else offsetX.coerceIn(width - w, 0f)
        offsetY = if (h <= height) (height - h) / 2 else offsetY.coerceIn(height - h, 0f)
        viewportChanged()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        matrix.setScale(zoom, zoom); matrix.postTranslate(offsetX, offsetY)
        val saved = canvas.save()
        canvas.concat(matrix)
        imageDestination.set(0f, 0f, originalWidth.toFloat(), originalHeight.toFloat())
        detailDestination.set(detailRect)
        bitmap?.let { canvas.drawBitmap(it, null, imageDestination, paint) }
        detail?.let { canvas.drawBitmap(it, null, detailDestination, paint) }
        canvas.restoreToCount(saved)
    }
    override fun onDetachedFromWindow() {
        removeCallbacks(updateViewport); viewportPending = false
        super.onDetachedFromWindow()
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scale.onTouchEvent(event); gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                hadMultiplePointers = false
                pointer = event.getPointerId(0)
                lastX = event.x; lastY = event.y; downX = event.x; downY = event.y
                val edge = resources.displayMetrics.density * 4f
                val bottom = (height - originalHeight * zoom).coerceAtMost(0f)
                categoryEdgesAtDown = if (originalHeight > 0 && zoom <= baseZoom * 1.03f) {
                    (if (offsetY >= -edge) 1 else 0) or (if (offsetY <= bottom + edge) 2 else 0)
                } else 0
            }
            MotionEvent.ACTION_POINTER_DOWN -> hadMultiplePointers = true
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointer)
                if (index >= 0) {
                    val px = event.getX(index); val py = event.getY(index)
                    if (!scale.isInProgress && event.pointerCount == 1) {
                        offsetX += px - lastX
                        offsetY += py - lastY; constrain(); invalidate()
                    }
                    lastX = px; lastY = py
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val next = if (event.actionIndex == 0) 1 else 0
                pointer = event.getPointerId(next); lastX = event.getX(next); lastY = event.getY(next)
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX; val dy = event.y - downY
                val threshold = max(56f * resources.displayMetrics.density, width * 0.12f)
                val horizontalFits = originalWidth > 0 && originalWidth * zoom <= width + 4f * resources.displayMetrics.density
                if (!hadMultiplePointers && !scale.isInProgress) {
                    val x = kotlin.math.abs(dx)
                    val y = kotlin.math.abs(dy)
                    val verticalEdge = if (dy >= 0f) 1 else 2
                    if (horizontalFits && zoom <= baseZoom * 1.03f && x >= threshold && x > y * 1.35f) {
                        onHorizontalPage?.invoke(if (dx < 0f) 1 else -1)
                    } else if (categoryEdgesAtDown and verticalEdge != 0 && y >= threshold && y > x * 1.35f) {
                        onVerticalPage?.invoke(if (dy < 0f) 1 else -1)
                    }
                }
                pointer = -1
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> { pointer = -1; parent?.requestDisallowInterceptTouchEvent(false) }
        }
        return true
    }
}
