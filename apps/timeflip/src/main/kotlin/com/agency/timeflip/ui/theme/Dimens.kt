package com.agency.timeflip.ui.theme

import androidx.compose.ui.unit.dp

/** Spacing scale in dp: 4, 8, 12, 16, 24, 32. Figma spacing variables use the same values. */
object TimeFlipSpacing {
    val Space4 = 4.dp
    val Space8 = 8.dp
    val Space12 = 12.dp
    val Space16 = 16.dp
    val Space24 = 24.dp
    val Space32 = 32.dp
}

/** Corner radius in dp. Panels and controls use 4 or 8; nothing is a generic rounded card. */
object TimeFlipRadius {
    val Small = 4.dp
    val Medium = 8.dp
}

object TimeFlipSize {
    /** Minimum touch target for every control. */
    val MinTouchTarget = 48.dp

    /** Thumbnail in a project row. */
    val RowThumbnail = 56.dp

    /** Thumbnail in the photo chooser. */
    val PhotoSlotHeight = 176.dp

    /** Width of the grab area of the comparison divider. */
    val DividerGrab = 48.dp

    /** Width of the side gutter at phone width. */
    val ScreenGutter = 16.dp
}
