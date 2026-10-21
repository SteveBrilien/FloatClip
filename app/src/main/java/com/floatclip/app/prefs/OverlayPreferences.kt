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
    fun bubbleMotionSensitivityPercent(): Int = prefs.getInt(
        KEY_BUBBLE_MOTION_SENSITIVITY_PERCENT,
        DEFAULT_BUBBLE_MOTION_SENSITIVITY_PERCENT,
    ).coerceIn(30, 120)
    fun keepAliveEnabled(): Boolean = prefs.getBoolean(KEY_KEEP_ALIVE_ENABLED, true)
    fun overlayEnabled(): Boolean = prefs.getBoolean(KEY_OVERLAY_ENABLED, false)
    fun sourceColors(): Boolean = prefs.getBoolean("source_colors", true)
    fun translucentCards(): Boolean = prefs.getBoolean("translucent_cards", true)
    fun saveSourceColors(value: Boolean) { prefs.edit().putBoolean("source_colors",value).apply() }
    fun saveTranslucentCards(value: Boolean) { prefs.edit().putBoolean("translucent_cards",value).apply() }

    fun panelAlphaPercent(): Int = prefs.getInt(KEY_PANEL_ALPHA_PERCENT, DEFAULT_PANEL_ALPHA_PERCENT).coerceIn(45, 100)
    fun panelWidthDp(): Int = prefs.getInt(KEY_PANEL_WIDTH_DP, DEFAULT_PANEL_WIDTH_DP).coerceIn(280, 420)
    fun panelHeightDp(): Int = prefs.getInt(KEY_PANEL_HEIGHT_DP, DEFAULT_PANEL_HEIGHT_DP).coerceIn(300, 620)
    fun entryLineCount(): Int = prefs.getInt(KEY_ENTRY_LINE_COUNT, DEFAULT_ENTRY_LINE_COUNT).coerceIn(1, 3)
    fun cardAlphaPercent(): Int = prefs.getInt(KEY_CARD_ALPHA_PERCENT, DEFAULT_CARD_ALPHA_PERCENT).coerceIn(45, 100)
    fun frostStrengthPercent(): Int = prefs.getInt(KEY_FROST_STRENGTH_PERCENT, DEFAULT_FROST_STRENGTH_PERCENT).coerceIn(0, 100)
    fun borderEnabled(): Boolean = prefs.getBoolean(KEY_BORDER_ENABLED, true)
    fun borderAlphaPercent(): Int = prefs.getInt(KEY_BORDER_ALPHA_PERCENT, DEFAULT_BORDER_ALPHA_PERCENT).coerceIn(5, 60)
    fun accentTheme(): String = prefs.getString(KEY_ACCENT_THEME, "blue") ?: "blue"
    fun cardPressScalePercent(): Int = prefs.getInt(KEY_CARD_PRESS_SCALE_PERCENT, DEFAULT_CARD_PRESS_SCALE_PERCENT).coerceIn(94, 100)
    fun bubblePressScalePercent(): Int = prefs.getInt(KEY_BUBBLE_PRESS_SCALE_PERCENT, DEFAULT_BUBBLE_PRESS_SCALE_PERCENT).coerceIn(90, 100)
    fun panelPressScalePercent(): Int = prefs.getInt(KEY_PANEL_PRESS_SCALE_PERCENT, DEFAULT_PANEL_PRESS_SCALE_PERCENT).coerceIn(97, 100)
    fun transitionDurationMs(): Int = prefs.getInt(KEY_TRANSITION_DURATION_MS, DEFAULT_TRANSITION_DURATION_MS).coerceIn(120, 480)
    fun switchAnimationMs(): Int = prefs.getInt(KEY_SWITCH_ANIMATION_MS, DEFAULT_SWITCH_ANIMATION_MS).coerceIn(80, 300)
    fun detailedNotification(): Boolean = prefs.getBoolean(KEY_DETAILED_NOTIFICATION, false)
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

    fun saveBubbleMotionSensitivityPercent(value: Int) {
        prefs.edit().putInt(KEY_BUBBLE_MOTION_SENSITIVITY_PERCENT, value.coerceIn(30, 120)).apply()
    }

    fun saveKeepAliveEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_ALIVE_ENABLED, value).apply()
    }

    fun saveOverlayEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_OVERLAY_ENABLED, value).apply()
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

    fun saveEntryLineCount(value: Int) {
        prefs.edit().putInt(KEY_ENTRY_LINE_COUNT, value.coerceIn(1, 3)).apply()
    }


    fun saveCardAlphaPercent(value: Int) { prefs.edit().putInt(KEY_CARD_ALPHA_PERCENT,value.coerceIn(45,100)).apply() }
    fun saveFrostStrengthPercent(value: Int) { prefs.edit().putInt(KEY_FROST_STRENGTH_PERCENT,value.coerceIn(0,100)).apply() }
    fun saveBorderEnabled(value: Boolean) { prefs.edit().putBoolean(KEY_BORDER_ENABLED,value).apply() }
    fun saveBorderAlphaPercent(value: Int) { prefs.edit().putInt(KEY_BORDER_ALPHA_PERCENT,value.coerceIn(5,60)).apply() }
    fun saveAccentTheme(value: String) { prefs.edit().putString(KEY_ACCENT_THEME,value).apply() }
    fun saveCardPressScalePercent(value: Int) { prefs.edit().putInt(KEY_CARD_PRESS_SCALE_PERCENT,value.coerceIn(94,100)).apply() }
    fun saveBubblePressScalePercent(value: Int) { prefs.edit().putInt(KEY_BUBBLE_PRESS_SCALE_PERCENT,value.coerceIn(90,100)).apply() }
    fun savePanelPressScalePercent(value: Int) { prefs.edit().putInt(KEY_PANEL_PRESS_SCALE_PERCENT,value.coerceIn(97,100)).apply() }
    fun saveTransitionDurationMs(value: Int) { prefs.edit().putInt(KEY_TRANSITION_DURATION_MS,value.coerceIn(120,480)).apply() }
    fun saveSwitchAnimationMs(value: Int) { prefs.edit().putInt(KEY_SWITCH_ANIMATION_MS,value.coerceIn(80,300)).apply() }
    fun saveDetailedNotification(value: Boolean) { prefs.edit().putBoolean(KEY_DETAILED_NOTIFICATION,value).apply() }

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
            .remove(KEY_BUBBLE_MOTION_SENSITIVITY_PERCENT)
            .remove(KEY_PANEL_ALPHA_PERCENT)
            .remove(KEY_PANEL_WIDTH_DP)
            .remove(KEY_PANEL_HEIGHT_DP)
            .remove(KEY_ENTRY_LINE_COUNT)
            .remove(KEY_CARD_ALPHA_PERCENT)
            .remove(KEY_FROST_STRENGTH_PERCENT)
            .remove(KEY_BORDER_ENABLED)
            .remove(KEY_BORDER_ALPHA_PERCENT)
            .remove(KEY_ACCENT_THEME)
            .remove(KEY_CARD_PRESS_SCALE_PERCENT)
            .remove(KEY_BUBBLE_PRESS_SCALE_PERCENT)
            .remove(KEY_PANEL_PRESS_SCALE_PERCENT)
            .remove(KEY_TRANSITION_DURATION_MS)
            .remove(KEY_SWITCH_ANIMATION_MS)
            .remove(KEY_DETAILED_NOTIFICATION)
            .remove(KEY_PANEL_X)
            .remove(KEY_PANEL_Y)
            .remove(KEY_THEME_MODE)
            .apply()
    }

    companion object {
        const val DEFAULT_BUBBLE_SIZE_DP = 50
        const val DEFAULT_BUBBLE_ALPHA_PERCENT = 76
        const val DEFAULT_EDGE_HIDE_PERCENT = 38
        const val DEFAULT_BUBBLE_MOTION_SENSITIVITY_PERCENT = 65
        const val DEFAULT_PANEL_ALPHA_PERCENT = 90
        const val DEFAULT_PANEL_WIDTH_DP = 352
        const val DEFAULT_PANEL_HEIGHT_DP = 430
        const val DEFAULT_ENTRY_LINE_COUNT = 2
        const val DEFAULT_CARD_ALPHA_PERCENT = 86
        const val DEFAULT_FROST_STRENGTH_PERCENT = 28
        const val DEFAULT_BORDER_ALPHA_PERCENT = 18
        const val DEFAULT_CARD_PRESS_SCALE_PERCENT = 98
        const val DEFAULT_BUBBLE_PRESS_SCALE_PERCENT = 96
        const val DEFAULT_PANEL_PRESS_SCALE_PERCENT = 99
        const val DEFAULT_TRANSITION_DURATION_MS = 240
        const val DEFAULT_SWITCH_ANIMATION_MS = 140

        private const val KEY_BUBBLE_Y = "bubble_y"
        private const val KEY_BUBBLE_RIGHT = "bubble_right"
        private const val KEY_BUBBLE_SIZE_DP = "bubble_size_dp"
        private const val KEY_BUBBLE_ALPHA_PERCENT = "bubble_alpha_percent"
        private const val KEY_EDGE_HIDE_PERCENT = "edge_hide_percent"
        private const val KEY_BUBBLE_MOTION_SENSITIVITY_PERCENT = "bubble_motion_sensitivity_percent"
        private const val KEY_KEEP_ALIVE_ENABLED = "keep_alive_enabled"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val KEY_PANEL_ALPHA_PERCENT = "panel_alpha_percent"
        private const val KEY_PANEL_WIDTH_DP = "panel_width_dp"
        private const val KEY_PANEL_HEIGHT_DP = "panel_height_dp"
        private const val KEY_ENTRY_LINE_COUNT = "entry_line_count"
        private const val KEY_CARD_ALPHA_PERCENT = "card_alpha_percent"
        private const val KEY_FROST_STRENGTH_PERCENT = "frost_strength_percent"
        private const val KEY_BORDER_ENABLED = "border_enabled"
        private const val KEY_BORDER_ALPHA_PERCENT = "border_alpha_percent"
        private const val KEY_ACCENT_THEME = "accent_theme"
        private const val KEY_CARD_PRESS_SCALE_PERCENT = "card_press_scale_percent"
        private const val KEY_BUBBLE_PRESS_SCALE_PERCENT = "bubble_press_scale_percent"
        private const val KEY_PANEL_PRESS_SCALE_PERCENT = "panel_press_scale_percent"
        private const val KEY_TRANSITION_DURATION_MS = "transition_duration_ms"
        private const val KEY_SWITCH_ANIMATION_MS = "switch_animation_ms"
        private const val KEY_DETAILED_NOTIFICATION = "detailed_notification"
        private const val KEY_PANEL_X = "panel_x"
        private const val KEY_PANEL_Y = "panel_y"
        private const val KEY_THEME_MODE = "theme_mode"
    }
}
