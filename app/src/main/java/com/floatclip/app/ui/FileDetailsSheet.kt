package com.floatclip.app.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.floatclip.app.R
import com.floatclip.app.prefs.OverlayPreferences
import kotlin.math.abs
import kotlin.math.roundToInt

/** A bottom-aligned file detail surface with a scrollable body and downward dismissal. */
internal class FileDetailsSheet(
    private val owner: Activity,
    title: String,
    content: View,
    colors: FileUi.Palette,
) : Dialog(owner) {
    private val config=ViewConfiguration.get(owner)
    private val motion=OverlayPreferences(owner)
    private var closing=false
    private val scroller=ScrollView(owner).apply {
        isFillViewport=false
        addView(content,ViewGroup.LayoutParams(-1,-2))
    }
    private val header=LinearLayout(owner).apply {
        orientation=LinearLayout.HORIZONTAL
        gravity=Gravity.CENTER_VERTICAL
        setPadding(dp(18),0,dp(8),0)
        addView(TextView(owner).apply {
            text=title;textSize=18f;setTextColor(colors.text)
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            includeFontPadding=false;maxLines=2;ellipsize=TextUtils.TruncateAt.END
            isAccessibilityHeading=true
        },LinearLayout.LayoutParams(0,-2,1f))
        addView(ImageView(owner).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList=ColorStateList.valueOf(colors.muted)
            setPadding(dp(14),dp(14),dp(14),dp(14))
            background=FileUi.action(owner,colors)
            contentDescription="关闭文件详情";isFocusable=true
            setOnClickListener { close() }
        },LinearLayout.LayoutParams(dp(48),dp(48)))
    }
    private val grip=View(owner).apply {
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        background=FileUi.shape(owner,colors.border,radius=3)
    }
    private val surface=LinearLayout(owner).apply {
        orientation=LinearLayout.VERTICAL
        val radius=dp(FileUi.CARD_RADIUS*2).toFloat()
        background=GradientDrawable().apply {
            setColor(colors.surface)
            cornerRadii=floatArrayOf(radius,radius,radius,radius,0f,0f,0f,0f)
        }
        accessibilityPaneTitle="文件详情"
        isClickable=true
        addView(grip,LinearLayout.LayoutParams(dp(34),dp(4)).apply {
            gravity=Gravity.CENTER_HORIZONTAL;topMargin=dp(8);bottomMargin=dp(4)
        })
        addView(header,LinearLayout.LayoutParams(-1,-2))
        addView(scroller,LinearLayout.LayoutParams(-1,0,1f))
    }
    private val root=DragHost().apply { addView(surface,FrameLayout.LayoutParams(-1,-1)) }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(root)
        setCanceledOnTouchOutside(true)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setGravity(Gravity.BOTTOM)
            attributes=attributes.apply { windowAnimations=0;dimAmount=.34f }
        }
    }

    override fun show() {
        val container=owner.findViewById<View>(android.R.id.content)
        val available=container.height.takeIf{it>0}?:owner.resources.displayMetrics.heightPixels
        val fraction=if(container.width>available) .82f else .59f
        val height=(available*fraction).roundToInt().coerceIn(minOf(dp(250),available),available)
        val animate=ValueAnimator.areAnimatorsEnabled()
        surface.translationY=if(animate)height.toFloat() else 0f
        super.show()
        window?.setLayout(-1,height)
        if(animate)settle(0f,240)
    }

    fun close(after:()->Unit={}) {
        if(closing || !isShowing)return
        closing=true
        if(!ValueAnimator.areAnimatorsEnabled()) { dismiss();after();return }
        settle(root.height.toFloat(),170) { dismiss();after() }
    }

    override fun cancel() { close() }

    private fun settle(target:Float,duration:Long,after:()->Unit={}) {
        surface.animate().cancel()
        if(!ValueAnimator.areAnimatorsEnabled()) { surface.translationY=target;after();return }
        surface.animate().translationY(target)
            .setDuration(duration)
            .setInterpolator(motion.motionInterpolator())
            .withEndAction(after).start()
    }

    private fun dp(value:Int)=(value*owner.resources.displayMetrics.density).roundToInt()

    private inner class DragHost:FrameLayout(owner) {
        private var startX=0f
        private var startY=0f
        private var dragOrigin=0f
        private var canDrag=false
        private var dragging=false
        private var velocity:VelocityTracker?=null

        override fun onInterceptTouchEvent(event:MotionEvent):Boolean {
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX=event.x;startY=event.y
                    canDrag=!closing && (event.y<scroller.top || !scroller.canScrollVertically(-1))
                    dragging=false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy=event.y-startY
                    if(canDrag && dy>config.scaledTouchSlop && dy>abs(event.x-startX)*1.25f) {
                        surface.animate().cancel()
                        dragOrigin=event.y-surface.translationY
                        velocity=VelocityTracker.obtain().apply { addMovement(event) }
                        dragging=true
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(event:MotionEvent):Boolean {
            if(!dragging)return false
            velocity?.addMovement(event)
            when(event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val distance=(event.y-dragOrigin).coerceAtLeast(0f)
                    val threshold=height*.28f
                    surface.translationY=if(distance<=threshold)distance else threshold+(distance-threshold)*.66f
                }
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {
                    velocity?.computeCurrentVelocity(1000,config.scaledMaximumFlingVelocity.toFloat())
                    val fast=surface.translationY>dp(20) && (velocity?.yVelocity?:0f)>dp(850)
                    val dismiss=event.actionMasked==MotionEvent.ACTION_UP && (surface.translationY>height*.28f || fast)
                    releaseDrag()
                    if(dismiss) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        close()
                    } else settle(0f,180)
                }
            }
            return true
        }

        override fun onDetachedFromWindow() { releaseDrag();super.onDetachedFromWindow() }

        private fun releaseDrag() {
            dragging=false
            velocity?.recycle();velocity=null
        }
    }
}
