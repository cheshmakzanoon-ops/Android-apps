package com.agency.timeflip.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Type scale. Uses the system default family only, so no font is downloaded. Sizes are in sp and
 * no text container has a fixed height, so the layout holds at font scale 2.0.
 */
object TimeFlipType {
    /** Screen titles and the welcome heading. */
    val Title = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    )

    /** Section headings and card-like row titles. */
    val Section = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    )

    /** Body text. */
    val Body = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    )

    /** Secondary body text and supporting copy. */
    val BodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )

    /** Buttons and chip labels. */
    val Label = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )

    /** Captions, metadata and status lines. */
    val Caption = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )

    /** Numeric readouts (time, percentages, scale and rotation). Tabular figures keep digits aligned. */
    val Readout = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontFeatureSettings = "tnum",
    )
}

internal val TimeFlipTypography: Typography = Typography(
    headlineSmall = TimeFlipType.Title,
    titleMedium = TimeFlipType.Section,
    bodyLarge = TimeFlipType.Body,
    bodyMedium = TimeFlipType.BodySmall,
    labelLarge = TimeFlipType.Label,
    labelMedium = TimeFlipType.Caption,
)
