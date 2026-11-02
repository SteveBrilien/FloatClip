package com.floatclip.app.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.ProgressBar
import com.floatclip.app.model.ClipEntry
import com.floatclip.app.ui.FloatDialog
import java.io.ByteArrayOutputStream

/** Foreground ClipData grants are consumed immediately; external URIs are never stored as history. */
object ClipboardImages {
    private val captures=java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task,"FloatClip image capture").apply { isDaemon=true } }
    private val main=Handler(Looper.getMainLooper())
    private val sequence=java.util.concurrent.atomic.AtomicLong()
    private class DecodeTask(val priority:Int,val order:Long,val work:()->Unit):Runnable,Comparable<DecodeTask> {
        override fun run()=work()
        override fun compareTo(other:DecodeTask)=compareValuesBy(this,other,{it.priority},{it.order})
    }
    private val decodes=java.util.concurrent.ThreadPoolExecutor(1,1,0,java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.PriorityBlockingQueue<Runnable>(),java.util.concurrent.ThreadFactory { task -> Thread(task,"FloatClip thumbnails").apply { isDaemon=true } })
    private val previews=java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task,"FloatClip image preview").apply { isDaemon=true } }
    private class Request(val done:(Bitmap?)->Unit) { @Volatile var active=true }
    private val pending=mutableMapOf<String,MutableList<Request>>()

    private val thumbnails=object:android.util.LruCache<String,Bitmap>(8*1024*1024) {
        override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount
    }
    fun captureAsync(context:Context,clip:ClipData,store:ClipStore,done:(Result<ClipEntry?>)->Unit) {
        captures.execute { val result=runCatching { capture(context,clip,store) };main.post { done(result) } }
    }
    fun event(clip:ClipData)=clip.description.timestamp.toString()+":"+(if(clip.itemCount>0)clip.getItemAt(0).uri else null)
    fun isImage(context:Context,clip:ClipData):Boolean {
        if(clip.description.hasMimeType("image/*"))return true
        val uri=if(clip.itemCount>0)clip.getItemAt(0).uri else null
        return uri?.scheme=="content" && runCatching { context.contentResolver.getType(uri)?.startsWith("image/")==true }.getOrDefault(false)
    }
    fun capture(context:Context,clip:ClipData,store:ClipStore):ClipEntry? {
        if(!isImage(context,clip) || ClipboardWrites.isOwnClip(context,clip.description) ||
            clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE",false)==true)return null
        require(clip.itemCount==1) { "暂仅支持单张剪贴板图片" }
        val uri=clip.getItemAt(0).uri ?: return null
        return importUri(context,uri,store)
    }
    /** Explicit picker/share import also works when a screenshot was never put in ClipData. */
    fun importUri(context:Context,uri:android.net.Uri,store:ClipStore):ClipEntry? {
        require(uri.scheme=="content") { "仅收录获授权的剪贴板图片" }
        val encoded=context.contentResolver.openInputStream(uri)?.use { input ->
            val output=BoundedOutput(ImageBlobStore.MAX_IMAGE_BYTES)
            input.copyTo(output);output.toByteArray()
        } ?: return null
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(encoded,0,encoded.size,bounds)
        require(bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384 && bounds.outWidth.toLong()*bounds.outHeight<=ImageBlobStore.MAX_PIXELS) { "图片尺寸超过 6400 万像素" }
        val bitmap=BitmapFactory.decodeByteArray(encoded,0,encoded.size) ?: error("图片无法读取")
        return try {
            // Screenshots already in PNG retain their exact pixels/metadata and avoid recompression.
            if(encoded.take(8).toByteArray().contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10)))return store.addImage(encoded)
            val output=BoundedOutput(ImageBlobStore.MAX_IMAGE_BYTES)
            check(bitmap.compress(Bitmap.CompressFormat.PNG,100,output)) { "图片转换失败" }
            store.addImage(output.toByteArray())
        } finally { bitmap.recycle() }
    }
    fun thumbnail(store:ClipStore,entry:ClipEntry,maxDimension:Int=480):Bitmap?=runCatching {
        val key=entry.imageRef+":"+entry.imageSha256+":"+maxDimension
        thumbnails.get(key)?.let { return@runCatching it }
        val png=store.readImage(entry)
        val scale=maxOf(entry.imageWidth,entry.imageHeight)/maxDimension
        var sample=1
        while(sample*2<=scale || entry.imageWidth.toLong()/sample*(entry.imageHeight/sample)>8_000_000)sample*=2
        BitmapFactory.decodeByteArray(png,0,png.size,BitmapFactory.Options().apply { inSampleSize=sample })?.also { thumbnails.put(key,it) }
    }.getOrNull()

    private fun thumbnailKey(entry:ClipEntry,maxDimension:Int,card:Boolean)=
        entry.imageRef+":"+entry.imageSha256+":"+maxDimension+if(card && entry.imageHeight>entry.imageWidth*2.2f)":card" else ""

    /** Long cards decode only their visible top region; the full preview remains independent. */
    internal fun cardRegion(width:Int,height:Int):android.graphics.Rect =
        android.graphics.Rect(0,0,width,minOf(height,width*2))

    fun prefetch(store:ClipStore,entry:ClipEntry) {
        if(decodes.queue.size<4)thumbnailAsync(store,entry,480,card=true,prefetch=true) { }
    }
    fun thumbnailAsync(store:ClipStore,entry:ClipEntry,maxDimension:Int=480,card:Boolean=false,prefetch:Boolean=false,done:(Bitmap?)->Unit):AutoCloseable {
        val key=thumbnailKey(entry,maxDimension,card)
        thumbnails.get(key)?.let { done(it);return AutoCloseable {} }
        val request=Request(done)
        val cancellation=AutoCloseable {
            request.active=false
            synchronized(pending) { pending[key]?.remove(request) }
        }
        synchronized(pending) {
            pending[key]?.let { it.add(request);return cancellation }
            pending[key]=mutableListOf(request)
        }
        val work=work@{
            val wanted=synchronized(pending) {
                val active=pending[key]?.any { it.active }==true
                if(!active)pending.remove(key)
                active
            }
            if(!wanted)return@work Unit
            val bitmap=runCatching {
                val disk=if(maxDimension<=480)store.readThumbnail(key) else null
                val saved=disk?.let { BitmapFactory.decodeByteArray(it,0,it.size) }
                if(saved!=null)saved else {
                    val decoded=if(card && entry.imageHeight>entry.imageWidth*2.2f) {
                        val png=store.readImage(entry)
                        val decoder=android.graphics.BitmapRegionDecoder.newInstance(png,0,png.size,false)
                        try {
                            var sample=1
                            while(entry.imageWidth/(sample*2)>=maxDimension)sample*=2
                            decoder.decodeRegion(cardRegion(entry.imageWidth,entry.imageHeight),BitmapFactory.Options().apply{inSampleSize=sample})
                        } finally {decoder.recycle()}
                    } else thumbnail(store,entry,maxDimension)
                    if(decoded!=null && maxDimension<=480) {
                        val output=ByteArrayOutputStream()
                        decoded.compress(Bitmap.CompressFormat.PNG,100,output)
                        store.writeThumbnail(key,output.toByteArray())
                    }
                    decoded
                }
            }.getOrNull()
            if(bitmap!=null)thumbnails.put(key,bitmap)
            main.post { synchronized(pending){pending.remove(key)}.orEmpty().filter { it.active }.forEach{it.done(bitmap)} }
            Unit
        }
        if(maxDimension>480)previews.execute(work) else decodes.execute(DecodeTask(if(prefetch)10 else 0,sequence.incrementAndGet(),work))
        return cancellation
    }

    fun copy(context:Context,store:ClipStore,entry:ClipEntry) {
        store.readImage(entry) // Fail before replacing the system clipboard if an attachment is damaged.
        ClipboardWrites.writeImage(context,context.getSystemService(ClipboardManager::class.java),ClipboardImageProvider.uri(context.packageName,entry.imageRef))
        com.floatclip.app.sync.SyncRuntime.userCopiedImage(context,entry)
    }
    fun preview(context:Context,store:ClipStore,entry:ClipEntry) {
        val height=(context.resources.displayMetrics.heightPixels*.6f).toInt()
        val frame=FrameLayout(context).apply {
            addView(ProgressBar(context),FrameLayout.LayoutParams(
                (42*resources.displayMetrics.density).toInt(),(42*resources.displayMetrics.density).toInt(),Gravity.CENTER
            ))
        }
        val handle=FloatDialog.show(context,"图片预览",custom=frame,negative="关闭",positive="复制图片",onPositive={copy(context,store,entry);true})
        val request=thumbnailAsync(store,entry,4096) { bitmap ->
            if(!handle.dialog.isShowing)return@thumbnailAsync
            frame.removeAllViews()
            if(bitmap==null)frame.addView(android.widget.TextView(context).apply {
                text="图片暂不可用，请保留本机数据";gravity=Gravity.CENTER
            },FrameLayout.LayoutParams(-1,height))
            else frame.addView(com.floatclip.app.ui.ZoomImageView(context).apply { setImageBitmap(bitmap) },FrameLayout.LayoutParams(-1,height))
        }
        handle.dialog.setOnDismissListener { request.close() }
    }
    private class BoundedOutput(private val limit:Int):ByteArrayOutputStream() {
        override fun write(value:Int) { require(count<limit) { "图片超过 8 MiB" };super.write(value) }
        override fun write(bytes:ByteArray,offset:Int,length:Int) { require(count.toLong()+length<=limit) { "图片超过 8 MiB" };super.write(bytes,offset,length) }
    }
}
