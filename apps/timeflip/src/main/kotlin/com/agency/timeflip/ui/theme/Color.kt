package com.agency.timeflip.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Colour tokens for the dark, photo-first theme. Each token has a Figma colour style with the same
 * name (docs/design.md). The app is dark-only, because the photos are the content and the chrome
 * must stay out of their way. Status is never shown by colour alone: every status also has an icon
 * and text.
 */
object TimeFlipColor {
    /** Near-black window background. Matches the launcher window colour. */
    val Background = Color(0xFF111214)

    /** Panels and the top app bar. */
    val Surface = Color(0xFF1A1B20)

    /** Inputs, chips at rest and thumbnail placeholders. */
    val SurfaceVariant = Color(0xFF25272E)

    /** Raised elements such as selected rows and dialogs. */
    val SurfaceRaised = Color(0xFF2E3139)

    /** Borders of interactive controls. Contrast is at least 3:1 on Background. */
    val Outline = Color(0xFF6E737E)

    /** Dividers and decorative rules. */
    val OutlineVariant = Color(0xFF3A3E47)

    /** Primary text. Off-white from the launcher mark. */
    val OnBackground = Color(0xFFF2F0EA)

    /** Secondary text and captions. */
    val OnSurfaceVariant = Color(0xFFB9B6AE)

    /** The single warm accent, taken from the launcher mark. */
    val Accent = Color(0xFFE0A458)

    /** Text and icons on Accent. */
    val OnAccent = Color(0xFF1E1408)

    /** Selected state containers. */
    val AccentContainer = Color(0xFF3D2D14)

    val OnAccentContainer = Color(0xFFF6DDB5)

    /** Errors. Always paired with an icon and text. */
    val Error = Color(0xFFFF8A80)

    val OnError = Color(0xFF3A0B07)

    val ErrorContainer = Color(0xFF5C1A14)

    val OnErrorContainer = Color(0xFFFFDAD5)

    /** Positive status such as a reliable alignment. Paired with an icon and text. */
    val Positive = Color(0xFF8FCB9B)
}

internal val TimeFlipColorScheme: ColorScheme = darkColorScheme(
    primary = TimeFlipColor.Accent,
    onPrimary = TimeFlipColor.OnAccent,
    primaryContainer = TimeFlipColor.AccentContainer,
    onPrimaryContainer = TimeFlipColor.OnAccentContainer,
    secondary = TimeFlipColor.SurfaceRaised,
    onSecondary = TimeFlipColor.OnBackground,
    secondaryContainer = TimeFlipColor.SurfaceVariant,
    onSecondaryContainer = TimeFlipColor.OnBackground,
    tertiary = TimeFlipColor.Positive,
    onTertiary = TimeFlipColor.Background,
    background = TimeFlipColor.Background,
    onBackground = TimeFlipColor.OnBackground,
    surface = TimeFlipColor.Surface,
    onSurface = TimeFlipColor.OnBackground,
    surfaceVariant = TimeFlipColor.SurfaceVariant,
    onSurfaceVariant = TimeFlipColor.OnSurfaceVariant,
    surfaceContainer = TimeFlipColor.Surface,
    surfaceContainerHigh = TimeFlipColor.SurfaceRaised,
    outline = TimeFlipColor.Outline,
    outlineVariant = TimeFlipColor.OutlineVariant,
    error = TimeFlipColor.Error,
    onError = TimeFlipColor.OnError,
    errorContainer = TimeFlipColor.ErrorContainer,
    onErrorContainer = TimeFlipColor.OnErrorContainer,
)
