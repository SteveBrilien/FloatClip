package com.floatclip.app.clipboard

import android.content.Context
import com.floatclip.app.backup.VaultBackupManager
import com.floatclip.app.model.ClipEntry
import com.floatclip.app.prefs.CategoryStore
import com.floatclip.app.security.LocalVaultCrypto
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class ClipStore(context: Context, private val crypto: com.floatclip.app.security.VaultCipher = LocalVaultCrypto()) {
    private val appContext = context.applicationContext
    private val legacyPrefs = appContext.getSharedPreferences("floatclip_store", Context.MODE_PRIVATE)
    private val vaultFile = File(appContext.filesDir, "floatclip_vault.bin")
    private val categoryStore = CategoryStore(appContext)

    init { migrateLegacyIfNeeded() }

    fun entries(): List<ClipEntry> = synchronized(LOCK) { parseEntries(readVaultJson()) }

    fun addText(text: String): ClipEntry? = synchronized(LOCK) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@synchronized null
        val current = entries().toMutableList()
        val duplicate = current.firstOrNull { it.text == trimmed && it.sourceDeviceId == "local" }
        if (duplicate != null) current.remove(duplicate)
        val inferredCategory = ClipClassifier.classify(trimmed, categoryStore.categories()) ?: CategoryStore.DEFAULT_CATEGORY
        val entry = duplicate?.copy(createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
            ?: ClipEntry(id = maxOf(System.currentTimeMillis(), (current.maxOfOrNull { it.id } ?: 0L) + 1), text = trimmed, category = inferredCategory)
        current.add(0, entry)
        persist(current.take(MAX_ITEMS))
        entry
    }

    fun togglePinned(id: Long) = synchronized(LOCK) { persist(entries().map { if (it.id == id) it.copy(pinned = !it.pinned) else it }) }

    fun updateCategory(id: Long, category: String) = synchronized(LOCK) {
        val clean = category.trim().ifEmpty { CategoryStore.DEFAULT_CATEGORY }
        persist(entries().map { if (it.id == id) it.copy(category = clean) else it })
    }

    fun moveCategoryToDefault(category: String) = synchronized(LOCK) {
        persist(entries().map { if (it.category == category) it.copy(category = CategoryStore.DEFAULT_CATEGORY) else it })
    }

    fun delete(id: Long) = synchronized(LOCK) { persist(entries().filterNot { it.id == id }) }
    fun clearUnpinned() = synchronized(LOCK) { persist(entries().filter { it.pinned }) }
    fun clearAll() = synchronized(LOCK) { persist(emptyList()) }
    fun exportJson(): String = buildJson(entries())

    fun importJson(raw: String): Boolean = synchronized(LOCK) {
        val parsed = parseEntries(raw)
        if (parsed.isEmpty() && runCatching { JSONArray(raw).length() }.getOrNull() != 0) return@synchronized false
        persist(parsed.take(MAX_ITEMS), allowRecovery = true)
        true
    }

    private fun persist(items: List<ClipEntry>, allowRecovery: Boolean = false) {
        // Never replace a damaged or unreadable vault with an empty snapshot.
        if (vaultFile.exists() && !allowRecovery) readStrict()
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
                    key = o.optString("key", o.getLong("id").toString()),
                    updatedAt = o.optLong("updatedAt", o.optLong("createdAt", 0L)),
                    sourceDeviceId = o.optJSONObject("source")?.optString("id", "local") ?: "local",
                    sourceDeviceName = o.optJSONObject("source")?.optString("name", "本机") ?: "本机",
                    sourcePlatform = o.optJSONObject("source")?.optString("platform", "android") ?: "android",
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
                .put("createdAt", item.createdAt)
                .put("key", item.key).put("updatedAt", item.updatedAt)
                .put("source", JSONObject().put("id", item.sourceDeviceId)
                    .put("name", item.sourceDeviceName).put("platform", item.sourcePlatform)))
        }
        return array.toString()
    }

    private fun readStrict(): String {
        if (!vaultFile.exists()) return "[]"
        val raw = crypto.decrypt(vaultFile.readBytes()).toString(Charsets.UTF_8)
        check(parseEntries(raw).size == JSONArray(raw).length()) { "本机剪贴板损坏，请保留数据并从备份恢复" }
        return raw
    }

    fun unifiedSnapshot(): JSONObject = synchronized(LOCK) {
        val raw = readStrict()
        val revision = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val array = JSONArray()
        val device = DeviceIdentity.json(appContext)
        parseEntries(raw).forEach { item ->
            val source = if (item.sourceDeviceId == "local") device else JSONObject()
                .put("id", item.sourceDeviceId).put("name", item.sourceDeviceName).put("platform", item.sourcePlatform)
            array.put(JSONObject().put("id", item.key).put("text", item.text).put("pinned", item.pinned)
                .put("category", item.category).put("createdAt", item.createdAt).put("updatedAt", item.updatedAt)
                .put("source", source))
        }
        JSONObject().put("revision", revision).put("entries", array)
    }

    fun commitUnified(revision: String, array: JSONArray): JSONObject = synchronized(LOCK) {
        check(unifiedSnapshot().getString("revision") == revision) { "VAULT_CONFLICT" }
        check(array.length() <= MAX_ITEMS && array.toString().toByteArray(Charsets.UTF_8).size <= 16 * 1024 * 1024) { "剪贴板库过大" }
        val current = entries().associateBy { it.key }
        var nextId = maxOf(System.currentTimeMillis(), (current.values.maxOfOrNull { it.id } ?: 0L) + 1)
        val keys = mutableSetOf<String>()
        val ownId = DeviceIdentity.id(appContext)
        val next = (0 until array.length()).map { index ->
            val o = array.getJSONObject(index)
            val key = o.getString("id")
            val text = o.getString("text")
            val source = o.getJSONObject("source")
            val sourceId = source.getString("id")
            val name = source.getString("name")
            val platform = source.getString("platform")
            val category = o.getString("category")
            val created = o.getLong("createdAt")
            val updated = o.getLong("updatedAt")
            require(key.matches(Regex("[-a-zA-Z0-9_:]{1,128}")) && keys.add(key)) { "记录编号无效" }
            require(text.isNotBlank() && text.length <= 1000000 && category.length <= 80 && created >= 0 && updated >= 0) { "记录格式无效" }
            require(sourceId.matches(Regex("[-a-zA-Z0-9_:]{1,100}")) && name.isNotBlank() && name.length <= 80 && platform in listOf("android", "windows", "ipad", "web")) { "设备来源无效" }
            ClipEntry(current[key]?.id ?: nextId++, text, o.getBoolean("pinned"), category.ifBlank { CategoryStore.DEFAULT_CATEGORY },
                created, key, updated, if (sourceId == ownId) "local" else sourceId, name, platform)
        }
        persist(next)
        unifiedSnapshot()
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
        private val LOCK = Any()
        private const val KEY_ITEMS = "items"
        private const val MAX_ITEMS = 500
    }
}
