package com.floatclip.app.clipboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import com.floatclip.app.model.ClipEntry
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor

/**
 * One original attachment, one decoder, one visible region. The encrypted original stays on disk;
 * neither full-size bitmaps nor viewers enter the card thumbnail cache.
 */
internal class ClipImageSource(
    private val store: ClipStore,
    private val entry: ClipEntry,
    private val onError: () -> Unit,
) : AutoCloseable {
    private data class Request(val rect: Rect, val sample: Int, val generation: Long, val done: (Bitmap, Rect) -> Unit)
    private val lock = Any()
    @Volatile private var closed = false
    private var latest: Request? = null
    private var generation = 0L
    private var scheduled = false
    // Accessed only on the decoder executor, including recycle.
    private var decoder: BitmapRegionDecoder? = null
    private var failed = false

    fun request(rect: Rect, sample: Int, done: (Bitmap, Rect) -> Unit) {
        synchronized(lock) {
            if (closed) return
            latest = Request(Rect(rect), sample, ++generation, done)
            if (scheduled) return
            scheduled = true
        }
        worker.execute(::drain)
    }

    private fun drain() {
        while (true) {
            val request = synchronized(lock) {
                if (closed || latest == null) { scheduled = false; null }
                else latest.also { latest = null }
            } ?: return
            val bitmap = runCatching {
                if (closed || failed) return@runCatching null
                val source = decoder ?: store.readImage(entry).let { bytes ->
                    if (closed) return@runCatching null
                    BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false).also { decoder = it }
                }
                if (closed) return@runCatching null
                source.decodeRegion(request.rect, BitmapFactory.Options().apply { inSampleSize = request.sample })
            }.getOrElse {
                failed = true
                main.post { if (!closed) onError() }
                null
            }
            if (bitmap != null) main.post {
                val active = synchronized(lock) { !closed && generation == request.generation }
                if (active) request.done(bitmap, request.rect) else bitmap.recycle()
            }
        }
    }

    override fun close() {
        synchronized(lock) { if (closed) return; closed = true; generation++; latest = null }
        worker.execute { decoder?.recycle(); decoder = null }
    }

    companion object {
        internal const val MAX_REGION_PIXELS = 3_000_000L
        private val main = Handler(Looper.getMainLooper())
        private val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "FloatClip visible image region").apply { isDaemon = true }
        }

        /** Power-of-two decode at screen resolution, with a strict allocation budget. */
        internal fun sample(width: Int, height: Int, scale: Float): Int {
            var sample = 1
            val desired = (1f / scale.coerceAtLeast(.0001f)).toInt().coerceAtLeast(1)
            while (sample * 2 <= desired) sample *= 2
            while (ceil(width.toDouble() / sample) * ceil(height.toDouble() / sample) > MAX_REGION_PIXELS) sample *= 2
            return sample
        }

        internal fun viewport(width: Int, height: Int, viewWidth: Int, viewHeight: Int, scale: Float, x: Float, y: Float): Rect {
            val zoom = scale.coerceAtLeast(.0001f)
            val left = floor((-x / zoom).toDouble()).toInt().coerceIn(0, width - 1)
            val top = floor((-y / zoom).toDouble()).toInt().coerceIn(0, height - 1)
            return Rect(left, top,
                ceil(((viewWidth - x) / zoom).toDouble()).toInt().coerceIn(left + 1, width),
                ceil(((viewHeight - y) / zoom).toDouble()).toInt().coerceIn(top + 1, height))
        }
    }
}
