package com.floatclip.app.theme

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import com.floatclip.app.integration.OriginOsSystemBridge

data class OverlayPalette(
    val bubbleBackground: Int,
    val panelBackground: Int,
    val rowBackground: Int,
    val primaryText: Int,
    val secondaryText: Int,
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
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val fallback = if (night) {
            OverlayPalette(
                bubbleBackground = Color.rgb(46, 47, 52),
                panelBackground = Color.rgb(35, 36, 40),
                rowBackground = Color.rgb(48, 49, 54),
                primaryText = Color.WHITE,
                secondaryText = Color.LTGRAY,
            )
        } else {
            OverlayPalette(
                bubbleBackground = Color.WHITE,
                panelBackground = Color.rgb(248, 248, 250),
                rowBackground = Color.WHITE,
                primaryText = Color.rgb(32, 32, 36),
                secondaryText = Color.DKGRAY,
            )
        }

        val system = if (originOsBridge.isAvailable()) originOsBridge.currentTheme() else null
        return if (system == null) fallback else fallback.copy(
            bubbleBackground = system.bubbleBackgroundArgb ?: fallback.bubbleBackground,
            panelBackground = system.panelBackgroundArgb ?: fallback.panelBackground,
            primaryText = system.primaryTextArgb ?: fallback.primaryText,
            secondaryText = system.secondaryTextArgb ?: fallback.secondaryText,
            bubbleCornerRadiusDp = system.cornerRadiusDp ?: fallback.bubbleCornerRadiusDp,
            panelCornerRadiusDp = system.cornerRadiusDp ?: fallback.panelCornerRadiusDp,
            elevationDp = system.elevationDp ?: fallback.elevationDp,
            source = "originos:${system.sourcePackage}:${system.themeName ?: "current"}",
        )
    }
}
