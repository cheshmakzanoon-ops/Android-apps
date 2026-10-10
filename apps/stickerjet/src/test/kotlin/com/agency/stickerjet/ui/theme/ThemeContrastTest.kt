package com.agency.stickerjet.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Checks the palette against the brief and every text pair against WCAG 4.5:1. A change to a colour
 * token that drops a pair below the limit fails the build, not a device review.
 */
class ThemeContrastTest {

    @Test
    fun palette_matches_the_brief_exactly() {
        assertEquals("#1C1B2E", hex(StickerPalette.Ink))
        assertEquals("#FFF7EC", hex(StickerPalette.Paper))
        assertEquals("#FF6A3D", hex(StickerPalette.Jet))
        assertEquals("#2BB3A3", hex(StickerPalette.Sea))
        assertEquals("#FFC857", hex(StickerPalette.Sun))
        assertEquals("#8B7CF6", hex(StickerPalette.Lilac))
    }

    @Test
    fun wcag_arithmetic_matches_known_values() {
        // Black on white is the maximum, 21:1. Ink on Paper is about 15.9:1.
        assertEquals(21.0, WcagContrast.ratio(Color.Black, Color.White), 0.01)
        assertEquals(15.87, WcagContrast.ratio(StickerPalette.Ink, StickerPalette.Paper), 0.05)
    }

    @Test
    fun light_text_pairs_meet_4_5_to_1() {
        val s = StickerLightScheme
        assertTextPair("onBackground", s.onBackground, "background", s.background)
        assertTextPair("onSurface", s.onSurface, "surface", s.surface)
        assertTextPair("onSurfaceVariant", s.onSurfaceVariant, "surfaceVariant", s.surfaceVariant)
        assertTextPair("onPrimary", s.onPrimary, "primary", s.primary)
        assertTextPair("onSecondary", s.onSecondary, "secondary", s.secondary)
        assertTextPair("onTertiary", s.onTertiary, "tertiary", s.tertiary)
        assertTextPair("onPrimaryContainer", s.onPrimaryContainer, "primaryContainer", s.primaryContainer)
        assertTextPair("onSecondaryContainer", s.onSecondaryContainer, "secondaryContainer", s.secondaryContainer)
        assertTextPair("onTertiaryContainer", s.onTertiaryContainer, "tertiaryContainer", s.tertiaryContainer)
        assertTextPair("onError", s.onError, "error", s.error)
        assertTextPair("onErrorContainer", s.onErrorContainer, "errorContainer", s.errorContainer)
        assertTextPair("onSurface", s.onSurface, "surfaceContainer", s.surfaceContainer)
        assertTextPair("onAccent", StickerLightExtra.onAccent, "accent", StickerLightExtra.accent)
    }

    @Test
    fun dark_text_pairs_meet_4_5_to_1() {
        val s = StickerDarkScheme
        assertTextPair("onBackground", s.onBackground, "background", s.background)
        assertTextPair("onSurface", s.onSurface, "surface", s.surface)
        assertTextPair("onSurfaceVariant", s.onSurfaceVariant, "surfaceVariant", s.surfaceVariant)
        assertTextPair("onPrimary", s.onPrimary, "primary", s.primary)
        assertTextPair("onSecondary", s.onSecondary, "secondary", s.secondary)
        assertTextPair("onTertiary", s.onTertiary, "tertiary", s.tertiary)
        assertTextPair("onPrimaryContainer", s.onPrimaryContainer, "primaryContainer", s.primaryContainer)
        assertTextPair("onSecondaryContainer", s.onSecondaryContainer, "secondaryContainer", s.secondaryContainer)
        assertTextPair("onTertiaryContainer", s.onTertiaryContainer, "tertiaryContainer", s.tertiaryContainer)
        assertTextPair("onError", s.onError, "error", s.error)
        assertTextPair("onErrorContainer", s.onErrorContainer, "errorContainer", s.errorContainer)
        assertTextPair("onAccent", StickerDarkExtra.onAccent, "accent", StickerDarkExtra.accent)
    }

    @Test
    fun outlines_meet_3_to_1_against_their_surfaces() {
        // Non-text boundaries (WCAG 1.4.11) need 3:1, not 4.5:1.
        assertTrue(WcagContrast.ratio(StickerLightScheme.outline, StickerLightScheme.background) >= 3.0)
        assertTrue(WcagContrast.ratio(StickerDarkScheme.outline, StickerDarkScheme.background) >= 3.0)
    }

    private fun assertTextPair(fgName: String, fg: Color, bgName: String, bg: Color) {
        val ratio = WcagContrast.ratio(fg, bg)
        assertTrue("$fgName on $bgName is ${"%.2f".format(ratio)}:1, below 4.5:1", ratio >= MIN_TEXT_RATIO)
    }

    private fun hex(c: Color): String =
        "#%02X%02X%02X".format(
            (c.red * 255).roundToInt(),
            (c.green * 255).roundToInt(),
            (c.blue * 255).roundToInt(),
        )

    private companion object {
        const val MIN_TEXT_RATIO = 4.5
    }
}
