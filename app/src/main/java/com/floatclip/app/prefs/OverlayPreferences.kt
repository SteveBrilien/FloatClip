package com.floatclip.app.prefs

import android.content.Context

class OverlayPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("floatclip_overlay", Context.MODE_PRIVATE)

    fun bubbleY(defaultValue: Int): Int = prefs.getInt(KEY_BUBBLE_Y, defaultValue)
    fun bubbleOnRight(): Boolean = prefs.getBoolean(KEY_BUBBLE_RIGHT, false)

    fun saveBubble(y: Int, onRight: Boolean) {
        prefs.edit().putInt(KEY_BUBBLE_Y, y).putBoolean(KEY_BUBBLE_RIGHT, onRight).apply()
    }

    companion object {
        private const val KEY_BUBBLE_Y = "bubble_y"
        private const val KEY_BUBBLE_RIGHT = "bubble_right"
    }
}
