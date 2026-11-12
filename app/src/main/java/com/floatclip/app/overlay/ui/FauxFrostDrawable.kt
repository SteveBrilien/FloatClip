package com.floatclip.app.overlay.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * No screen capture or hidden OEM APIs. A reusable 48x48 texture, translucent tint and subtle
 * local contrast approximate frosted glass on Android 11 without claiming real background blur.
 * BitmapShader repeats on the GPU; painting cost is independent of the window's pixel count.
 */
internal class FauxFrostDrawable(
    color: Int,
    private val cornerRadius: Float,
    strengthPercent: Int,
    private val borderColor: Int? = null,
    private val strokePx: Float = 1f,
) : Drawable() {
    private val shape = Path()
    private val baseColor = color
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val noisePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(makeTexture(strengthPercent), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        this.color = borderColor ?: Color.TRANSPARENT
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        shape.reset()
        shape.addRoundRect(RectF(bounds), cornerRadius, cornerRadius, Path.Direction.CW)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(shape, basePaint)
        val save = canvas.save()
        canvas.clipPath(shape)
        canvas.drawRect(bounds, noisePaint)
        canvas.restoreToCount(save)
        if(borderColor != null) canvas.drawPath(shape, edgePaint)
    }

    override fun setAlpha(alpha: Int) {
        basePaint.alpha = (Color.alpha(baseColor) * alpha.coerceIn(0, 255) / 255)
        noisePaint.alpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        basePaint.colorFilter = colorFilter
        noisePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, cornerRadius)
        outline.alpha = Color.alpha(basePaint.color) / 255f
    }

    companion object {
        private fun makeTexture(strengthPercent: Int): Bitmap {
            val strength = strengthPercent.coerceIn(0, 100)
            val texture = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
            var state = 0x49321
            for (y in 0 until 48) for (x in 0 until 48) {
                state = state * 1664525 + 1013904223
                val sample = state ushr 24
                val white = (sample and 1) == 0
                val alpha = 2 + strength * (3 + sample % 12) / 100
                texture.setPixel(x, y, Color.argb(alpha, if(white)255 else 0,
                    if(white)255 else 0, if(white)255 else 0))
            }
            return texture
        }
    }
}
