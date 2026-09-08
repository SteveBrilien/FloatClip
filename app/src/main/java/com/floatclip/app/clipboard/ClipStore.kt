package com.floatclip.app.clipboard

import android.content.Context
import com.floatclip.app.model.ClipEntry
import com.floatclip.app.prefs.CategoryStore
import org.json.JSONArray
import org.json.JSONObject

class ClipStore(context: Context) {
    private val prefs = context.getSharedPreferences("floatclip_store", Context.MODE_PRIVATE)

    fun entries(): List<ClipEntry> {
        val raw = prefs.getString(KEY_ITEMS, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(
                        ClipEntry(
                            id = o.getLong("id"),
                            text = o.getString("text"),
                            pinned = o.optBoolean("pinned", false),
                            category = o.optString("category", CategoryStore.DEFAULT_CATEGORY),
                            createdAt = o.optLong("createdAt", 0L),
                        ),
                    )
                }
            }.sortedWith(compareByDescending<ClipEntry> { it.pinned }.thenByDescending { it.createdAt })
        }.getOrDefault(emptyList())
    }

    fun addText(text: String): ClipEntry? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val current = entries().toMutableList()
        val duplicate = current.firstOrNull { it.text == trimmed }
        if (duplicate != null) current.remove(duplicate)
        val entry = duplicate?.copy(createdAt = System.currentTimeMillis())
            ?: ClipEntry(id = System.currentTimeMillis(), text = trimmed)
        current.add(0, entry)
        persist(current.take(MAX_ITEMS))
        return entry
    }

    fun togglePinned(id: Long) {
        persist(entries().map { if (it.id == id) it.copy(pinned = !it.pinned) else it })
    }

    fun updateCategory(id: Long, category: String) {
        val clean = category.trim().ifEmpty { CategoryStore.DEFAULT_CATEGORY }
        persist(entries().map { if (it.id == id) it.copy(category = clean) else it })
    }

    fun moveCategoryToDefault(category: String) {
        persist(entries().map {
            if (it.category == category) it.copy(category = CategoryStore.DEFAULT_CATEGORY) else it
        })
    }

    fun delete(id: Long) {
        persist(entries().filterNot { it.id == id })
    }

    fun clearUnpinned() {
        persist(entries().filter { it.pinned })
    }

    fun clearAll() {
        persist(emptyList())
    }

    private fun persist(items: List<ClipEntry>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("text", item.text)
                    .put("pinned", item.pinned)
                    .put("category", item.category)
                    .put("createdAt", item.createdAt),
            )
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    companion object {
        private const val KEY_ITEMS = "items"
        private const val MAX_ITEMS = 200
    }
}
