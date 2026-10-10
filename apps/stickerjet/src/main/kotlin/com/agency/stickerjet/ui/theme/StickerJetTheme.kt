package com.agency.stickerjet.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Root theme for every StickerJet screen. It follows the system light or dark setting, provides the
 * brand colour schemes, the Nunito type scale, the rounded shapes and the reduced-motion flag.
 */
@Composable
fun StickerJetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val reducedMotion = rememberReducedMotion()
    CompositionLocalProvider(
        LocalStickerExtraColors provides if (darkTheme) StickerDarkExtra else StickerLightExtra,
        LocalReducedMotion provides reducedMotion,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) StickerDarkScheme else StickerLightScheme,
            typography = StickerTypography,
            shapes = StickerShapes,
            content = content,
        )
    }
}

/** Read-only access to the StickerJet tokens that Material 3 does not provide. */
object StickerTheme {
    /** Extra colours for the current light or dark scheme. */
    val extra: StickerExtraColors
        @Composable get() = LocalStickerExtraColors.current

    /** True when the system has removed animations. */
    val reducedMotion: Boolean
        @Composable get() = LocalReducedMotion.current
}
