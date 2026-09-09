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
            system?.panelBackgroundArgb ?: fallback.panelBackground
        } else {
            fallback.panelBackground
        }
        val bubbleBackground = if (mode == OverlayThemeMode.SYSTEM) {
            system?.bubbleBackgroundArgb ?: fallback.bubbleBackground
        } else {
            fallback.bubbleBackground
        }
        val darkSurface = isDarkColor(panelBackground)
        val rowBackground = if (darkSurface) {
            blend(panelBackground, Color.WHITE, 0.075f)
        } else {
            blend(panelBackground, Color.BLACK, 0.025f)
        }
        val primaryText = if (darkSurface) Color.rgb(246, 247, 249) else Color.rgb(24, 26, 30)
        val secondaryText = if (darkSurface) Color.rgb(188, 191, 199) else Color.rgb(96, 101, 111)

        return OverlayPalette(
            bubbleBackground = bubbleBackground,
            panelBackground = panelBackground,
            rowBackground = rowBackground,
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
            bubbleBackground = Color.rgb(46, 48, 54),
            panelBackground = Color.rgb(32, 33, 38),
            rowBackground = Color.rgb(44, 45, 51),
            primaryText = Color.rgb(246, 247, 249),
            secondaryText = Color.rgb(188, 191, 199),
            isDark = true,
        )
    } else {
        OverlayPalette(
            bubbleBackground = Color.rgb(250, 250, 252),
            panelBackground = Color.rgb(248, 249, 251),
            rowBackground = Color.WHITE,
            primaryText = Color.rgb(24, 26, 30),
            secondaryText = Color.rgb(96, 101, 111),
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
