package com.floatclip.app

import com.floatclip.app.capture.CaptureFrame
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class CaptureFrameTest {
    private fun frame(size:Int,bytes:ByteArray,excluded:Boolean=false,stamp:String="123:"+"a".repeat(64)):DataInputStream {
        val buffer=ByteArrayOutputStream()
        DataOutputStream(buffer).apply{writeUTF(stamp);writeBoolean(excluded);writeInt(size);write(bytes)}
        return DataInputStream(ByteArrayInputStream(buffer.toByteArray()))
    }
    @Test fun ordinaryAppsCannotBootstrapThePrivilegedCaptureCredential() {
        for(uid in listOf(0,1000,10000,10230))assertThrows(SecurityException::class.java){
            com.floatclip.app.capture.CaptureBootstrapProvider.checkShell(uid)
        }
        com.floatclip.app.capture.CaptureBootstrapProvider.checkShell(2000)
    }
    @Test fun preservesExactUnicodeAndSensitiveExclusion() {
        val text="  中文 📋\r\n";val bytes=text.toByteArray(Charsets.UTF_8)
        assertEquals(text,CaptureFrame.read(frame(bytes.size,bytes)).text)
        assertTrue(CaptureFrame.read(frame(0,byteArrayOf(),true)).excluded)
    }
    @Test fun rejectsOversizeNegativeMalformedOrPrivatePayloadBeforeDispatch() {
        for(size in listOf(-1,64001))assertThrows(IllegalArgumentException::class.java){CaptureFrame.read(frame(size,byteArrayOf()))}
        assertThrows(IllegalArgumentException::class.java){CaptureFrame.read(frame(1,byteArrayOf(65),true))}
        assertThrows(IllegalArgumentException::class.java){CaptureFrame.read(frame(1,byteArrayOf(0xFF.toByte())))}
        assertThrows(IllegalArgumentException::class.java){CaptureFrame.read(frame(1,byteArrayOf(65),stamp="not-an-event"))}
    }
}
