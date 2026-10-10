package com.agency.timeflip.domain.model

import kotlin.math.ceil

/**
 * Timing of one exported video: hold on BEFORE, transition, hold on AFTER.
 * Frame i is presented at i × 1_000_000 / fps microseconds, so timing never drifts.
 */
data class TimelinePlan(
    val transitionMs: Int,
    val holdBeforeMs: Int = DEFAULT_HOLD_MS,
    val holdAfterMs: Int = DEFAULT_HOLD_MS,
    val fps: Int = DEFAULT_FPS,
) {
    init {
        require(transitionMs > 0) { "transitionMs must be positive" }
        require(holdBeforeMs >= 0 && holdAfterMs >= 0) { "holds must not be negative" }
        require(fps in 1..60) { "fps must be within 1..60, was $fps" }
    }

    val totalDurationMs: Int get() = holdBeforeMs + transitionMs + holdAfterMs

    /** Frames needed to cover [totalDurationMs] exactly, rounded up to a whole frame. */
    val frameCount: Int get() = ceil(totalDurationMs.toDouble() * fps / 1000.0).toInt()

    fun presentationTimeUs(frameIndex: Int): Long {
        require(frameIndex in 0 until frameCount) { "frame index $frameIndex outside 0 until $frameCount" }
        return frameIndex * 1_000_000L / fps
    }

    /**
     * Raw linear transition progress (0..1, before easing) at [frameIndex].
     * Holds return 0 before the transition and 1 after it.
     */
    fun linearProgress(frameIndex: Int): Double {
        val timeMs = frameIndex * 1000.0 / fps
        val progress = (timeMs - holdBeforeMs) / transitionMs
        return progress.coerceIn(0.0, 1.0)
    }

    companion object {
        const val DEFAULT_HOLD_MS = 800
        const val DEFAULT_FPS = 30
    }
}
