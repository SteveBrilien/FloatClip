package com.floatclip.app.ui

import android.content.Context
import android.view.MotionEvent
import android.view.Gravity
import android.widget.TextView
import com.floatclip.app.prefs.OverlayPreferences

/** Copy feedback never blocks navigation; success is shown only after the clipboard write. */
class CopyFeedbackButton(context:Context,private val label:String,private val copiedLabel:String,
    private val action:((Result<Unit>)->Unit)->Unit):TextView(context) {
    private val appearance=OverlayPreferences(context)
    private val palette=FileUi.palette(context)
    private val reset=Runnable { text=label;setTextColor(palette.accent) }
    init {
        text=label;textSize=15f;gravity=Gravity.CENTER
        minimumHeight=(48*resources.displayMetrics.density).toInt()
        background=FileUi.action(context,palette,true);setTextColor(palette.accent)
        contentDescription=label;isClickable=true;isFocusable=true
        setOnClickListener {
            if(!isEnabled)return@setOnClickListener
            removeCallbacks(reset);isEnabled=false;text="正在复制…"
            action { result ->
                isEnabled=true
                result.onSuccess {
                    text=copiedLabel;setTextColor(palette.success)
                    announceForAccessibility(copiedLabel)
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                }.onFailure {
                    text="复制未完成";setTextColor(palette.destructive)
                    android.widget.Toast.makeText(context,it.message?:"复制未完成，请重试",android.widget.Toast.LENGTH_LONG).show()
                }
                postDelayed(reset,1400)
            }
        }
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(isEnabled)when(event.actionMasked){
            MotionEvent.ACTION_DOWN -> scale(appearance.cardPressScalePercent()/100f)
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> scale(1f)
        }
        return super.onTouchEvent(event)
    }
    private fun scale(value:Float) {
        animate().cancel()
        animate().scaleX(value).scaleY(value)
            .setInterpolator(appearance.motionInterpolator())
            .setDuration(if(android.animation.ValueAnimator.areAnimatorsEnabled())(appearance.transitionDurationMs()*.4f).toLong() else 0L).start()
    }
    override fun onDetachedFromWindow() { removeCallbacks(reset);animate().cancel();scaleX=1f;scaleY=1f;super.onDetachedFromWindow() }
}
