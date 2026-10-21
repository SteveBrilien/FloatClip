package com.floatclip.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.view.View
import com.floatclip.app.prefs.OverlayPreferences
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.roundToInt

/**
 * Small Fluent-style switch used by FloatClip instead of OEM/native switch skins.
 * Geometry is fixed to the product's 4 dp grid and remains stable across ROMs.
 */
class FluentSwitch(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var checked = false
    private var position = 0f
    private var accent = Color.rgb(36, 93, 206)
    private var offTrack = Color.rgb(223, 231, 242)
    private var offThumb = Color.rgb(89, 108, 133)
    private var onToggle: ((Boolean) -> Unit)? = null
    private var animator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            val next = !checked
            animateTo(next)
            onToggle?.invoke(next)
        }
    }

    fun bind(
        value: Boolean,
        accentColor: Int,
        offTrackColor: Int,
        offThumbColor: Int,
        listener: (Boolean) -> Unit,
    ) {
        animator?.cancel()
        checked = value
        position = if (value) 1f else 0f
        accent = accentColor
        offTrack = offTrackColor
        offThumb = offThumbColor
        onToggle = listener
        invalidate()
    }

    private fun animateTo(value: Boolean) {
        animator?.cancel()
        val start = position
        val end = if (value) 1f else 0f
        checked = value
        animator = ValueAnimator.ofFloat(start, end).apply {
            duration = OverlayPreferences(context).switchAnimationMs().toLong()
            addUpdateListener {
                position = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(44), dp(28))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val trackLeft = dp(1).toFloat()
        val trackTop = dp(3).toFloat()
        val trackRight = width - dp(1).toFloat()
        val trackBottom = height - dp(3).toFloat()
        val radius = (trackBottom - trackTop) / 2f

        paint.style = Paint.Style.FILL
        paint.color = blend(offTrack, blend(offTrack, accent, 0.38f), position)
        canvas.drawRoundRect(trackLeft, trackTop, trackRight, trackBottom, radius, radius, paint)

        val thumbRadius = dp(9).toFloat()
        val startX = trackLeft + radius
        val endX = trackRight - radius
        val cx = startX + (endX - startX) * position
        paint.color = blend(offThumb, accent, position)
        canvas.drawCircle(cx, height / 2f, thumbRadius, paint)

        // Fine inner highlight keeps the thumb readable on dark surfaces.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1).toFloat()
        paint.color = withAlpha(Color.WHITE, if (checked) 150 else 110)
        canvas.drawCircle(cx, height / 2f, thumbRadius - dp(0.5f), paint)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.Switch::class.java.name
        info.isCheckable = true
        info.isChecked = checked
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).roundToInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).roundToInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).roundToInt(),
    )
}
