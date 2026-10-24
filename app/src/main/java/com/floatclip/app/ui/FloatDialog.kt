package com.floatclip.app.ui

import android.app.Dialog
import android.content.Context
import android.content.ComponentCallbacks
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.floatclip.app.prefs.OverlayPreferences
import com.floatclip.probe.FloatClipAccent

/** FloatClip-owned modal surface. Avoids OEM AlertDialog geometry/colors on Android 11. */
object FloatDialog {
    data class Handle(val dialog:Dialog,val primary:TextView?,val body:LinearLayout)

    private data class P(
        val card:Int,val text:Int,val muted:Int,val line:Int,val field:Int,val accent:Int,val tint:Int,val dark:Boolean
    )

    /** Repaint an open surface in place: typed text, selection and action callbacks stay intact. */
    private class ThemedDialog(context:Context):Dialog(context),ComponentCallbacks,SharedPreferences.OnSharedPreferenceChangeListener {
        var repaint: (() -> Unit)? = null
        private val app=context.applicationContext
        private val appearance=app.getSharedPreferences("floatclip_overlay",Context.MODE_PRIVATE)
        override fun onStart() {
            super.onStart()
            app.registerComponentCallbacks(this)
            appearance.registerOnSharedPreferenceChangeListener(this)
            repaint?.invoke()
        }
        override fun onStop() {
            app.unregisterComponentCallbacks(this)
            appearance.unregisterOnSharedPreferenceChangeListener(this)
            super.onStop()
        }
        private fun refresh() { window?.decorView?.post { if(isShowing)repaint?.invoke() } }
        override fun onConfigurationChanged(newConfig:Configuration) = refresh()
        override fun onLowMemory() = Unit
        override fun onSharedPreferenceChanged(prefs:SharedPreferences?,key:String?) = refresh()
    }

    private fun repaint(view:View,old:P,next:P) {
        fun color(value:Int)=when(value) {
            old.text->next.text;old.muted->next.muted;old.accent->next.accent
            old.card->next.card;old.field->next.field;old.tint->next.tint;old.line->next.line
            else->value
        }
        if(view is TextView) {
            view.setTextColor(color(view.currentTextColor))
            view.setHintTextColor(color(view.currentHintTextColor))
        }
        when(val background=view.background) {
            is GradientDrawable->background.color?.defaultColor?.let { background.setColor(color(it)) }
            is ColorDrawable->background.color=color(background.color)
        }
        if(view is ViewGroup)for(index in 0 until view.childCount)repaint(view.getChildAt(index),old,next)
    }

