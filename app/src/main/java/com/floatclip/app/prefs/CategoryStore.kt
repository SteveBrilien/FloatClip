package com.floatclip.app.prefs

import android.content.Context

class CategoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("floatclip_categories", Context.MODE_PRIVATE)

    fun categories(): List<String> {
        val saved = prefs.getStringSet(KEY_CATEGORIES, null)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?.sorted()
        return listOf(DEFAULT_CATEGORY) + (saved ?: DEFAULTS).filterNot { it == DEFAULT_CATEGORY }
    }

    fun add(name: String): Boolean {
        val clean = name.trim().take(MAX_LENGTH)
        if (clean.isEmpty() || clean == DEFAULT_CATEGORY) return false
        val set = categories().filterNot { it == DEFAULT_CATEGORY }.toMutableSet()
        val changed = set.add(clean)
        if (changed) prefs.edit().putStringSet(KEY_CATEGORIES, set).apply()
        return changed
    }

    fun delete(name: String): Boolean {
        if (name == DEFAULT_CATEGORY) return false
        val set = categories().filterNot { it == DEFAULT_CATEGORY }.toMutableSet()
        val changed = set.remove(name)
        if (changed) prefs.edit().putStringSet(KEY_CATEGORIES, set).apply()
        return changed
    }

    companion object {
        const val DEFAULT_CATEGORY = "未分类"
        private const val KEY_CATEGORIES = "categories"
        private const val MAX_LENGTH = 24
        private val DEFAULTS = listOf("常用", "工作", "验证码", "地址")
    }
}
