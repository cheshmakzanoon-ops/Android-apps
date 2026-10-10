package com.agency.timeflip.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape

internal val TimeFlipShapes = Shapes(
    extraSmall = RoundedCornerShape(TimeFlipRadius.Small),
    small = RoundedCornerShape(TimeFlipRadius.Small),
    medium = RoundedCornerShape(TimeFlipRadius.Medium),
    large = RoundedCornerShape(TimeFlipRadius.Medium),
    extraLarge = RoundedCornerShape(TimeFlipRadius.Medium),
)

/** The only theme. Dark, because the photos are the content. */
@Composable
fun TimeFlipTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TimeFlipColorScheme,
        typography = TimeFlipTypography,
        shapes = TimeFlipShapes,
        content = content,
    )
}
