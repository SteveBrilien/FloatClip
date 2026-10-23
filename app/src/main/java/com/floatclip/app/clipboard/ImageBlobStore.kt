package com.floatclip.app.clipboard

import com.floatclip.app.security.VaultCipher
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Encrypted attachments are independent of the small metadata vault. No plaintext file is written. */
internal class ImageBlobStore(private val directory:File,private val cipher:VaultCipher) {
    data class Info(val sha256:String,val bytes:Int,val width:Int,val height:Int)
    init { check(directory.isDirectory || directory.mkdirs()) { "无法创建图片存储" } }

    fun validate(png:ByteArray):Info {
        require(png.size in 45..MAX_IMAGE_BYTES && png.take(8).toByteArray().contentEquals(SIGNATURE)) { "图片必须是 PNG，且不超过 8 MiB" }
        require(ByteBuffer.wrap(png,8,4).int==13 && String(png,12,4,Charsets.US_ASCII)=="IHDR") { "PNG 图片头无效" }
        val width=ByteBuffer.wrap(png,16,4).int
        val height=ByteBuffer.wrap(png,20,4).int
        require(width in 1..16384 && height in 1..16384 && width.toLong()*height<=MAX_PIXELS) { "图片尺寸超过 6400 万像素" }
        require(png.copyOfRange(png.size-12,png.size).contentEquals(IEND)) { "PNG 图片不完整" }
        var offset=8;var data=false
        while(offset<png.size) {
            require(offset+12<=png.size) { "PNG 数据不完整" }
            val size=ByteBuffer.wrap(png,offset,4).int
            require(size>=0 && offset.toLong()+size+12<=png.size) { "PNG 数据长度无效" }
            val crc=java.util.zip.CRC32().apply { update(png,offset+4,size+4) }.value
            require(crc==(ByteBuffer.wrap(png,offset+8+size,4).int.toLong() and 0xffffffffL)) { "PNG 数据校验失败" }
            val type=String(png,offset+4,4,Charsets.US_ASCII)
            if(type=="IDAT")data=true
            if(type=="IEND")require(size==0 && offset+12==png.size) { "PNG 尾部无效" }
            offset+=size+12
        }
        require(data) { "PNG 没有图像数据" }
        return Info(MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it.toInt() and 255) },png.size,width,height)
    }

    private fun file(reference:String):File {
        require(reference.matches(Regex("[a-f0-9]{32}"))) { "图片引用无效" }
        return File(directory,"$reference.bin")
    }
    fun write(png:ByteArray,reference:String=UUID.randomUUID().toString().replace("-","")):String {
        validate(png)
        val destination=file(reference)
        val encrypted=cipher.encrypt(png)
        val stored=directory.listFiles().orEmpty().sumOf { it.length() }-destination.length()
        require(stored+encrypted.size<=MAX_STORED_BYTES) { "图片空间不足，请整理普通记录后重试" }
        val temporary=File(directory,"$reference.tmp")
        try {
            temporary.outputStream().use { it.write(encrypted);it.fd.sync() }
            check(temporary.renameTo(destination)) { "图片未保存" }
        } finally { temporary.delete() }
        return reference
    }
    fun read(reference:String,expectedSha256:String?=null):ByteArray {
        val source=file(reference)
        require(source.length() in 1..MAX_IMAGE_BYTES+65536L) { "图片存储大小无效" }
        val png=cipher.decrypt(source.readBytes())
        val actual=validate(png).sha256
        require(expectedSha256==null || actual==expectedSha256) { "图片校验失败，请保留本机数据" }
        return png
    }
    fun delete(reference:String) { runCatching { file(reference).delete() } }
    fun prune(references:Set<String>) {
        directory.listFiles().orEmpty().filter { it.extension=="bin" && it.nameWithoutExtension !in references }.forEach { it.delete() }
    }
    companion object {
        const val MAX_IMAGE_BYTES=8*1024*1024
        const val MAX_ACTIVE_BYTES=32*1024*1024
        const val MAX_STORED_BYTES=64L*1024*1024
        const val MAX_PIXELS=64_000_000L
        private val SIGNATURE=byteArrayOf(137.toByte(),80,78,71,13,10,26,10)
        private val IEND=byteArrayOf(0,0,0,0,73,69,78,68,174.toByte(),66,96,130.toByte())
    }
}
