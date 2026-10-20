package com.floatclip.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.floatclip.app.clipboard.ClipboardRepository
import com.floatclip.app.clipboard.ClipboardWrites
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ClipboardWritesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(ClipboardManager::class.java)

    @Test fun copyingRemoteHistoryDoesNotRecaptureItAsLocalButExplicitReadsStillWork() {
        val repository=ClipboardRepository(context)
        repository.writeText("来自电脑的正文")
        assertNull(repository.readCurrentText(forCapture=true))
        assertEquals("来自电脑的正文",repository.readCurrentText())
    }

    @Test fun anExternalAppCopyingIdenticalTextIsStillCaptured() {
        val repository=ClipboardRepository(context)
        repository.writeText("相同正文")
        manager.setPrimaryClip(ClipData.newPlainText("Other app","相同正文"))
        assertEquals("相同正文",repository.readCurrentText(forCapture=true))
        assertFalse(ClipboardWrites.isCurrentOwn(context))
    }

    @Test fun newRepositoryStillRecognizesOwnClipEventAndAcceptsLaterExternalContent() {
        ClipboardRepository(context).writeText("旧的复制")
        assertNull(ClipboardRepository(context).readCurrentText(forCapture=true))
        manager.setPrimaryClip(ClipData.newPlainText("Other app","新的复制"))
        assertEquals("新的复制",ClipboardRepository(context).readCurrentText(forCapture=true))
    }
}
