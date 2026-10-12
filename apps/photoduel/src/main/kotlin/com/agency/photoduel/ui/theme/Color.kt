package com.agency.photoduel.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * PhotoDuel colour tokens: warm paper neutrals, dark ink and one cobalt accent. The photos carry the
 * colour; the interface stays quiet around them. There are no gradients anywhere in the app.
 *
 * Every value here equals the colour of the same name in res/values/colors.xml (`pd_<token>_light` and
 * `pd_<token>_dark`) and the Figma variables "Color Light" and "Color Dark". PaletteTokensTest checks
 * that the Kotlin and XML values agree.
 */
@Immutable
data class PdPalette(
    val bgCanvas: Color,
    val bgSurface: Color,
    val bgSunken: Color,
    val bgInverse: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textInverse: Color,
    val textOnAccent: Color,
    val textOnPhoto: Color,
    val accent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val border: Color,
    val borderStrong: Color,
    val error: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val success: Color,
    val successContainer: Color,
    val scrim: Color,
    val captionOverlay: Color,
)

object PdTokens {
    val Light = PdPalette(
        bgCanvas = Color(0xFFF6F1E7),
        bgSurface = Color(0xFFFBF8F2),
        bgSunken = Color(0xFFEDE6D6),
        bgInverse = Color(0xFF1B1A17),
        textPrimary = Color(0xFF1B1A17),
        textSecondary = Color(0xFF5B564B),
        textInverse = Color(0xFFF6F1E7),
        textOnAccent = Color(0xFFFFFFFF),
        textOnPhoto = Color(0xFFFFFFFF),
        accent = Color(0xFF2540C8),
        accentContainer = Color(0xFFDCE3FF),
        onAccentContainer = Color(0xFF0B1450),
        border = Color(0xFFDCD3BF),
        borderStrong = Color(0xFF8A8270),
        error = Color(0xFFB3261E),
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
        success = Color(0xFF2E6B3A),
        successContainer = Color(0xFFD9EBDC),
        scrim = Color(0x9915140F),
        captionOverlay = Color(0xB815140F),
    )

    val Dark = PdPalette(
        bgCanvas = Color(0xFF15140F),
        bgSurface = Color(0xFF1F1D18),
        bgSunken = Color(0xFF2A2822),
        bgInverse = Color(0xFFF6F1E7),
        textPrimary = Color(0xFFF6F1E7),
        textSecondary = Color(0xFFC9C1AE),
        textInverse = Color(0xFF1B1A17),
        textOnAccent = Color(0xFF0B1450),
        textOnPhoto = Color(0xFFFFFFFF),
        accent = Color(0xFF9DB0FF),
        accentContainer = Color(0xFF1E2A6E),
        onAccentContainer = Color(0xFFDCE3FF),
        border = Color(0xFF3A372F),
        borderStrong = Color(0xFF6B6657),
        error = Color(0xFFF2B8B5),
        errorContainer = Color(0xFF410E0B),
        onErrorContainer = Color(0xFFF9DEDC),
        success = Color(0xFF8FCB9B),
        successContainer = Color(0xFF12301A),
        scrim = Color(0x9915140F),
        captionOverlay = Color(0xB815140F),
    )
}

/** Material 3 roles built from the tokens. Secondary and tertiary reuse the neutrals so the accent stays the only colour. */
fun PdPalette.toColorScheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = textOnAccent,
        primaryContainer = accentContainer,
        onPrimaryContainer = onAccentContainer,
        inversePrimary = if (dark) PdTokens.Light.accent else PdTokens.Dark.accent,
        secondary = textSecondary,
        onSecondary = bgSurface,
        secondaryContainer = bgSunken,
        onSecondaryContainer = textPrimary,
        tertiary = success,
        onTertiary = bgSurface,
        tertiaryContainer = successContainer,
        onTertiaryContainer = textPrimary,
        background = bgCanvas,
        onBackground = textPrimary,
        surface = bgSurface,
        onSurface = textPrimary,
        surfaceVariant = bgSunken,
        onSurfaceVariant = textSecondary,
        surfaceTint = accent,
        inverseSurface = bgInverse,
        inverseOnSurface = textInverse,
        error = error,
        // Dark: deep red on the light error tone; light: white on the strong error tone.
        onError = if (dark) errorContainer else Color.White,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        outline = borderStrong,
        outlineVariant = border,
        scrim = scrim,
        surfaceBright = bgSurface,
        surfaceDim = bgSunken,
        surfaceContainerLowest = bgCanvas,
        surfaceContainerLow = bgCanvas,
        surfaceContainer = bgSurface,
        surfaceContainerHigh = bgSurface,
        surfaceContainerHighest = bgSunken,
    )
}

/** The app's own tokens for roles Material 3 does not name (success, borders, photo captions). */
val LocalPdPalette = staticCompositionLocalOf { PdTokens.Light }
