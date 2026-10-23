package com.floatclip.app.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

/** Bounded, read-only decoders. No extraction, active document execution or external application. */
object FilePreviewContent {
    data class Page(val bitmap: Bitmap, val count: Int)
    enum class Kind { IMAGE, PDF, ZIP, TEXT, UNSUPPORTED }
    fun kind(name: String): Kind = when (name.substringAfterLast('.', "").lowercase()) {
        "png", "jpg", "jpeg", "webp", "gif", "bmp" -> Kind.IMAGE
        "pdf" -> Kind.PDF
        "zip" -> Kind.ZIP
        "txt", "md", "csv", "tsv", "log", "json", "xml", "html", "htm", "yaml", "yml", "ini", "conf", "kt", "java", "cs", "py", "js", "ts", "css", "sql", "sh" -> Kind.TEXT
        else -> Kind.UNSUPPORTED
    }
    fun image(file: File): Bitmap {
        require(file.length() <= 64L * 1024 * 1024) { "图片文件过大" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片无法解码" }
        var sample = 1
        while (bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > 8_000_000 || maxOf(bounds.outWidth, bounds.outHeight) / sample > 8192) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: throw IOException("图片无法解码")
    }
    fun pdf(file: File, index: Int): Page {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { pdf ->
                require(pdf.pageCount > 0) { "PDF 没有可预览的页面" }
                pdf.openPage(index.coerceIn(0, pdf.pageCount - 1)).use { page ->
                    val scale = minOf(2f, 2048f / maxOf(page.width, page.height))
                    val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    try { bitmap.eraseColor(Color.WHITE); page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                    catch (e: Exception) { bitmap.recycle(); throw e }
                    return Page(bitmap, pdf.pageCount)
                }
            }
        }
    }
    fun text(file: File): String {
        val data = file.inputStream().use { it.readBytesBounded(1_048_576) }
        require(data.none { it == 0.toByte() }) { "这是二进制或暂不支持编码的文本文件" }
        val decoded = runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString() }
            .getOrElse { charset("GB18030").newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString() }
        return decoded.removePrefix("\uFEFF")
    }
    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val n = read(buffer); if (n < 0) break; require(out.size() + n <= limit) { "文本超过 1 MB，暂不支持完整预览" }; out.write(buffer, 0, n) }
        return out.toByteArray()
    }
    fun zip(file: File): String {
        fun list(charset: java.nio.charset.Charset): String = ZipFile(file, charset).use { zip ->
            val entries = zip.entries(); val out = StringBuilder(); var count = 0
            while (entries.hasMoreElements() && count < 500 && out.length < 100_000) {
                val entry = entries.nextElement(); out.append(entry.name.take(512)).append(if (entry.isDirectory) "/" else "  ·  ${com.floatclip.app.ui.FileUi.size(entry.size.coerceAtLeast(0))}").append('\n'); count++
            }
            if (entries.hasMoreElements()) out.append("\n仅显示前 $count 项，未解压文件。")
            if (count == 0) "空压缩包" else out.toString()
        }
        return try { list(Charsets.UTF_8) } catch (_: IllegalArgumentException) { list(charset("GB18030")) }
    }
}
