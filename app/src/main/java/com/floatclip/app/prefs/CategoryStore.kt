package com.floatclip.app.prefs

import android.content.Context
import org.json.JSONArray

class CategoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("floatclip_categories", Context.MODE_PRIVATE)

    fun categories(): List<String> {
        val ordered = readOrdered()
        val custom = if (ordered != null) {
            ordered
        } else {
            val legacy = prefs.getStringSet(KEY_CATEGORIES, null)
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() && it != DEFAULT_CATEGORY }
                ?.distinct()
                ?.sorted()
            val migrated = legacy ?: DEFAULTS
            persist(migrated)
            migrated
        }
        return listOf(DEFAULT_CATEGORY) + custom.filterNot { it == DEFAULT_CATEGORY }.distinct()
    }

    fun add(name: String): Boolean {
        val clean = name.trim().take(MAX_LENGTH)
        if (clean.isEmpty() || clean == DEFAULT_CATEGORY) return false
        val list = categories().filterNot { it == DEFAULT_CATEGORY }.toMutableList()
        if (clean in list) return false
        list.add(clean)
        persist(list)
        return true
    }

    fun delete(name: String): Boolean {
        if (name == DEFAULT_CATEGORY) return false
        val list = categories().filterNot { it == DEFAULT_CATEGORY }.toMutableList()
        val changed = list.remove(name)
        if (changed) persist(list)
        return changed
    }

    fun move(name: String, delta: Int): Boolean {
        if (name == DEFAULT_CATEGORY || delta == 0) return false
        val list = categories().filterNot { it == DEFAULT_CATEGORY }.toMutableList()
        val from = list.indexOf(name)
        if (from < 0) return false
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (to == from) return false
        val value = list.removeAt(from)
        list.add(to, value)
        persist(list)
        return true
    }

    fun replaceOrder(names: List<String>): Boolean {
        val clean = names.map { it.trim().take(MAX_LENGTH) }
            .filter { it.isNotEmpty() && it != DEFAULT_CATEGORY }
            .distinct()
        val current = categories().filterNot { it == DEFAULT_CATEGORY }
        if (clean == current || clean.toSet() != current.toSet()) return false
        persist(clean)
        return true
    }

    private fun readOrdered(): List<String>? {
        val raw = prefs.getString(KEY_ORDERED_CATEGORIES, null) ?: return null
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val value = array.optString(i).trim()
                    if (value.isNotEmpty() && value != DEFAULT_CATEGORY && value !in this) add(value)
                }
            }
        }.getOrNull()
    }

    private fun persist(items: List<String>) {
        val clean = items.map { it.trim().take(MAX_LENGTH) }
            .filter { it.isNotEmpty() && it != DEFAULT_CATEGORY }
            .distinct()
        val array = JSONArray()
        clean.forEach(array::put)
        prefs.edit()
            .putString(KEY_ORDERED_CATEGORIES, array.toString())
            .remove(KEY_CATEGORIES)
            .apply()
    }

    companion object {
        const val DEFAULT_CATEGORY = "未分类"
        private const val KEY_CATEGORIES = "categories"
        private const val KEY_ORDERED_CATEGORIES = "categories_ordered_v2"
        private const val MAX_LENGTH = 24
        private val DEFAULTS = listOf("常用", "网址", "电话", "邮箱", "地址", "验证码", "工作")
    }
}
