package com.floatclip.app.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.floatclip.app.security.PortableVaultCrypto
import com.floatclip.app.security.VaultSettings

class VaultBackupManager(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver
    private val settings = VaultSettings(context)

    fun backupIfConfigured(vaultJson: String): Boolean {
        if (!settings.backupWriteArmed()) return false
        val tree = settings.backupTreeUri() ?: return false
        val pin = settings.pin() ?: return false
        return backup(tree, pin, vaultJson)
    }

    fun hasBackup(treeUri: Uri): Boolean = runCatching { findVault(treeUri) != null }.getOrDefault(false)

    fun initializeBackup(treeUri: Uri, pin: String, vaultJson: String): Boolean {
        if (hasBackup(treeUri)) return false
        val ok = backup(treeUri, pin, vaultJson)
        if (ok) settings.setBackupWriteArmed(true)
        return ok
    }

    fun backup(treeUri: Uri, pin: String, vaultJson: String): Boolean = runCatching {
        val target = findOrCreateVault(treeUri) ?: return@runCatching false
        val envelope = PortableVaultCrypto.encrypt(vaultJson, pin)
        resolver.openOutputStream(target, "wt")!!.bufferedWriter(Charsets.UTF_8).use { it.write(envelope) }
        true
    }.getOrDefault(false)

    fun restore(treeUri: Uri, pin: String): String? = runCatching {
        val target = findVault(treeUri) ?: return@runCatching null
        val envelope = resolver.openInputStream(target)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
        PortableVaultCrypto.decrypt(envelope, pin)
    }.getOrNull()

    private fun findOrCreateVault(treeUri: Uri): Uri? {
        findVault(treeUri)?.let { return it }
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)
        return DocumentsContract.createDocument(resolver, parent, "application/octet-stream", FILE_NAME)
    }

    private fun findVault(treeUri: Uri): Uri? {
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        resolver.query(children, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == FILE_NAME) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIndex))
                }
            }
        }
        return null
    }

    companion object {
        const val FILE_NAME = "FloatClip.vault"
    }
}
