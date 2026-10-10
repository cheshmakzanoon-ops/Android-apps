package com.agency.stickerjet.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The six brand colours, fixed by the StickerJet brief. Every filled brand colour is labelled with
 * [Ink] because white on [Jet] measures 2.85:1, below the 4.5:1 text requirement.
 */
object StickerPalette {
    /** Text, outlines and labels on filled brand colours. */
    val Ink: Color = Color(0xFF1C1B2E)

    /** Light background and surface. */
    val Paper: Color = Color(0xFFFFF7EC)

    /** Primary action fill. Never used as text on [Paper] (2.7:1). */
    val Jet: Color = Color(0xFFFF6A3D)

    /** Secondary fill, for kept subject areas and secondary actions. */
    val Sea: Color = Color(0xFF2BB3A3)

    /** Highlight, for caption swatches and the highlighted subject. */
    val Sun: Color = Color(0xFFFFC857)

    /** Sparing accent only: one badge or marker per screen at most. */
    val Lilac: Color = Color(0xFF8B7CF6)
}

// Derived tones. Each text pair is checked against 4.5:1 in ThemeContrastTest.
private val LightSurfaceVariant = Color(0xFFF2E6D4)
private val LightOnSurfaceVariant = Color(0xFF4B4860)
private val LightOutline = Color(0xFF6F6A85)
private val LightOutlineVariant = Color(0xFFD8CDBD)
private val LightPrimaryContainer = Color(0xFFFFE3D6)
private val LightSecondaryContainer = Color(0xFFD6F3EE)
private val LightTertiaryContainer = Color(0xFFFFEBB8)
private val LightError = Color(0xFFB42318)
private val LightErrorContainer = Color(0xFFF9DAD5)

private val DarkSurface = Color(0xFF27263B)
private val DarkSurfaceVariant = Color(0xFF33324A)
private val DarkOnSurfaceVariant = Color(0xFFC5C1D6)
private val DarkOutline = Color(0xFF8F8AA6)
private val DarkOutlineVariant = Color(0xFF45435E)
private val DarkPrimaryContainer = Color(0xFF6B2B1C)
private val DarkSecondaryContainer = Color(0xFF15524B)
private val DarkTertiaryContainer = Color(0xFF5C4300)
private val DarkError = Color(0xFFFFB4A8)
private val DarkErrorContainer = Color(0xFF5C0F09)

/** Light colour scheme. Brand colours are used as tokens, so Material defaults never show through. */
val StickerLightScheme: ColorScheme = lightColorScheme(
    primary = StickerPalette.Jet,
    onPrimary = StickerPalette.Ink,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = StickerPalette.Ink,
    inversePrimary = StickerPalette.Jet,
    secondary = StickerPalette.Sea,
    onSecondary = StickerPalette.Ink,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = StickerPalette.Ink,
    tertiary = StickerPalette.Sun,
    onTertiary = StickerPalette.Ink,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = StickerPalette.Ink,
    background = StickerPalette.Paper,
    onBackground = StickerPalette.Ink,
    surface = StickerPalette.Paper,
    onSurface = StickerPalette.Ink,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceTint = StickerPalette.Jet,
    inverseSurface = StickerPalette.Ink,
    inverseOnSurface = StickerPalette.Paper,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = LightError,
    onError = Color(0xFFFFFFFF),
    errorContainer = LightErrorContainer,
    onErrorContainer = StickerPalette.Ink,
    surfaceContainerLowest = StickerPalette.Paper,
    surfaceContainerLow = Color(0xFFFBF0E2),
    surfaceContainer = Color(0xFFF6EADB),
    surfaceContainerHigh = LightSurfaceVariant,
    surfaceContainerHighest = Color(0xFFEBDDC9),
)

/** Dark colour scheme. Ink is the background; Paper is the text. */
val StickerDarkScheme: ColorScheme = darkColorScheme(
    primary = StickerPalette.Jet,
    onPrimary = StickerPalette.Ink,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = LightPrimaryContainer,
    inversePrimary = StickerPalette.Jet,
    secondary = StickerPalette.Sea,
    onSecondary = StickerPalette.Ink,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = LightSecondaryContainer,
    tertiary = StickerPalette.Sun,
    onTertiary = StickerPalette.Ink,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = LightTertiaryContainer,
    background = StickerPalette.Ink,
    onBackground = StickerPalette.Paper,
    surface = DarkSurface,
    onSurface = StickerPalette.Paper,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceTint = StickerPalette.Jet,
    inverseSurface = StickerPalette.Paper,
    inverseOnSurface = StickerPalette.Ink,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = DarkError,
    onError = DarkErrorContainer,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkError,
    surfaceContainerLowest = StickerPalette.Ink,
    surfaceContainerLow = Color(0xFF232237),
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkSurfaceVariant,
    surfaceContainerHighest = Color(0xFF3D3C56),
)

/** Colours that Material 3 has no slot for. Read them through [StickerTheme]. */
@Immutable
data class StickerExtraColors(
    /** Lilac accent. Use sparingly; its label is [onAccent]. */
    val accent: Color,
    val onAccent: Color,
    /** Tint for subject areas that are kept (drawn at partial alpha over the matte preview). */
    val keptTint: Color,
    /** Tint for erased areas in the brush overlay. */
    val removedTint: Color,
    /** Transparency checkerboard, two alternating tones. */
    val checkerA: Color,
    val checkerB: Color,
)

val StickerLightExtra = StickerExtraColors(
    accent = StickerPalette.Lilac,
    onAccent = StickerPalette.Ink,
    keptTint = StickerPalette.Sea,
    removedTint = StickerPalette.Jet,
    checkerA = Color(0xFFFFFFFF),
    checkerB = Color(0xFFE4DACB),
)

val StickerDarkExtra = StickerExtraColors(
    accent = StickerPalette.Lilac,
    onAccent = StickerPalette.Ink,
    keptTint = StickerPalette.Sea,
    removedTint = StickerPalette.Jet,
    checkerA = Color(0xFF33324A),
    checkerB = Color(0xFF27263B),
)

/** Provided by [StickerJetTheme]; the default is the light palette. */
val LocalStickerExtraColors = staticCompositionLocalOf { StickerLightExtra }
