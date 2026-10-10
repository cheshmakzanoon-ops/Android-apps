package com.agency.timeflip.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.agency.timeflip.ui.theme.TimeFlipColorScheme
import com.agency.timeflip.ui.theme.TimeFlipShapes
import com.agency.timeflip.ui.theme.TimeFlipTypography

/**
 * The only theme: dark, because the photos are the content. MainActivity may wrap the app in it,
 * and TimeFlipApp applies it again, which is harmless.
 */
@Composable
fun TimeFlipTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TimeFlipColorScheme,
        typography = TimeFlipTypography,
        shapes = TimeFlipShapes,
        content = content,
    )
}
