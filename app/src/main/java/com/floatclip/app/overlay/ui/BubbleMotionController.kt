package com.floatclip.app.overlay.ui

import android.content.Context
import android.graphics.Point
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Two-stage motion: velocity-driven free glide first, then a soft spring into the nearest edge.
 * This deliberately delays edge magnetism so a thrown bubble keeps its momentum instead of
 * being captured immediately.
 */
class BubbleMotionController(
    context: Context,
    private val windowManager: WindowManager,
    private val screenSize: () -> Point,
    private val onSettled: (y: Int, onRight: Boolean) -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    private val scroller = OverScroller(context).apply {
        setFriction(ViewConfiguration.getScrollFriction() * 0.42f)
    }
    private var runningView: View? = null
    private var runningFrame: Runnable? = null
    private var generation = 0

    fun cancel() {
        generation++
        scroller.forceFinished(true)
        runningFrame?.let { frame -> runningView?.removeCallbacks(frame) }
        runningFrame = null
        runningView = null
    }

    fun release(view: View, params: WindowManager.LayoutParams, velocityX: Float, velocityY: Float) {
        cancel()
        val speed = hypot(velocityX.toDouble(), velocityY.toDouble()).toFloat()
        if (speed >= 180f * density) {
            startFling(view, params, velocityX, velocityY)
        } else {
            startSpring(view, params, velocityX)
        }
    }

    private fun startFling(view: View, params: WindowManager.LayoutParams, velocityX: Float, velocityY: Float) {
        val screen = screenSize()
        val maxY = (screen.y - params.height).coerceAtLeast(0)
        // Direct dragging deliberately permits half of the bubble past either horizontal edge.
        // Preserve that exact release coordinate here; clamping to 0..visibleMaxX caused a
        // one-frame teleport when the user physically pushed the ball into the edge.
        val minMotionX = -params.width / 2
        val maxMotionX = screen.x - params.width / 2
        scroller.fling(
            params.x.coerceIn(minMotionX, maxMotionX),
            params.y.coerceIn(0, maxY),
            velocityX.toInt(),
            velocityY.toInt(),
            minMotionX,
            maxMotionX,
            0,
            maxY,
        )
        val token = ++generation
        runningView = view
        val frame = object : Runnable {
            override fun run() {
                if (token != generation || runningView !== view) return
                if (scroller.computeScrollOffset()) {
                    params.x = scroller.currX
                    params.y = scroller.currY
                    runCatching { windowManager.updateViewLayout(view, params) }
                    view.postOnAnimation(this)
                } else {
                    runningFrame = null
                    runningView = null
                    startSpring(view, params, 0f)
                }
            }
        }
        runningFrame = frame
        view.postOnAnimation(frame)
    }

    private fun startSpring(view: View, params: WindowManager.LayoutParams, initialVelocityX: Float) {
        val screen = screenSize()
        val maxX = (screen.x - params.width).coerceAtLeast(0)
        val minMotionX = -params.width / 2f
        val maxMotionX = screen.x - params.width / 2f
        val onRight = params.x + params.width / 2 >= screen.x / 2
        val targetX = if (onRight) maxX.toFloat() else 0f
        val token = ++generation
        runningView = view

        var x = params.x.toFloat().coerceIn(minMotionX, maxMotionX)
        var velocity = initialVelocityX
        var lastNanos = System.nanoTime()
        val stiffness = 74f
        val dampingRatio = 0.88f
        val damping = 2f * sqrt(stiffness) * dampingRatio

        val frame = object : Runnable {
            override fun run() {
                if (token != generation || runningView !== view) return
                val now = System.nanoTime()
                val dt = ((now - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.032f)
                lastNanos = now
                val displacement = x - targetX
                val acceleration = -stiffness * displacement - damping * velocity
                velocity += acceleration * dt
                x += velocity * dt
                if (x < minMotionX) {
                    x = minMotionX
                    if (velocity < 0f) velocity *= -0.22f
                } else if (x > maxMotionX) {
                    x = maxMotionX
                    if (velocity > 0f) velocity *= -0.22f
                }
                params.x = x.toInt()
                params.y = params.y.coerceIn(0, (screen.y - params.height).coerceAtLeast(0))
                runCatching { windowManager.updateViewLayout(view, params) }

                if (abs(x - targetX) <= 0.75f && abs(velocity) <= 12f) {
                    params.x = targetX.toInt()
                    runCatching { windowManager.updateViewLayout(view, params) }
                    runningFrame = null
                    runningView = null
                    onSettled(params.y, onRight)
                } else {
                    view.postOnAnimation(this)
                }
            }
        }
        runningFrame = frame
        view.postOnAnimation(frame)
    }
}
