package com.floatclip.app.capture

import java.io.DataInputStream

/** Only plain UTF-8 text and bounded event metadata cross the shell/app boundary. */
data class CaptureFrame(val stamp:String,val text:String,val excluded:Boolean) {
    companion object {
        fun read(input:DataInputStream):CaptureFrame {
            val stamp=input.readUTF()
            val excluded=input.readBoolean()
            val size=input.readInt()
            require(stamp.matches(Regex("[0-9]{1,20}:[a-f0-9]{64}"))&&size in 0..64000&&(!excluded||size==0))
            val bytes=ByteArray(size);input.readFully(bytes)
            val text=bytes.toString(Charsets.UTF_8)
            if(!excluded)require(text.isNotBlank()&&text.toByteArray(Charsets.UTF_8).contentEquals(bytes))
            return CaptureFrame(stamp,text,excluded)
        }
    }
}
