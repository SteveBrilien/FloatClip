package com.floatclip.app.security

import android.content.Context
import android.net.Uri
import android.util.Base64

class VaultSettings(context: Context) {
    private val prefs = context.getSharedPreferences("floatclip_security", Context.MODE_PRIVATE)
    private val localCrypto = LocalVaultCrypto()

    fun hasPin(): Boolean = !prefs.getString(KEY_PIN, null).isNullOrBlank()

    fun pin(): String? {
        val encoded = prefs.getString(KEY_PIN, null) ?: return null
        return runCatching {
            val decrypted = localCrypto.decrypt(Base64.decode(encoded, Base64.NO_WRAP)).toString(Charsets.UTF_8)
            decrypted.takeIf { it.matches(Regex("\\d{6}")) }
        }.getOrNull()
    }

    fun savePin(pin: String) {
        require(pin.matches(Regex("\\d{6}")))
        val encrypted = localCrypto.encrypt(pin.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(KEY_PIN, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    fun backupTreeUri(): Uri? = prefs.getString(KEY_BACKUP_TREE, null)?.let(Uri::parse)

    /**
     * A newly selected tree is deliberately write-locked until FloatClip has checked whether it
     * already contains a portable vault. This prevents a reinstall from overwriting the only
     * surviving backup before the user has had a chance to restore it.
     */
    fun saveBackupTreeUri(uri: Uri, writeArmed: Boolean = false) {
        prefs.edit()
            .putString(KEY_BACKUP_TREE, uri.toString())
            .putBoolean(KEY_BACKUP_WRITE_ARMED, writeArmed)
            .apply()
    }

    fun backupWriteArmed(): Boolean = prefs.getBoolean(KEY_BACKUP_WRITE_ARMED, false)

    fun setBackupWriteArmed(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BACKUP_WRITE_ARMED, enabled).apply()
    }

    fun syncEndpoint(): String = prefs.getString(KEY_SYNC_ENDPOINT, "").orEmpty()

    fun saveSyncEndpoint(value: String) {
        prefs.edit().putString(KEY_SYNC_ENDPOINT, value.trim()).apply()
    }

    companion object {
        private const val KEY_PIN = "portable_pin_wrapped"
        private const val KEY_BACKUP_TREE = "backup_tree_uri"
        private const val KEY_BACKUP_WRITE_ARMED = "backup_write_armed"
        private const val KEY_SYNC_ENDPOINT = "sync_endpoint"
    }
}
