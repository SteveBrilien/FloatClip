package com.floatclip.app.prefs

import android.content.Context

enum class OverlayThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

class OverlayPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("floatclip_overlay", Context.MODE_PRIVATE)

    fun bubbleY(defaultValue: Int): Int = prefs.getInt(KEY_BUBBLE_Y, defaultValue)
    fun bubbleOnRight(): Boolean = prefs.getBoolean(KEY_BUBBLE_RIGHT, false)
    fun bubbleSizeDp(): Int = prefs.getInt(KEY_BUBBLE_SIZE_DP, DEFAULT_BUBBLE_SIZE_DP).coerceIn(42, 64)
    fun bubbleAlphaPercent(): Int = prefs.getInt(KEY_BUBBLE_ALPHA_PERCENT, DEFAULT_BUBBLE_ALPHA_PERCENT).coerceIn(35, 100)
    fun edgeHidePercent(): Int = prefs.getInt(KEY_EDGE_HIDE_PERCENT, DEFAULT_EDGE_HIDE_PERCENT).coerceIn(0, 55)

    fun panelAlphaPercent(): Int = prefs.getInt(KEY_PANEL_ALPHA_PERCENT, DEFAULT_PANEL_ALPHA_PERCENT).coerceIn(45, 100)
    fun panelWidthDp(): Int = prefs.getInt(KEY_PANEL_WIDTH_DP, DEFAULT_PANEL_WIDTH_DP).coerceIn(280, 420)
    fun panelHeightDp(): Int = prefs.getInt(KEY_PANEL_HEIGHT_DP, DEFAULT_PANEL_HEIGHT_DP).coerceIn(300, 620)
    fun panelX(defaultValue: Int): Int = prefs.getInt(KEY_PANEL_X, defaultValue)
    fun panelY(defaultValue: Int): Int = prefs.getInt(KEY_PANEL_Y, defaultValue)

    fun themeMode(): OverlayThemeMode = runCatching {
        OverlayThemeMode.valueOf(prefs.getString(KEY_THEME_MODE, OverlayThemeMode.SYSTEM.name) ?: OverlayThemeMode.SYSTEM.name)
    }.getOrDefault(OverlayThemeMode.SYSTEM)

    fun saveBubble(y: Int, onRight: Boolean) {
        prefs.edit().putInt(KEY_BUBBLE_Y, y).putBoolean(KEY_BUBBLE_RIGHT, onRight).apply()
    }

    fun saveBubbleSizeDp(value: Int) {
        prefs.edit().putInt(KEY_BUBBLE_SIZE_DP, value.coerceIn(42, 64)).apply()
    }

    fun saveBubbleAlphaPercent(value: Int) {
        prefs.edit().putInt(KEY_BUBBLE_ALPHA_PERCENT, value.coerceIn(35, 100)).apply()
    }

    fun saveEdgeHidePercent(value: Int) {
        prefs.edit().putInt(KEY_EDGE_HIDE_PERCENT, value.coerceIn(0, 55)).apply()
    }

    fun savePanelAlphaPercent(value: Int) {
        prefs.edit().putInt(KEY_PANEL_ALPHA_PERCENT, value.coerceIn(45, 100)).apply()
    }

    fun savePanelWidthDp(value: Int) {
        prefs.edit().putInt(KEY_PANEL_WIDTH_DP, value.coerceIn(280, 420)).apply()
    }

    fun savePanelHeightDp(value: Int) {
        prefs.edit().putInt(KEY_PANEL_HEIGHT_DP, value.coerceIn(300, 620)).apply()
    }

    fun savePanelPosition(x: Int, y: Int) {
        prefs.edit().putInt(KEY_PANEL_X, x).putInt(KEY_PANEL_Y, y).apply()
    }

    fun saveThemeMode(mode: OverlayThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun resetAppearance() {
        prefs.edit()
            .remove(KEY_BUBBLE_SIZE_DP)
            .remove(KEY_BUBBLE_ALPHA_PERCENT)
            .remove(KEY_EDGE_HIDE_PERCENT)
            .remove(KEY_PANEL_ALPHA_PERCENT)
            .remove(KEY_PANEL_WIDTH_DP)
            .remove(KEY_PANEL_HEIGHT_DP)
            .remove(KEY_PANEL_X)
            .remove(KEY_PANEL_Y)
            .remove(KEY_THEME_MODE)
            .apply()
    }

    companion object {
        const val DEFAULT_BUBBLE_SIZE_DP = 50
        const val DEFAULT_BUBBLE_ALPHA_PERCENT = 76
        const val DEFAULT_EDGE_HIDE_PERCENT = 38
        const val DEFAULT_PANEL_ALPHA_PERCENT = 88
        const val DEFAULT_PANEL_WIDTH_DP = 352
        const val DEFAULT_PANEL_HEIGHT_DP = 430

        private const val KEY_BUBBLE_Y = "bubble_y"
        private const val KEY_BUBBLE_RIGHT = "bubble_right"
        private const val KEY_BUBBLE_SIZE_DP = "bubble_size_dp"
        private const val KEY_BUBBLE_ALPHA_PERCENT = "bubble_alpha_percent"
        private const val KEY_EDGE_HIDE_PERCENT = "edge_hide_percent"
        private const val KEY_PANEL_ALPHA_PERCENT = "panel_alpha_percent"
        private const val KEY_PANEL_WIDTH_DP = "panel_width_dp"
        private const val KEY_PANEL_HEIGHT_DP = "panel_height_dp"
        private const val KEY_PANEL_X = "panel_x"
        private const val KEY_PANEL_Y = "panel_y"
        private const val KEY_THEME_MODE = "theme_mode"
    }
}
