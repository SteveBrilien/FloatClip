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
    private val importLock=Any()
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
    fun trimThumbnailsForStandby() = thumbnails.trimToSize(2 * 1024 * 1024)

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
    data class ImportResult(val entry:ClipEntry?,val resized:Boolean)
    fun importUri(context:Context,uri:android.net.Uri,store:ClipStore):ClipEntry?=importSelected(context,uri,store).entry
    fun importSelected(context:Context,uri:android.net.Uri,store:ClipStore):ImportResult {
        require(uri.scheme=="content") { "仅收录获授权的剪贴板图片" }
        val encoded=context.contentResolver.openInputStream(uri)?.use { input ->
            val output=BoundedOutput(ClipboardPhotoImporter.MAX_SOURCE_BYTES,"原图片超过 32 MiB，请先在相册缩小后分享")
            input.copyTo(output);output.toByteArray()
        } ?: error("未能读取图片，请在相册重新选择")
        val prepared=ClipboardPhotoImporter.prepare(encoded)
        if(Thread.currentThread().isInterrupted)throw InterruptedException("图片导入已取消")
        val (saved,fresh)=synchronized(importLock) {
            val keys=store.entries().filter { it.kind=="image" }.mapTo(HashSet()) { it.key }
            val entry=store.addImage(prepared.png)
            entry to (entry!=null && entry.key !in keys)
        }
        // Capture and document-import workers can race; only the winner publishes the image.
        if(fresh && saved!=null)
            main.post { com.floatclip.app.sync.SyncRuntime.userCopiedImage(context.applicationContext,saved) }
        return ImportResult(saved,prepared.resized)
    }
    fun thumbnail(store:ClipStore,entry:ClipEntry,maxDimension:Int=480):Bitmap?=runCatching {
        val key=thumbnailKey(store,entry,maxDimension,false)
        thumbnails.get(key)?.let { return@runCatching it }
        val png=store.readImage(entry)
        val scale=(maxOf(entry.imageWidth,entry.imageHeight)+maxDimension-1)/maxDimension
        var sample=1
        while(sample*2<=scale || entry.imageWidth.toLong()/sample*(entry.imageHeight/sample)>8_000_000)sample*=2
        BitmapFactory.decodeByteArray(png,0,png.size,BitmapFactory.Options().apply { inSampleSize=sample })?.also { if(it.allocationByteCount<=2*1024*1024)thumbnails.put(key,it) }
    }.getOrNull()

    private fun thumbnailKey(store:ClipStore,entry:ClipEntry,maxDimension:Int,card:Boolean)=
        store.imageCacheScope+":"+entry.imageRef+":"+entry.imageSha256+":"+maxDimension+if(card && entry.imageHeight>entry.imageWidth*2.2f)":card" else ""

    /** Long cards decode only their visible top region; the full preview remains independent. */
    internal fun cardRegion(width:Int,height:Int):android.graphics.Rect =
        android.graphics.Rect(0,0,width,minOf(height,width*2))

    fun prefetch(store:ClipStore,entry:ClipEntry) {
        if(decodes.queue.size<4)thumbnailAsync(store,entry,480,card=true,prefetch=true) { }
    }
    fun thumbnailAsync(store:ClipStore,entry:ClipEntry,maxDimension:Int=480,card:Boolean=false,prefetch:Boolean=false,done:(Bitmap?)->Unit):AutoCloseable {
        val key=thumbnailKey(store,entry,maxDimension,card)
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
                            while((entry.imageWidth+sample-1)/sample>maxDimension)sample*=2
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
            if(bitmap!=null && bitmap.allocationByteCount<=2*1024*1024)thumbnails.put(key,bitmap)
            main.post { synchronized(pending){pending.remove(key)}.orEmpty().filter { it.active }.forEach{it.done(bitmap)} }
            Unit
        }
        if(maxDimension>480)previews.execute(work) else decodes.execute(DecodeTask(if(prefetch)10 else 0,sequence.incrementAndGet(),work))
        return cancellation
    }

    private val copies=java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task,"FloatClip image copy").apply {isDaemon=true} }
    fun isPrimaryImage(context: Context, clipboard: ClipboardManager, entry: ClipEntry): Boolean = runCatching {
        if (clipboard.primaryClipDescription?.hasMimeType("image/*") != true) return@runCatching false
        val clip = clipboard.primaryClip ?: return@runCatching false
        clip.itemCount > 0 && ClipboardImageProvider.reference(clip.getItemAt(0).uri, context.packageName) == entry.imageRef
    }.getOrDefault(false)

    private fun publishCopy(context:Context,entry:ClipEntry) {
        ClipboardWrites.writeImage(context,context.getSystemService(ClipboardManager::class.java),ClipboardImageProvider.uri(context.packageName,entry.imageRef))
        // Copying a saved image is clipboard-only: never create a new sync event or move the row.
    }
    fun copy(context:Context,store:ClipStore,entry:ClipEntry) {
        store.readImage(entry)
        publishCopy(context,entry)
    }
    fun copyAsync(context:Context,store:ClipStore,entry:ClipEntry,done:(Result<Unit>)->Unit):AutoCloseable {
        val active=java.util.concurrent.atomic.AtomicBoolean(true)
        copies.execute {
            val validation=runCatching { store.readImage(entry);Unit }
            main.post {
                if(active.get())done(validation.fold({runCatching {publishCopy(context,entry)}},{Result.failure(it)}))
            }
        }
        return AutoCloseable {active.set(false)}
    }
    fun copyWithFeedback(context:Context,store:ClipStore,entry:ClipEntry) {
        val notice=android.widget.Toast.makeText(context,"正在复制图片…",android.widget.Toast.LENGTH_SHORT)
        notice.show()
        copyAsync(context,store,entry) { result ->
            notice.cancel()
            android.widget.Toast.makeText(context,if(result.isSuccess)"已复制图片" else result.exceptionOrNull()?.message?:"复制未完成",android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    fun preview(context:Context,store:ClipStore,entry:ClipEntry) { ClipViewerActivity.open(context,entry) }
    private class BoundedOutput(private val limit:Int,private val message:String="图片超过 8 MiB"):ByteArrayOutputStream() {
        override fun write(value:Int) { require(count<limit) { message };super.write(value) }
        override fun write(bytes:ByteArray,offset:Int,length:Int) { require(count.toLong()+length<=limit) { message };super.write(bytes,offset,length) }
    }
}
