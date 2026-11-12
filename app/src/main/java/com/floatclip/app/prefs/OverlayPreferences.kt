package com.floatclip.app.prefs

import android.content.Context

enum class OverlayThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

class OverlayPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("floatclip_overlay", Context.MODE_PRIVATE)

    init {
        // Restore the classic controls without resurrecting hidden curve/range customizations.
        if (!prefs.getBoolean("classic_settings_v43", false)) {
            val edit=prefs.edit().putBoolean("classic_settings_v43",true).remove(KEY_MOTION_PRESET)
            (0..3).forEach { edit.remove("motion_curve_"+it) }
            listOf("card","bubble","panel","transition","switch").forEach {
                edit.remove("range_min_"+it).remove("range_max_"+it)
            }
            if(prefs.getString("panel_background_mode","transparent")=="window") {
                edit.putString("panel_background_mode","transparent")
                    .putInt(KEY_PANEL_ALPHA_PERCENT,prefs.getInt("window_alpha",100).coerceIn(45,100))
            }
            if(prefs.getBoolean("simple_surface_v1",false)) {
                edit.putInt(KEY_PANEL_ALPHA_PERCENT,(100-prefs.getInt("surface_transparency",10)).coerceIn(45,100))
            }
            edit.apply()
        }
    }
    fun surfaceTransparencyPercent():Int = prefs.getInt("surface_transparency",10).coerceIn(0,75)
    fun saveSurfaceTransparencyPercent(value:Int) { prefs.edit().putInt("surface_transparency",value.coerceIn(0,75)).apply() }
    fun surfacePanelAlphaPercent(): Int = if (backgroundMode() == "solid") 100 else panelAlphaPercent()
    fun surfaceCardAlphaPercent():Int = if(backgroundMode()=="solid")100 else cardAlphaPercent()
    fun overlayBorderEnabled(): Boolean = prefs.getBoolean(KEY_BORDER_ENABLED, true)

    fun motionSpeed():Int=prefs.getInt("motion_speed",1).coerceIn(0,2)
    fun saveMotionSpeed(value:Int) {
        require(value in 0..2)
        prefs.edit().putInt("motion_speed",value)
            .putInt(KEY_TRANSITION_DURATION_MS,listOf(200,300,440)[value])
            .putInt(KEY_SWITCH_ANIMATION_MS,listOf(100,140,180)[value]).apply()
    }
    fun panelMotionDurationMs(open:Boolean):Int {
        val duration=transitionDurationMs()
        return if(open || duration==0)duration else (duration*.55f).toInt().coerceIn(70,200)
    }

    fun feedbackStrength(): Int = prefs.getInt("feedback_strength", 1).coerceIn(0, 2)
    fun saveFeedbackStrength(value: Int) {
        require(value in 0..2)
        val scales = when(value) {
            0 -> listOf(99, 98, 100)
            2 -> listOf(96, 93, 98)
            else -> listOf(98, 96, 99)
        }
        prefs.edit().putInt("feedback_strength", value)
            .putInt(KEY_CARD_PRESS_SCALE_PERCENT, scales[0])
            .putInt(KEY_BUBBLE_PRESS_SCALE_PERCENT, scales[1])
            .putInt(KEY_PANEL_PRESS_SCALE_PERCENT, scales[2]).apply()
    }

    fun bubbleY(defaultValue: Int): Int = prefs.getInt(KEY_BUBBLE_Y, defaultValue)
    fun bubbleOnRight(): Boolean = prefs.getBoolean(KEY_BUBBLE_RIGHT, false)
    fun bubbleSizeDp(): Int = prefs.getInt(KEY_BUBBLE_SIZE_DP, DEFAULT_BUBBLE_SIZE_DP).coerceIn(42, 64)
    fun bubbleAlphaPercent(): Int = prefs.getInt(KEY_BUBBLE_ALPHA_PERCENT, DEFAULT_BUBBLE_ALPHA_PERCENT).coerceIn(35, 100)
    fun edgeHidePercent(): Int = prefs.getInt(KEY_EDGE_HIDE_PERCENT, DEFAULT_EDGE_HIDE_PERCENT).coerceIn(0,55)
    fun bubbleMotionSensitivityPercent():Int = prefs.getInt(KEY_BUBBLE_MOTION_SENSITIVITY_PERCENT, DEFAULT_BUBBLE_MOTION_SENSITIVITY_PERCENT).coerceIn(30,120)
    fun overlayEnabled(): Boolean = prefs.getBoolean(KEY_OVERLAY_ENABLED, false)
    fun twoColumnClipboard(): Boolean = prefs.getBoolean("clipboard_two_columns", false)
    fun saveTwoColumnClipboard(value: Boolean) { prefs.edit().putBoolean("clipboard_two_columns", value).apply() }
    fun mergeRemoteImages(): Boolean = prefs.getBoolean("clipboard_merge_remote_images", true)
    fun saveMergeRemoteImages(value: Boolean) { prefs.edit().putBoolean("clipboard_merge_remote_images", value).apply() }
    fun sourceColors(): Boolean = prefs.getBoolean("source_colors", true)
    fun translucentCards(): Boolean = prefs.getBoolean("translucent_cards",true)
    fun saveSourceColors(value: Boolean) { prefs.edit().putBoolean("source_colors",value).apply() }
    fun saveTranslucentCards(value: Boolean) { prefs.edit().putBoolean("translucent_cards",value).apply() }

    fun backgroundMode():String {
        val saved=prefs.getString("panel_background_mode","transparent")
            ?.takeIf { it in listOf("solid","transparent","frost","window") } ?: "transparent"
        // API 30 and below cannot blur other applications through this overlay window.
        // Do not substitute a synthetic noise texture while preserving the saved choice.
        return if (saved=="frost" && android.os.Build.VERSION.SDK_INT<31) "transparent" else saved
    }
    fun saveBackgroundMode(value:String) { require(value in listOf("solid","transparent","frost","window"));prefs.edit().putString("panel_background_mode",value).apply() }
    fun windowAlphaPercent()=prefs.getInt("window_alpha",100).coerceIn(20,100)
    fun saveWindowAlphaPercent(value:Int) { prefs.edit().putInt("window_alpha",value.coerceIn(20,100)).apply() }
    fun curveControl(index:Int):Int { require(index in 0..3);return prefs.getInt("motion_curve_"+index,listOf(20,80,20,100)[index]).coerceIn(0,100) }
    fun motionPreset():String {
        prefs.getString(KEY_MOTION_PRESET,null)?.takeIf { it in MOTION_PRESETS }?.let { return it }
        val current=(0..3).map(::curveControl)
        return when(current) {
            listOf(20,80,20,100) -> "soft"
            listOf(16,100,30,100) -> "snappy"
            listOf(0,0,100,100) -> "linear"
            else -> "custom"
        }
    }
    fun saveMotionPreset(value:String) {
        require(value in MOTION_PRESETS)
        val editor=prefs.edit().putString(KEY_MOTION_PRESET,value)
        val points=when(value) {
            "soft" -> listOf(20,80,20,100)
            "snappy" -> listOf(16,100,30,100)
            "linear" -> listOf(0,0,100,100)
            else -> null
        }
        points?.forEachIndexed { index,point -> editor.putInt("motion_curve_"+index,point) }
        editor.apply()
    }
    fun saveCurveControl(index:Int,value:Int) {
        require(index in 0..3)
        prefs.edit().putString(KEY_MOTION_PRESET,"custom")
            .putInt("motion_curve_"+index,value.coerceIn(0,100)).apply()
    }
    fun motionInterpolator():android.view.animation.Interpolator=android.view.animation.PathInterpolator(
        curveControl(0)/100f,curveControl(1)/100f,curveControl(2)/100f,curveControl(3)/100f)
    fun panelAlphaPercent(): Int = prefs.getInt(KEY_PANEL_ALPHA_PERCENT, DEFAULT_PANEL_ALPHA_PERCENT).coerceIn(45, 100)
    fun panelWidthDp(): Int = prefs.getInt(KEY_PANEL_WIDTH_DP, DEFAULT_PANEL_WIDTH_DP).coerceIn(280, 420)
    fun panelHeightDp(): Int = prefs.getInt(KEY_PANEL_HEIGHT_DP, DEFAULT_PANEL_HEIGHT_DP).coerceIn(300, 620)
    fun entryLineCount(): Int = prefs.getInt(KEY_ENTRY_LINE_COUNT, DEFAULT_ENTRY_LINE_COUNT).coerceIn(1, 3)
    fun cardAlphaPercent(): Int = prefs.getInt(KEY_CARD_ALPHA_PERCENT, DEFAULT_CARD_ALPHA_PERCENT).coerceIn(45, 100)
    // UI labels say transparency: 0% is opaque, higher values are more see-through.
    // Persisted alpha values remain untouched for safe upgrade and downgrade.
    fun panelTransparencyPercent()=100-panelAlphaPercent()
    fun bubbleTransparencyPercent()=100-bubbleAlphaPercent()
    fun cardTransparencyPercent()=100-cardAlphaPercent()
    fun savePanelTransparencyPercent(value:Int)=savePanelAlphaPercent(100-value.coerceIn(0,55))
    fun saveBubbleTransparencyPercent(value:Int)=saveBubbleAlphaPercent(100-value.coerceIn(0,65))
    fun saveCardTransparencyPercent(value:Int)=saveCardAlphaPercent(100-value.coerceIn(0,55))

    fun frostStrengthPercent(): Int = prefs.getInt(KEY_FROST_STRENGTH_PERCENT, DEFAULT_FROST_STRENGTH_PERCENT).coerceIn(0, 100)
    fun borderEnabled(): Boolean = false
    fun borderAlphaPercent(): Int = prefs.getInt(KEY_BORDER_ALPHA_PERCENT, DEFAULT_BORDER_ALPHA_PERCENT).coerceIn(5, 60)
    fun accentTheme(): String = prefs.getString(KEY_ACCENT_THEME,"blue") ?: "blue"
    fun interactionRange(key:String):IntRange {
        val fallback=when(key){"transition"->0..1500;"switch"->0..1000;else->50..100}
        val hard=if(key in listOf("transition","switch"))0..5000 else 10..120
        val min=prefs.getInt("range_min_"+key,fallback.first).coerceIn(hard)
        val max=prefs.getInt("range_max_"+key,fallback.last).coerceIn(hard)
        return if(min<max)min..max else fallback
    }
    fun saveInteractionRange(key:String,min:Int,max:Int) {
        require(key in listOf("card","bubble","panel","transition","switch"))
        val hard=if(key in listOf("transition","switch"))0..5000 else 10..120
        require(min in hard && max in hard && min<max)
        prefs.edit().putInt("range_min_"+key,min).putInt("range_max_"+key,max).apply()
    }
    fun cardPressScalePercent(): Int = prefs.getInt(KEY_CARD_PRESS_SCALE_PERCENT, DEFAULT_CARD_PRESS_SCALE_PERCENT).coerceIn(interactionRange("card"))
    fun bubblePressScalePercent(): Int = prefs.getInt(KEY_BUBBLE_PRESS_SCALE_PERCENT, DEFAULT_BUBBLE_PRESS_SCALE_PERCENT).coerceIn(interactionRange("bubble"))
    fun panelPressScalePercent(): Int = prefs.getInt(KEY_PANEL_PRESS_SCALE_PERCENT, DEFAULT_PANEL_PRESS_SCALE_PERCENT).coerceIn(interactionRange("panel"))
    fun transitionDurationMs(): Int = prefs.getInt(KEY_TRANSITION_DURATION_MS, DEFAULT_TRANSITION_DURATION_MS).coerceIn(interactionRange("transition"))
    fun switchAnimationMs(): Int = prefs.getInt(KEY_SWITCH_ANIMATION_MS, DEFAULT_SWITCH_ANIMATION_MS).coerceIn(interactionRange("switch"))
    fun detailedNotification(): Boolean = prefs.getBoolean(KEY_DETAILED_NOTIFICATION, false)
    fun panelFollowsBubble(): Boolean = prefs.getBoolean(KEY_PANEL_FOLLOWS_BUBBLE, false)
    fun savePanelFollowsBubble(value:Boolean) { prefs.edit().putBoolean(KEY_PANEL_FOLLOWS_BUBBLE,value).apply() }
    fun panelX(defaultValue: Int): Int = prefs.getInt(KEY_PANEL_X, defaultValue)
    fun panelY(defaultValue: Int): Int = prefs.getInt(KEY_PANEL_Y, defaultValue)

    fun themeMode(): OverlayThemeMode = runCatching {
        OverlayThemeMode.valueOf(prefs.getString(KEY_THEME_MODE, OverlayThemeMode.SYSTEM.name) ?: OverlayThemeMode.SYSTEM.name)
    }.getOrDefault(OverlayThemeMode.SYSTEM)

    fun bubbleThemeOverride(): OverlayThemeMode? = prefs.getString(KEY_BUBBLE_THEME_MODE, null)
        ?.let { saved -> OverlayThemeMode.entries.firstOrNull { it.name == saved } }

    // Legacy unset/follow-app values migrate to the independent system theme.
    fun bubbleThemeMode(): OverlayThemeMode = bubbleThemeOverride() ?: OverlayThemeMode.SYSTEM

    fun saveBubbleThemeOverride(mode: OverlayThemeMode?) {
        prefs.edit().putString(KEY_BUBBLE_THEME_MODE, mode?.name).apply()
    }

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
    fun saveCardPressScalePercent(value: Int) { prefs.edit().putInt(KEY_CARD_PRESS_SCALE_PERCENT,value.coerceIn(interactionRange("card"))).apply() }
    fun saveBubblePressScalePercent(value: Int) { prefs.edit().putInt(KEY_BUBBLE_PRESS_SCALE_PERCENT,value.coerceIn(interactionRange("bubble"))).apply() }
    fun savePanelPressScalePercent(value: Int) { prefs.edit().putInt(KEY_PANEL_PRESS_SCALE_PERCENT,value.coerceIn(interactionRange("panel"))).apply() }
    fun saveTransitionDurationMs(value: Int) { prefs.edit().putInt(KEY_TRANSITION_DURATION_MS,value.coerceIn(interactionRange("transition"))).apply() }
    fun saveSwitchAnimationMs(value: Int) { prefs.edit().putInt(KEY_SWITCH_ANIMATION_MS,value.coerceIn(interactionRange("switch"))).apply() }
    fun saveDetailedNotification(value: Boolean) { prefs.edit().putBoolean(KEY_DETAILED_NOTIFICATION,value).apply() }

    fun savePanelPosition(x: Int, y: Int) {
        prefs.edit().putInt(KEY_PANEL_X, x).putInt(KEY_PANEL_Y, y).apply()
    }

    fun saveThemeMode(mode: OverlayThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun resetAppearance() {
        prefs.edit()
            .remove("panel_background_mode").remove("window_alpha")
            .remove("range_min_card").remove("range_max_card").remove("range_min_bubble").remove("range_max_bubble")
            .remove("range_min_panel").remove("range_max_panel").remove("range_min_transition").remove("range_max_transition")
            .remove("range_min_switch").remove("range_max_switch")
            .remove("motion_curve_0").remove("motion_curve_1").remove("motion_curve_2").remove("motion_curve_3")
            .remove(KEY_MOTION_PRESET)
            .remove("feedback_strength")
            .remove("motion_speed")
            .remove("surface_transparency")
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
            .remove(KEY_PANEL_FOLLOWS_BUBBLE)
            .remove(KEY_PANEL_X)
            .remove(KEY_PANEL_Y)
            .remove(KEY_THEME_MODE)
            .remove(KEY_BUBBLE_THEME_MODE)
            .remove("source_colors")
            .remove("translucent_cards")
            .remove("clipboard_two_columns")
            .remove("clipboard_merge_remote_images")
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
        private const val KEY_PANEL_FOLLOWS_BUBBLE = "panel_follows_bubble"
        private const val KEY_PANEL_X = "panel_x"
        private const val KEY_PANEL_Y = "panel_y"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_BUBBLE_THEME_MODE = "bubble_theme_mode"
        private const val KEY_MOTION_PRESET = "motion_preset"
        private val MOTION_PRESETS = setOf("soft","snappy","linear","custom")
    }
}
