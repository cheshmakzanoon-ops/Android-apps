package com.agency.stickerjet.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing scale in dp. Screens use these tokens instead of raw literals. */
object Spacing {
    val xxs: Dp = 4.dp
    val xs: Dp = 8.dp
    val sm: Dp = 12.dp
    val md: Dp = 16.dp
    val lg: Dp = 24.dp
    val xl: Dp = 32.dp
    val xxl: Dp = 48.dp

    /** Horizontal gutter on phones. Content never sits closer than this to the screen edge. */
    val gutter: Dp = 16.dp
}

/** Minimum size of any tappable element, per the Android and WCAG target guidance. */
object TouchTarget {
    val min: Dp = 48.dp
}
