package com.agency.stickerjet.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** True when the system asks for no animation ("Remove animations" in developer or accessibility settings). */
val LocalReducedMotion = compositionLocalOf { false }

/**
 * Reads the system animator scale. A scale of zero means the user has removed animations.
 * Falls back to normal motion when the setting cannot be read.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        } catch (e: Exception) {
            false
        }
    }
}

/** Motion specs. Every animated transition picks its spec through [fade] or [StickerMotion.standard]. */
object StickerMotion {
    const val STANDARD_MS: Int = 240

    /** Snap when reduced motion is on, otherwise a short ease. */
    fun <T> standard(reduced: Boolean): FiniteAnimationSpec<T> =
        if (reduced) snap<T>() else tween<T>(durationMillis = STANDARD_MS, easing = FastOutSlowInEasing)
}
