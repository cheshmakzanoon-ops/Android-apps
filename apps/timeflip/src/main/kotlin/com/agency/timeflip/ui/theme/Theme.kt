package com.agency.timeflip.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes

/** Corner shapes for Material components. Radius is 4 dp for small controls and 8 dp for panels. */
internal val TimeFlipShapes = Shapes(
    extraSmall = RoundedCornerShape(TimeFlipRadius.Small),
    small = RoundedCornerShape(TimeFlipRadius.Small),
    medium = RoundedCornerShape(TimeFlipRadius.Medium),
    large = RoundedCornerShape(TimeFlipRadius.Medium),
    extraLarge = RoundedCornerShape(TimeFlipRadius.Medium),
)
