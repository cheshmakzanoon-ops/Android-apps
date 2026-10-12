package com.agency.photoduel.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Type tokens from res/values/integers.xml (`pd_type_*`), in sp so the user's font size is honoured.
 * The family is the platform default; no font file is bundled.
 */
object PdType {
    val display = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 42.sp, letterSpacing = (-0.25).sp)
    val headline = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp)
    val title = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 28.sp)
    val body = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp)
    val bodyStrong = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp)
    val label = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp)
    val caption = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp)

    /** Tabular figures so the countdown does not jitter as digits change. */
    val countdown = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        fontFeatureSettings = "tnum",
    )
}

val PhotoDuelTypography = Typography(
    displayLarge = PdType.display,
    displayMedium = PdType.display,
    displaySmall = PdType.display.copy(fontSize = 30.sp, lineHeight = 38.sp),
    headlineLarge = PdType.headline.copy(fontSize = 28.sp, lineHeight = 36.sp),
    headlineMedium = PdType.headline,
    headlineSmall = PdType.headline.copy(fontSize = 22.sp, lineHeight = 30.sp),
    titleLarge = PdType.title,
    titleMedium = PdType.bodyStrong,
    titleSmall = PdType.label,
    bodyLarge = PdType.body,
    bodyMedium = PdType.body.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = PdType.caption,
    labelLarge = PdType.label,
    labelMedium = PdType.caption.copy(fontWeight = FontWeight.Medium),
    labelSmall = PdType.caption.copy(fontSize = 11.sp),
)
