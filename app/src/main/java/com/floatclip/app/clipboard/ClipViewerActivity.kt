package com.floatclip.app.clipboard

import android.app.Activity
import android.os.Bundle
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.floatclip.app.R
import com.floatclip.app.model.ClipEntry
import com.floatclip.app.ui.FileUi
import com.floatclip.app.ui.CopyFeedbackButton
import com.floatclip.app.ui.ZoomImageView

/** Internal viewer receives only a stable row id, never arbitrary external files or clipboard text. */
class ClipViewerActivity:Activity() {
    private lateinit var store:ClipStore
    private var imageRequest:AutoCloseable?=null
    private var copyRequest:AutoCloseable?=null
    private var returned=false
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        store=ClipStore(this)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(FileUi.palette(this).page))
    }
    override fun onStart() { super.onStart();showEntry() }
    override fun onNewIntent(value:Intent) { super.onNewIntent(value);setIntent(value);showEntry() }
    override fun onConfigurationChanged(config:Configuration) { super.onConfigurationChanged(config);showEntry() }
    @Suppress("DEPRECATION")
    private fun showEntry() {
        imageRequest?.close();copyRequest?.close()
        val entry=store.entries().firstOrNull { it.id==intent.getLongExtra(EXTRA_ID,-1) }
        if(entry==null){Toast.makeText(this,"这条记录已移除",Toast.LENGTH_SHORT).show();finish();return}
        if(entry.kind=="image") {
            val black=android.graphics.Color.rgb(10,11,14)
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(black))
            window.statusBarColor=black
            window.navigationBarColor=black
            window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            setContentView(ClipImageViewerView(this,store,store.entries(),entry.id) { finish() })
            return
        }
        val p=FileUi.palette(this)
        window.statusBarColor=p.page;window.navigationBarColor=p.page
        val lightBars=if(android.graphics.Color.luminance(p.page)>.5f)View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        window.decorView.systemUiVisibility=lightBars or if(entry.kind=="image")View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY else 0
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setBackgroundColor(p.page);setPadding(dp(12),dp(6),dp(12),dp(12))
        }
        val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_left);imageTintList=ColorStateList.valueOf(p.text)
            setPadding(dp(12),dp(12),dp(12),dp(12));contentDescription="返回";background=FileUi.action(this@ClipViewerActivity,p)
            setOnClickListener{finish()}
        },LinearLayout.LayoutParams(dp(48),dp(48)))
        header.addView(TextView(this).apply {
            text=if(entry.kind=="image")"${entry.imageWidth} × ${entry.imageHeight}" else "查看全文"
            textSize=16f;maxLines=1;setTextColor(p.text)
        },LinearLayout.LayoutParams(0,dp(48),1f))
        root.addView(header)
        var full:FrameLayout?=null
        if(entry.kind=="image") {
            val frame=FrameLayout(this)
            frame.addView(ProgressBar(this),FrameLayout.LayoutParams(dp(42),dp(42),Gravity.CENTER))
            val imageRoot=FrameLayout(this).apply {setBackgroundColor(p.page)}
            full=imageRoot
            imageRoot.addView(frame,FrameLayout.LayoutParams(-1,-1))
            root.removeView(header)
            header.background=FileUi.shape(this,p.surface,p.border)
            imageRoot.addView(header,FrameLayout.LayoutParams(-1,dp(48),Gravity.TOP).apply {leftMargin=dp(12);rightMargin=dp(12);topMargin=dp(8)})
            imageRequest=ClipboardImages.thumbnailAsync(store,entry,8192) { bitmap ->
                if(isFinishing||isDestroyed)return@thumbnailAsync
                frame.removeAllViews()
                if(bitmap!=null)frame.addView(ZoomImageView(this).apply {
                    setImageBitmap(bitmap)
                    setOnClickListener { header.visibility=if(header.visibility==View.VISIBLE)View.GONE else View.VISIBLE;imageRoot.getChildAt(2)?.visibility=header.visibility }
                },FrameLayout.LayoutParams(-1,-1))
                else frame.addView(TextView(this).apply {text="图片暂不可用，请保留本机数据";setTextColor(p.muted);gravity=Gravity.CENTER},FrameLayout.LayoutParams(-1,-1))
            }
        } else {
            val text=TextView(this).apply {
                this.text=entry.text;textSize=17f;setTextColor(p.text);setTextIsSelectable(true)
                setPadding(dp(10),dp(12),dp(10),dp(20));setLineSpacing(dp(4).toFloat(),1f)
                contentDescription="剪贴板全文"
            }
            root.addView(ScrollView(this).apply {addView(text);isFillViewport=true},LinearLayout.LayoutParams(-1,0,1f))
        }
        val copy=CopyFeedbackButton(this,if(entry.kind=="image")"复制图片" else "复制全文",if(entry.kind=="image")"已复制图片" else "已复制全文") { done ->
            if(entry.kind=="image")copyRequest=ClipboardImages.copyAsync(this,store,entry,done)
            else done(runCatching { ClipboardWrites.copyByUser(this,getSystemService(android.content.ClipboardManager::class.java),entry.text) })
        }
        val imageRoot=full
        if(imageRoot!=null) {
            imageRoot.addView(copy,FrameLayout.LayoutParams(-1,dp(52),Gravity.BOTTOM).apply{leftMargin=dp(12);rightMargin=dp(12);bottomMargin=dp(12)})
            setContentView(imageRoot)
        } else {
            root.addView(copy,LinearLayout.LayoutParams(-1,dp(52)).apply{topMargin=dp(8)})
            setContentView(root)
        }
    }
    override fun onDestroy() {
        imageRequest?.close();copyRequest?.close()
        if(isFinishing&&!returned) {
            returned=true
            intent.getStringExtra(EXTRA_RETURN)?.let { token ->
                sendBroadcast(Intent(RETURN_ACTION).setPackage(packageName).putExtra(EXTRA_RETURN,token),"$packageName.permission.INTERNAL")
            }
        }
        super.onDestroy()
    }
    private fun dp(value:Int)=(value*resources.displayMetrics.density+.5f).toInt()
    companion object {
        const val EXTRA_ID="clip_viewer_id"
        const val EXTRA_RETURN="clip_viewer_return"
        const val RETURN_ACTION="com.floatclip.app.CLIP_VIEWER_RETURN"
        fun open(context:Context,entry:ClipEntry,returnToken:String?=null) {
            context.startActivity(Intent(context,ClipViewerActivity::class.java).putExtra(EXTRA_ID,entry.id)
                .putExtra(EXTRA_RETURN,returnToken).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }
}
