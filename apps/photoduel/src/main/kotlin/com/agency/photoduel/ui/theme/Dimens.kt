package com.agency.photoduel.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Spacing, radius and elevation tokens from res/values/dimens.xml (`pd_space_*`, `pd_radius_*`). */
object PdSpace {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
    val touchTarget = 48.dp
    val gutter = 16.dp
    val contentMaxWidth = 640.dp
    val photoCardMinWidth = 148.dp
}

object PdRadius {
    val sm = 8.dp
    val md = 12.dp
    val lg = 20.dp
    val xl = 28.dp
}

object PdElevation {
    val level0 = 0.dp
    val level1 = 1.dp
    val level2 = 3.dp
    val level3 = 6.dp
}

val PhotoDuelShapes = Shapes(
    extraSmall = RoundedCornerShape(PdRadius.sm / 2),
    small = RoundedCornerShape(PdRadius.sm),
    medium = RoundedCornerShape(PdRadius.md),
    large = RoundedCornerShape(PdRadius.lg),
    extraLarge = RoundedCornerShape(PdRadius.xl),
)
