package com.agency.photoduel.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * PhotoDuel's Material 3 theme. Light and dark palettes come from [PdTokens]; dynamic colour is not used
 * so photos are always framed by the same calm neutrals. [reduceMotion] is published as
 * [LocalReduceMotion]; by default it follows the system "Remove animations" setting.
 */
@Composable
fun PhotoDuelTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    reduceMotion: Boolean = rememberSystemReduceMotion(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) PdTokens.Dark else PdTokens.Light
    CompositionLocalProvider(
        LocalPdPalette provides palette,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(
            colorScheme = palette.toColorScheme(darkTheme),
            typography = PhotoDuelTypography,
            shapes = PhotoDuelShapes,
            content = content,
        )
    }
}
