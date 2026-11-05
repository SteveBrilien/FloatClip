package com.floatclip.app.clipboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.floatclip.app.R
import com.floatclip.app.model.ClipEntry
import com.floatclip.app.prefs.OverlayPreferences
import com.floatclip.app.ui.CopyFeedbackButton
import com.floatclip.app.ui.ZoomImageView
import kotlin.math.roundToLong

/**
 * Shared image lightbox for both the app window and the floating overlay.
 * It never owns navigation: close returns to the surface that opened it.
 */
class ClipImageViewerView(
    context: Context,
    private val store: ClipStore,
    entries: List<ClipEntry>,
    startId: Long,
    private val onCloseRequested: () -> Unit,
) : FrameLayout(context), AutoCloseable {
    private val images = entries.filter { it.kind == "image" && it.imageRef.isNotBlank() }
    private val appearance = OverlayPreferences(context)
    private val imageHost = FrameLayout(context)
    private val topChrome = FrameLayout(context)
    private val counter = TextView(context)
    private val size = TextView(context)
    private val copyButton: CopyFeedbackButton
    private var imageRequest: AutoCloseable? = null
    private var copyRequest: AutoCloseable? = null
    private var index = images.indexOfFirst { it.id == startId }.let { if (it >= 0) it else 0 }
    private var current: ClipEntry? = images.getOrNull(index)
    private var closed = false

    init {
        setBackgroundColor(Color.rgb(10, 11, 14))
        isClickable = true
        isFocusable = true
        contentDescription = "图片查看器"
        addView(imageHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        topChrome.setPadding(dp(10), dp(10), dp(10), 0)
        val meta = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(counter, android.widget.LinearLayout.LayoutParams(-1, dp(24)))
            addView(size, android.widget.LinearLayout.LayoutParams(-1, dp(20)))
        }
        counter.apply {
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        size.apply {
            gravity = Gravity.CENTER
            textSize = 11.5f
            setTextColor(Color.argb(185, 255, 255, 255))
        }
        topChrome.addView(meta, LayoutParams(LayoutParams.MATCH_PARENT, dp(52), Gravity.TOP).apply {
            leftMargin = dp(56)
            rightMargin = dp(56)
        })
        topChrome.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundAction()
            contentDescription = "关闭图片查看"
            setOnClickListener { onCloseRequested() }
        }, LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END))
        addView(topChrome, LayoutParams(LayoutParams.MATCH_PARENT, dp(66), Gravity.TOP).apply {
            topMargin = systemBarHeight("status_bar_height") + dp(8)
        })

        copyButton = CopyFeedbackButton(context, "复制图片", "已复制图片") { done ->
            val entry = current
            copyRequest?.close()
            copyRequest = if (entry == null) {
                done(Result.failure(IllegalStateException("图片已不可用")))
                AutoCloseable { }
            } else {
                ClipboardImages.copyAsync(context, store, entry, done)
            }
        }.apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
                GradientDrawable().apply {
                    setColor(Color.argb(110, 35, 37, 44))
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), Color.argb(70, 255, 255, 255))
                },
                GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(12).toFloat()
                },
            )
        }
        addView(copyButton, LayoutParams(dp(132), dp(46), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = systemBarHeight("navigation_bar_height") + dp(16)
        })

        if (images.isEmpty()) {
            imageHost.addView(TextView(context).apply {
                text = "图片暂不可用"
                gravity = Gravity.CENTER
                textSize = 15f
                setTextColor(Color.argb(190, 255, 255, 255))
            }, LayoutParams(-1, -1))
        } else {
            show(index, 0, false)
        }
    }

    fun currentEntryId(): Long? = current?.id

    fun page(delta: Int): Boolean {
        if (delta == 0 || images.size < 2) return false
        val next = index + delta
        if (next !in images.indices) return false
        index = next
        show(index, delta, true)
        return true
    }

    private fun show(position: Int, direction: Int, animated: Boolean) {
        val entry = images.getOrNull(position) ?: return
        current = entry
        imageRequest?.close()
        imageHost.animate().cancel()
        imageHost.removeAllViews()
        counter.text = if (images.size > 1) "${position + 1} / ${images.size}" else ""
        size.text = listOf(entry.imageWidth, entry.imageHeight).takeIf { it.all { value -> value > 0 } }
            ?.let { "${it[0]} × ${it[1]}" }.orEmpty()

        val progress = ProgressBar(context).apply {
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
        }
        imageHost.addView(progress, LayoutParams(dp(40), dp(40), Gravity.CENTER))
        imageRequest = ClipboardImages.thumbnailAsync(store, entry, 8192) { bitmap ->
            if (closed || current?.id != entry.id) return@thumbnailAsync
            imageHost.removeAllViews()
            if (bitmap == null) {
                imageHost.addView(TextView(context).apply {
                    text = "图片暂不可用，请保留本机数据"
                    gravity = Gravity.CENTER
                    textSize = 14f
                    setTextColor(Color.argb(190, 255, 255, 255))
                }, LayoutParams(-1, -1))
                return@thumbnailAsync
            }
            val zoom = ZoomImageView(context).apply {
                setImageBitmap(bitmap)
                onHorizontalPage = { delta -> page(delta) }
                setOnClickListener { toggleChrome() }
            }
            imageHost.addView(zoom, LayoutParams(-1, -1))
            if (animated && android.animation.ValueAnimator.areAnimatorsEnabled()) {
                imageHost.alpha = 0f
                imageHost.translationX = dp(if (direction > 0) 28 else -28).toFloat()
                imageHost.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setInterpolator(appearance.motionInterpolator())
                    .setDuration((appearance.transitionDurationMs() * 0.72f).roundToLong().coerceIn(90L, 420L))
                    .start()
            } else {
                imageHost.alpha = 1f
                imageHost.translationX = 0f
            }
        }
    }

    private fun toggleChrome() {
        val show = topChrome.visibility != View.VISIBLE
        topChrome.visibility = if (show) View.VISIBLE else View.INVISIBLE
        copyButton.visibility = if (show) View.VISIBLE else View.INVISIBLE
    }

    private fun roundAction() = RippleDrawable(
        ColorStateList.valueOf(Color.argb(75, 255, 255, 255)),
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(105, 28, 30, 36))
            setStroke(dp(1), Color.argb(65, 255, 255, 255))
        },
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        },
    )

    override fun close() {
        if (closed) return
        closed = true
        imageRequest?.close()
        imageRequest = null
        copyRequest?.close()
        copyRequest = null
        animate().cancel()
        imageHost.animate().cancel()
    }

    override fun onDetachedFromWindow() {
        close()
        super.onDetachedFromWindow()
    }

    private fun systemBarHeight(name:String):Int {
        val id=resources.getIdentifier(name,"dimen","android")
        return if(id!=0)runCatching { resources.getDimensionPixelSize(id) }.getOrDefault(0) else 0
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}

