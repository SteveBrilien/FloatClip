package com.floatclip.probe

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Shared visual tokens. Geometry uses a 4 dp grid; controls have a 48 dp minimum target. */
internal object ShareDesign {
    const val PAGE = 16
    const val CARD = 16
    const val CONTROL = 12
    const val TARGET = 48
    data class Palette(val bg: Int, val card: Int, val text: Int, val muted: Int,
        val line: Int, val blue: Int, val tint: Int, val primary: Int)
    private fun color(value: String) = Color.parseColor(value)
    fun palette(dark: Boolean) = if (dark) Palette(
        color("#0C1422"), color("#162235"), color("#EDF3FC"), color("#A9B8CD"),
        color("#2D3C52"), color("#8DBBFF"), color("#243B5A"), color("#4B88F0")
    ) else Palette(
        color("#F4F7FC"), Color.WHITE, color("#19283F"), color("#596C85"),
        color("#DFE7F2"), color("#245DCE"), color("#EBF2FF"), color("#245DCE")
    )
}

internal enum class ShareGlyph { SHARE, DEVICES, SETTINGS, FILE, SHIELD, SYSTEM, SUN, MOON, CHEVRON }

/** Small, consistent vector icons without bitmap assets or additional UI dependencies. */
internal class ShareIcon(private val glyph: ShareGlyph, color: Int) : Drawable() {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = canvas.drawLine(x,y,x2,y2,pen)
        fun path(vararg points: Float) {
            val path=Path(); path.moveTo(points[0],points[1])
            for (i in 2 until points.size step 2) path.lineTo(points[i],points[i+1])
            canvas.drawPath(path,pen)
        }
        when (glyph) {
            ShareGlyph.SHARE -> {
                canvas.drawRoundRect(5f,5f,18f,21f,2f,2f,pen)
                canvas.drawRoundRect(9f,3f,14f,7f,1.5f,1.5f,pen)
                line(9f,12f,14f,12f); line(9f,16f,13f,16f)
            }
            ShareGlyph.DEVICES -> {
                canvas.drawRoundRect(2f,4f,17f,15f,2f,2f,pen)
                line(8f,15f,8f,19f); line(5f,19f,11f,19f)
                canvas.drawRoundRect(14f,9f,22f,21f,1.5f,1.5f,pen)
                line(17f,18f,19f,18f)
            }
            ShareGlyph.SETTINGS -> {
                canvas.drawCircle(12f,12f,6f,pen); canvas.drawCircle(12f,12f,2f,pen)
                for (i in 0..7) { canvas.save(); canvas.rotate(i*45f,12f,12f); line(12f,3f,12f,5f); canvas.restore() }
            }
            ShareGlyph.FILE -> {
                path(14f,3f,5f,3f,5f,21f,19f,21f,19f,8f,14f,3f,14f,8f,19f,8f)
                line(9f,12f,15f,12f); line(9f,16f,15f,16f)
            }
            ShareGlyph.SHIELD -> {
                val outline=Path().apply { moveTo(12f,3f); lineTo(20f,6f); lineTo(19f,13f); quadTo(18f,18f,12f,21f); quadTo(6f,18f,5f,13f); lineTo(4f,6f); close() }
                canvas.drawPath(outline,pen); path(8f,12f,11f,15f,16f,10f)
            }
            ShareGlyph.SYSTEM -> {
                canvas.drawRoundRect(3f,4f,21f,17f,2f,2f,pen)
                line(12f,17f,12f,21f); line(8f,21f,16f,21f)
            }
            ShareGlyph.SUN -> {
                canvas.drawCircle(12f,12f,4f,pen)
                for (i in 0..7) { canvas.save(); canvas.rotate(i*45f,12f,12f); line(12f,2f,12f,4f); canvas.restore() }
            }
            ShareGlyph.MOON -> canvas.drawPath(Path().apply {
                moveTo(19f,15f); cubicTo(10f,18f,5f,11f,9f,4f)
                cubicTo(-1f,8f,5f,25f,17f,19f); quadTo(19f,17f,19f,15f)
            },pen)
            ShareGlyph.CHEVRON -> path(9f,6f,15f,12f,9f,18f)
        }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { pen.alpha=alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { pen.colorFilter=filter; invalidateSelf() }
    @Deprecated("Required by Drawable") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
