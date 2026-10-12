package com.agency.photoduel.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.MotionDurationScale

/** Motion durations (ms) from res/values/integers.xml (`pd_motion_*`). */
object PdMotion {
    const val INSTANT_MS = 100
    const val SHORT_MS = 150
    const val MEDIUM_MS = 250
    const val LONG_MS = 400

    /** Length of the results bar animation. */
    const val REVEAL_MS = 900

    /** Pause between the tally and the winner step of the reveal. */
    const val REVEAL_PAUSE_MS = 600
}

/**
 * True when motion should be reduced. Screens use it to replace movement with an instant change: the
 * results reveal shows the final tally at once, and no element slides or scales.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * Reads the system "Remove animations" setting without touching a Context: Compose runs effects with a
 * [MotionDurationScale] taken from the system animator duration scale, and a scale of zero means the
 * user asked for no animation. Starts as false until the first effect has run, then follows changes.
 */
@Composable
fun rememberSystemReduceMotion(): Boolean {
    var reduce by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val scale = coroutineContext[MotionDurationScale] ?: return@LaunchedEffect
        // The platform keeps the scale up to date while the app runs; follow it.
        snapshotFlow { scale.scaleFactor }.collect { reduce = it == 0f }
    }
    return reduce
}
