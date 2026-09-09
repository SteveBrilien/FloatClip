package com.floatclip.app.clipboard

import android.content.Context
import com.floatclip.app.backup.VaultBackupManager
import com.floatclip.app.model.ClipEntry
import com.floatclip.app.prefs.CategoryStore
import com.floatclip.app.security.LocalVaultCrypto
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ClipStore(context: Context) {
    private val appContext = context.applicationContext
    private val legacyPrefs = appContext.getSharedPreferences("floatclip_store", Context.MODE_PRIVATE)
    private val vaultFile = File(appContext.filesDir, "floatclip_vault.bin")
    private val crypto = LocalVaultCrypto()
    private val categoryStore = CategoryStore(appContext)

    init { migrateLegacyIfNeeded() }

    fun entries(): List<ClipEntry> = parseEntries(readVaultJson())

    fun addText(text: String): ClipEntry? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val current = entries().toMutableList()
        val duplicate = current.firstOrNull { it.text == trimmed }
        if (duplicate != null) current.remove(duplicate)
        val inferredCategory = ClipClassifier.classify(trimmed, categoryStore.categories()) ?: CategoryStore.DEFAULT_CATEGORY
        val entry = duplicate?.copy(createdAt = System.currentTimeMillis())
            ?: ClipEntry(id = System.currentTimeMillis(), text = trimmed, category = inferredCategory)
        current.add(0, entry)
        persist(current.take(MAX_ITEMS))
        return entry
    }

    fun togglePinned(id: Long) { persist(entries().map { if (it.id == id) it.copy(pinned = !it.pinned) else it }) }

    fun updateCategory(id: Long, category: String) {
        val clean = category.trim().ifEmpty { CategoryStore.DEFAULT_CATEGORY }
        persist(entries().map { if (it.id == id) it.copy(category = clean) else it })
    }

    fun moveCategoryToDefault(category: String) {
        persist(entries().map { if (it.category == category) it.copy(category = CategoryStore.DEFAULT_CATEGORY) else it })
    }

    fun delete(id: Long) { persist(entries().filterNot { it.id == id }) }
    fun clearUnpinned() { persist(entries().filter { it.pinned }) }
    fun clearAll() { persist(emptyList()) }
    fun exportJson(): String = buildJson(entries())

    fun importJson(raw: String): Boolean {
        val parsed = parseEntries(raw)
        if (parsed.isEmpty() && runCatching { JSONArray(raw).length() }.getOrNull() != 0) return false
        persist(parsed.take(MAX_ITEMS))
        return true
    }

    private fun persist(items: List<ClipEntry>) {
        val raw = buildJson(items)
        val encrypted = crypto.encrypt(raw.toByteArray(Charsets.UTF_8))
        val temp = File(vaultFile.parentFile, "${vaultFile.name}.tmp")
        temp.writeBytes(encrypted)
        if (!temp.renameTo(vaultFile)) {
            vaultFile.writeBytes(encrypted)
            temp.delete()
        }
        Thread { runCatching { VaultBackupManager(appContext).backupIfConfigured(raw) } }.start()
    }

    private fun readVaultJson(): String {
        if (!vaultFile.exists()) return "[]"
        return runCatching { crypto.decrypt(vaultFile.readBytes()).toString(Charsets.UTF_8) }.getOrDefault("[]")
    }

    private fun parseEntries(raw: String): List<ClipEntry> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(ClipEntry(
                    id = o.getLong("id"),
                    text = o.getString("text"),
                    pinned = o.optBoolean("pinned", false),
                    category = o.optString("category", CategoryStore.DEFAULT_CATEGORY),
                    createdAt = o.optLong("createdAt", 0L),
                ))
            }
        }.sortedWith(compareByDescending<ClipEntry> { it.pinned }.thenByDescending { it.createdAt })
    }.getOrDefault(emptyList())

    private fun buildJson(items: List<ClipEntry>): String {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject()
                .put("id", item.id)
                .put("text", item.text)
                .put("pinned", item.pinned)
                .put("category", item.category)
                .put("createdAt", item.createdAt))
        }
        return array.toString()
    }

    private fun migrateLegacyIfNeeded() {
        if (vaultFile.exists()) return
        val legacy = legacyPrefs.getString(KEY_ITEMS, null) ?: return
        val migrated = parseEntries(legacy)
        if (migrated.isEmpty() && runCatching { JSONArray(legacy).length() }.getOrNull() != 0) return
        persist(migrated)
        legacyPrefs.edit().remove(KEY_ITEMS).apply()
    }

    companion object {
        private const val KEY_ITEMS = "items"
        private const val MAX_ITEMS = 500
    }
}
