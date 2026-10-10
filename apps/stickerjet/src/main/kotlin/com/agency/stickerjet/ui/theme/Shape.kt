package com.agency.stickerjet.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Rounded, friendly corners that suit sticker artwork. Material's sharp defaults are replaced so
 * cards and sheets never look like stock Material chrome.
 */
val StickerShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Pill shape for buttons and chips. */
val PillShape = RoundedCornerShape(percent = 50)

/** Sticker-sized tiles in the library and the preview stage. */
val StickerTileShape = RoundedCornerShape(24.dp)
