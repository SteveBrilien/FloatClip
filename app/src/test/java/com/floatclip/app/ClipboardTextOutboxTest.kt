package com.floatclip.app

import android.app.Activity
import com.floatclip.app.security.VaultCipher
import com.floatclip.app.sync.ClipboardTextOutbox
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ClipboardTextOutboxTest {
    private val fake=object:VaultCipher {
        override fun encrypt(plain:ByteArray)=byteArrayOf(0x55)+plain.reversedArray()
        override fun decrypt(payload:ByteArray):ByteArray {
            require(payload.firstOrNull()==0x55.toByte()){"bad ciphertext"}
            return payload.copyOfRange(1,payload.size).reversedArray()
        }
    }
    private fun context()=Robolectric.buildActivity(Activity::class.java).setup().get()
    private fun item(id:Int)=ClipboardTextOutbox.Item("copy-$id","正文 $id",1000L+id,"consent-A")
    @Test fun distinctFastCopiesSurviveProcessRestartInFifoOrder() {
        val app=context();val storage=ClipboardTextOutbox(app,fake)
        val expected=(0 until 7).map(::item)
        storage.save(expected)
        val reloaded=ClipboardTextOutbox(app,fake).load()
        assertEquals(expected,reloaded)
        assertEquals(expected.map{it.stamp},reloaded.map{it.stamp})
        assertFalse(File(app.filesDir,"clipboard_text_queue_v1.bin").readBytes().contentEquals(
            "正文 0".toByteArray()))
        storage.save(reloaded.mapIndexed{index,value -> if(index==0)value.copy(
            dispatched=true,syncId="11111111-1111-1111-1111-111111111111",lastSentAt=2000L) else value})
        assertTrue(storage.load().first().dispatched)
        assertEquals(2000L,storage.load().first().lastSentAt)
        assertEquals("11111111-1111-1111-1111-111111111111",storage.load().first().syncId)
        storage.save(storage.load().drop(1))
        assertEquals("copy-1",storage.load().first().stamp)
        storage.save(emptyList())
        assertTrue(storage.load().isEmpty())
    }
    @Test fun invalidOrOversizedEventsCannotReplaceJournal() {
        val app=context();val storage=ClipboardTextOutbox(app,fake)
        storage.save(listOf(item(1)))
        assertThrows(IllegalArgumentException::class.java) {storage.save(listOf(item(1),item(1)))}
        assertThrows(IllegalArgumentException::class.java) {
            storage.save(listOf(item(2).copy(text="x".repeat(ClipboardTextOutbox.MAX_ITEMS*700))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            storage.save((0..ClipboardTextOutbox.MAX_ITEMS).map(::item))
        }
        assertEquals(listOf(item(1)),storage.load())
    }
    @Test fun corruptedOutboxIsNeverSilentlyDiscarded() {
        val app=context();val storage=ClipboardTextOutbox(app,fake)
        storage.save(listOf(item(1)))
        File(app.filesDir,"clipboard_text_queue_v1.bin").writeText("corrupt")
        assertThrows(Exception::class.java){storage.load()}
        assertEquals("corrupt",File(app.filesDir,"clipboard_text_queue_v1.bin").readText())
    }
}