    fun show(
        context:Context,
        title:String,
        message:String?=null,
        custom:View?=null,
        negative:String?="取消",
        positive:String?="确定",
        onNegative:(()->Unit)?=null,
        onCancel:(()->Unit)?=null,
        bottomSheet:Boolean=false,
        onPositive:((Handle)->Boolean)?=null,
    ):Handle {
        val p=palette(context)
        val dialog=ThemedDialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val density=context.resources.displayMetrics.density
        fun dp(v:Int)=(v*density).toInt()

        val shell=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(22),dp(20),dp(22),dp(18))
            background=GradientDrawable().apply {
                setColor(p.card);cornerRadius=dp(20).toFloat();setStroke(dp(1),p.line)
            }
        }
        shell.addView(TextView(context).apply {
            text=title;textSize=20f;setTextColor(p.text);typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            includeFontPadding=false
        },LinearLayout.LayoutParams(-1,-2))
        if(!message.isNullOrBlank()) shell.addView(TextView(context).apply {
            text=message;textSize=13f;setTextColor(p.muted);includeFontPadding=false
            setLineSpacing(dp(3).toFloat(),1.08f);setPadding(0,dp(12),0,0)
        },LinearLayout.LayoutParams(-1,-2))
        if(custom!=null) shell.addView(custom,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(16) })

        val actions=LinearLayout(context).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.END;setPadding(0,dp(16),0,0)
        }
        fun button(label:String,primary:Boolean)=TextView(context).apply {
            text=label;textSize=14f;gravity=Gravity.CENTER;minimumHeight=dp(44)
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            setPadding(dp(18),0,dp(18),0)
            setTextColor(if(primary) p.accent else p.text)
            background=GradientDrawable().apply {
                setColor(if(primary)p.tint else Color.TRANSPARENT);cornerRadius=dp(12).toFloat()
            }
        }
        val negativeView=negative?.let { button(it,false).also { actions.addView(it,LinearLayout.LayoutParams(-2,dp(44))) } }
        val positiveView=positive?.let { button(it,true).also { actions.addView(it,LinearLayout.LayoutParams(-2,dp(44)).apply { marginStart=dp(8) }) } }
        if(actions.childCount>0)shell.addView(actions)
        dialog.setContentView(shell)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnCancelListener { onCancel?.invoke() }

        lateinit var handle:Handle
        handle=Handle(dialog,positiveView,shell)
        negativeView?.setOnClickListener { onNegative?.invoke();dialog.dismiss() }
        positiveView?.setOnClickListener {
            val dismiss=onPositive?.invoke(handle) ?: true
            if(dismiss)dialog.dismiss()
        }
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes=attributes.apply { dimAmount=0.35f;windowAnimations=0 }
                if(bottomSheet)setGravity(Gravity.BOTTOM)
                setLayout((context.resources.displayMetrics.widthPixels-dp(40)).coerceAtMost(dp(420)),WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }
        var applied=p
        dialog.repaint={
            val next=palette(context)
            if(next!=applied) {
                repaint(shell,applied,next)
                (shell.background as GradientDrawable).setStroke(dp(1),next.line)
                applied=next
            }
        }
        dialog.show()
        // setLayout must be repeated after show on several Android 11 OEM Window implementations.
        dialog.window?.setLayout((context.resources.displayMetrics.widthPixels-dp(40)).coerceAtMost(dp(420)),WindowManager.LayoutParams.WRAP_CONTENT)
        return handle
    }

    fun choices(
        context:Context,
        title:String,
        items:List<String>,
        selected:Int=-1,
        onSelect:(Int)->Unit,
    ):Handle {
        val p=palette(context)
        val density=context.resources.displayMetrics.density
        fun dp(v:Int)=(v*density).toInt()
        val list=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
        val handle=show(context,title,custom=ScrollView(context).apply {
            addView(list,ViewGroup.LayoutParams(-1,-2))
        },negative="取消",positive=null)
        items.forEachIndexed { index,label ->
            list.addView(TextView(context).apply {
                text=(if(index==selected)"✓  " else "    ")+label
                textSize=15f;gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(50)
                setTextColor(if(index==selected)p.accent else p.text)
                setPadding(dp(8),0,dp(8),0)
                background=GradientDrawable().apply {
                    setColor(if(index==selected)p.tint else Color.TRANSPARENT);cornerRadius=dp(12).toFloat()
                }
                setOnClickListener { onSelect(index);handle.dialog.dismiss() }
            })
        }
        return handle
    }

    fun actions(context:Context,title:String,items:List<String>,onSelect:(Int)->Unit):Handle =
        choices(context,title,items,-1,onSelect)

    private fun palette(context:Context):P {
        val prefs=OverlayPreferences(context)
        val dark=when(prefs.themeMode().name) {
            "DARK"->true;"LIGHT"->false
            else -> context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val accent=FloatClipAccent.color(prefs.accentTheme(),dark)
        return if(dark) P(
            Color.rgb(36,40,59),Color.rgb(192,202,245),Color.rgb(154,165,206),Color.rgb(65,72,104),
            Color.rgb(31,35,53),accent,blend(Color.rgb(41,46,66),accent,.18f),true
        ) else P(
            Color.WHITE,Color.rgb(25,40,63),Color.rgb(89,108,133),Color.rgb(223,231,242),
            Color.rgb(240,243,248),accent,blend(Color.rgb(235,242,255),accent,.08f),false
        )
    }

    private fun blend(a:Int,b:Int,t:Float)=Color.rgb(
        (Color.red(a)+(Color.red(b)-Color.red(a))*t).toInt(),
        (Color.green(a)+(Color.green(b)-Color.green(a))*t).toInt(),
        (Color.blue(a)+(Color.blue(b)-Color.blue(a))*t).toInt(),
    )
}
