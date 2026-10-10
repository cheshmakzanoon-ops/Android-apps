package com.agency.stickerjet.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * WCAG 2.x contrast arithmetic on opaque sRGB colours. Used by [ThemeContrastTest] so the palette
 * is checked against 4.5:1 for text on every commit, without a device.
 */
object WcagContrast {
    /** Relative luminance as defined in WCAG 2.x section 1.4.3. */
    fun relativeLuminance(color: Color): Double {
        val r = linearise(color.red.toDouble())
        val g = linearise(color.green.toDouble())
        val b = linearise(color.blue.toDouble())
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** Contrast ratio between two colours, from 1.0 (identical) to 21.0 (black on white). */
    fun ratio(foreground: Color, background: Color): Double {
        val a = relativeLuminance(foreground)
        val b = relativeLuminance(background)
        val lighter = maxOf(a, b)
        val darker = minOf(a, b)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun linearise(channel: Double): Double =
        if (channel <= 0.03928) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
}
