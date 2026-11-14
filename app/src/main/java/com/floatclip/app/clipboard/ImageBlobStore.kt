package com.floatclip.app.clipboard

import android.graphics.BitmapFactory
import com.floatclip.app.security.VaultCipher
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Private app sandbox for encoded image files. Read legacy encrypted PNG blobs too. */
internal class ImageBlobStore(private val directory:File,private val cipher:VaultCipher) {
    data class Info(val sha256:String,val bytes:Int,val width:Int,val height:Int,val mimeType:String)
    init { check(directory.isDirectory || directory.mkdirs()) { "无法创建图片存储" } }
    fun validate(encoded:ByteArray):Info {
        require(encoded.size in 45..MAX_IMAGE_BYTES) { "图片大小无效" }
        val isPng=encoded.size>=8 && encoded.copyOfRange(0,8).contentEquals(PNG_SIGNATURE)
        val mime=if(isPng)"image/png" else "image/jpeg"
        val (width,height)=if(isPng) {
            require(ByteBuffer.wrap(encoded,8,4).int==13 && String(encoded,12,4,Charsets.US_ASCII)=="IHDR") { "PNG 图片头无效" }
            require(encoded.copyOfRange(encoded.size-12,encoded.size).contentEquals(IEND)) { "PNG 图片不完整" }
            var offset=8;var hasData=false
            while(offset<encoded.size) {
                require(offset+12<=encoded.size) { "PNG 数据不完整" }
                val size=ByteBuffer.wrap(encoded,offset,4).int
                require(size>=0 && offset.toLong()+size+12<=encoded.size) { "PNG 数据长度无效" }
                val crc=java.util.zip.CRC32().apply { update(encoded,offset+4,size+4) }.value
                require(crc==(ByteBuffer.wrap(encoded,offset+8+size,4).int.toLong() and 0xffffffffL)) { "PNG 数据校验失败" }
                val type=String(encoded,offset+4,4,Charsets.US_ASCII)
                if(type=="IDAT")hasData=true
                if(type=="IEND")require(size==0 && offset+12==encoded.size) { "PNG 尾部无效" }
                offset+=size+12
            }
            require(hasData) { "PNG 没有图像数据" }
            ByteBuffer.wrap(encoded,16,4).int to ByteBuffer.wrap(encoded,20,4).int
        } else {
            require(encoded.size>=4 && encoded[0]==0xff.toByte() && encoded[1]==0xd8.toByte() &&
                encoded[encoded.lastIndex-1]==0xff.toByte() && encoded.last()==0xd9.toByte()) { "只接受 JPEG 或 PNG 图片" }
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            BitmapFactory.decodeByteArray(encoded,0,encoded.size,bounds)
            bounds.outWidth to bounds.outHeight
        }
        require(width in 1..16384 && height in 1..16384 && width.toLong()*height<=MAX_PIXELS) { "图片尺寸超过 6400 万像素" }
        val hash=MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
        return Info(hash,encoded.size,width,height,mime)
    }
    private fun file(reference:String):File {
        require(reference.matches(Regex("[a-f0-9]{32}"))) { "图片引用无效" }
        return File(directory,"$reference.bin")
    }
    fun write(encoded:ByteArray,reference:String=UUID.randomUUID().toString().replace("-","")):String {
        validate(encoded)
        val destination=file(reference)
        val stored=directory.listFiles().orEmpty().sumOf { it.length() }-destination.length()
        require(stored+encoded.size<=MAX_STORED_BYTES) { "图片空间不足，请整理普通记录后重试" }
        val temporary=File(directory,"$reference.tmp")
        try {
            // Sandbox storage is private. Avoid costly hardware keystore crypto for large
            // photos; encryption remains mandatory for metadata, pairing and the network.
            temporary.outputStream().use { it.write(encoded);it.fd.sync() }
            check(temporary.renameTo(destination)) { "图片未保存" }
        } finally {temporary.delete()}
        return reference
    }
    fun read(reference:String,expectedSha256:String?=null):ByteArray {
        val source=file(reference)
        require(source.length() in 1..MAX_IMAGE_BYTES+65536L) { "图片存储大小无效" }
        val bytes=source.readBytes()
        val encoded=if(bytes.size>=2 && (bytes[0]==0xff.toByte() && bytes[1]==0xd8.toByte() ||
                bytes.size>=8 && bytes.copyOfRange(0,8).contentEquals(PNG_SIGNATURE))) bytes
            else cipher.decrypt(bytes) // Old encrypted PNG, read without rewriting.
        val info=validate(encoded)
        require(expectedSha256==null || info.sha256==expectedSha256) { "图片校验失败，请保留本机数据" }
        return encoded
    }
    fun delete(reference:String){runCatching{file(reference).delete()}}
    fun prune(references:Set<String>){directory.listFiles().orEmpty().filter{it.extension=="bin"&&it.nameWithoutExtension !in references}.forEach{it.delete()}}
    companion object {
        const val MAX_IMAGE_BYTES=32*1024*1024
        const val MAX_ACTIVE_BYTES=128*1024*1024
        const val MAX_STORED_BYTES=256L*1024*1024
        const val MAX_PIXELS=64_000_000L
        private val PNG_SIGNATURE=byteArrayOf(137.toByte(),80,78,71,13,10,26,10)
        private val IEND=byteArrayOf(0,0,0,0,73,69,78,68,174.toByte(),66,96,130.toByte())
    }
}
