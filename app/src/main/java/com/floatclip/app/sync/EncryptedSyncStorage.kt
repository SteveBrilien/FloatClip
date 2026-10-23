package com.floatclip.app.sync

import android.content.Context
import android.util.AtomicFile
import com.floatclip.app.security.LocalVaultCrypto
import com.floatclip.share.ClipboardSync
import java.io.File

/** The queued clipboard text is protected with the same Android Keystore vault codec. */
class EncryptedSyncStorage(context: Context): ClipboardSync.Storage {
    private val file=AtomicFile(File(context.filesDir,"clipboard_sync.v1.bin"))
    private val cipher=LocalVaultCrypto()
    override fun load(): ByteArray? {
        if(!file.baseFile.exists())return null
        check(file.baseFile.length()<=700000) { "同步状态过大" }
        return cipher.decrypt(file.readFully())
    }
    override fun save(bytes: ByteArray) {
        val output=file.startWrite()
        try { output.write(cipher.encrypt(bytes));file.finishWrite(output) }
        catch(e: Exception){file.failWrite(output);throw e}
    }
}
