package com.floatclip.app.theme

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import com.floatclip.app.integration.OriginOsSystemBridge
import com.floatclip.app.prefs.OverlayPreferences
import com.floatclip.app.prefs.OverlayThemeMode

data class OverlayPalette(
    val bubbleBackground: Int,
    val panelBackground: Int,
    val rowBackground: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val isDark: Boolean,
    val bubbleCornerRadiusDp: Float = 26f,
    val panelCornerRadiusDp: Float = 22f,
    val rowCornerRadiusDp: Float = 14f,
    val elevationDp: Float = 12f,
    val source: String = "standalone",
)

interface OverlayThemeProvider {
    fun palette(): OverlayPalette
}

class AdaptiveOverlayThemeProvider(
    private val context: Context,
    private val originOsBridge: OriginOsSystemBridge,
) : OverlayThemeProvider {
    override fun palette(): OverlayPalette {
        val mode = OverlayPreferences(context).themeMode()
        val systemNight = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val requestedDark = when (mode) {
            OverlayThemeMode.SYSTEM -> systemNight
            OverlayThemeMode.LIGHT -> false
            OverlayThemeMode.DARK -> true
        }

        val fallback = fallbackPalette(requestedDark)
        val system = if (originOsBridge.isAvailable()) originOsBridge.currentTheme() else null

        // OEM resource names are semantic hints rather than a guarantee that foreground/background
        // resources belong to the same theme. Only consume OEM colors in SYSTEM mode, then normalize
        // the entire surface family to one luminance direction so white-on-white/black-on-black
        // combinations cannot occur.
        val panelBackground = if (mode == OverlayThemeMode.SYSTEM) {
            system?.panelBackgroundArgb
                ?.takeIf { isDarkColor(it) == requestedDark }
                ?: fallback.panelBackground
        } else {
            fallback.panelBackground
        }
        val bubbleBackground = if (mode == OverlayThemeMode.SYSTEM) {
            system?.bubbleBackgroundArgb
                ?.takeIf { isDarkColor(it) == requestedDark }
                ?: fallback.bubbleBackground
        } else {
            fallback.bubbleBackground
        }
        // Configuration.uiMode is authoritative. OEM semantic resources can lag one theme
        // transition behind on this OriginOS build, so never infer the requested mode back from
        // a potentially stale package color.
        val darkSurface = requestedDark
        val rowBackground = if (darkSurface) {
            blend(panelBackground, Color.WHITE, 0.075f)
        } else {
            blend(panelBackground, Color.BLACK, 0.025f)
        }
        val primaryText = fallback.primaryText
        val secondaryText = fallback.secondaryText

        return OverlayPalette(
            bubbleBackground = bubbleBackground,
            panelBackground = panelBackground,
            rowBackground = if (mode == OverlayThemeMode.SYSTEM && system != null) rowBackground else fallback.rowBackground,
            primaryText = primaryText,
            secondaryText = secondaryText,
            isDark = darkSurface,
            bubbleCornerRadiusDp = system?.cornerRadiusDp ?: fallback.bubbleCornerRadiusDp,
            panelCornerRadiusDp = system?.cornerRadiusDp ?: fallback.panelCornerRadiusDp,
            rowCornerRadiusDp = fallback.rowCornerRadiusDp,
            elevationDp = system?.elevationDp ?: fallback.elevationDp,
            source = if (system == null) {
                "standalone:${mode.name.lowercase()}"
            } else {
                "originos:${system.sourcePackage}:${system.themeName ?: "current"}:${mode.name.lowercase()}"
            },
        )
    }

    private fun fallbackPalette(dark: Boolean): OverlayPalette = if (dark) {
        OverlayPalette(
            bubbleBackground = Color.rgb(36, 40, 59),
            panelBackground = Color.rgb(26, 27, 38),
            rowBackground = Color.rgb(36, 40, 59),
            primaryText = Color.rgb(192, 202, 245),
            secondaryText = Color.rgb(154, 165, 206),
            isDark = true,
        )
    } else {
        OverlayPalette(
            bubbleBackground = Color.WHITE,
            panelBackground = Color.rgb(244, 247, 252),
            rowBackground = Color.WHITE,
            primaryText = Color.rgb(25, 40, 63),
            secondaryText = Color.rgb(89, 108, 133),
            isDark = false,
        )
    }

    private fun isDarkColor(color: Int): Boolean {
        val r = Color.red(color) / 255.0
        val g = Color.green(color) / 255.0
        val b = Color.blue(color) / 255.0
        val linearR = if (r <= 0.04045) r / 12.92 else Math.pow((r + 0.055) / 1.055, 2.4)
        val linearG = if (g <= 0.04045) g / 12.92 else Math.pow((g + 0.055) / 1.055, 2.4)
        val linearB = if (b <= 0.04045) b / 12.92 else Math.pow((b + 0.055) / 1.055, 2.4)
        return (0.2126 * linearR + 0.7152 * linearG + 0.0722 * linearB) < 0.43
    }

    private fun blend(from: Int, to: Int, ratio: Float): Int {
        val p = ratio.coerceIn(0f, 1f)
        val inv = 1f - p
        return Color.rgb(
            (Color.red(from) * inv + Color.red(to) * p).toInt(),
            (Color.green(from) * inv + Color.green(to) * p).toInt(),
            (Color.blue(from) * inv + Color.blue(to) * p).toInt(),
        )
    }
}
