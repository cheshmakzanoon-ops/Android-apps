package com.agency.stickerjet.domain

/**
 * Frame schedule of the exported loop. The frames are spaced as evenly as whole milliseconds allow.
 *
 * Frame i starts at `floor(i * 1000 / FPS)` ms after the trim start. Each duration is the distance to the
 * next frame's start, and the last frame lasts until the loop ends. Floor differences telescope, so the
 * durations sum to the loop length exactly. At 12 fps they are 83 or 84 ms, and the last frame is 42 to 125 ms.
 *
 * Only the positive length that fits in Int milliseconds is required here. The sticker length rules are
 * enforced by [TrimPolicy].
 */
object ExportPlan {

    /** Number of frames: `max(1, round(durationMs * FPS / 1000))`, with halves rounded up. */
    fun frameCount(trim: TrimRange): Int {
        requirePlayable(trim)
        val frames = (trim.durationMs * StickerLimits.FPS + HALF_SECOND_MS) / ONE_SECOND_MS
        return maxOf(1L, frames).toInt()
    }

    /** Timestamps of the frames in the loop: `trim.startMs + i * 1000 / FPS` for i in `0 until frameCount`. */
    fun frameTimestampsMs(trim: TrimRange): List<Long> {
        val count = frameCount(trim)
        return List(count) { index -> trim.startMs + offsetMs(index) }
    }

    /** Integer durations that sum exactly to `trim.durationMs`. */
    fun frameDurationsMs(trim: TrimRange): IntArray {
        val count = frameCount(trim)
        return IntArray(count) { index ->
            val nextOffset = if (index + 1 < count) offsetMs(index + 1) else trim.durationMs
            (nextOffset - offsetMs(index)).toInt()
        }
    }

    /** Start of frame [index] relative to the loop start, in whole milliseconds. */
    private fun offsetMs(index: Int): Long = index.toLong() * ONE_SECOND_MS / StickerLimits.FPS

    private fun requirePlayable(trim: TrimRange) {
        require(trim.durationMs in 1L..Int.MAX_VALUE.toLong()) {
            "Trim length must be between 1 ms and Int.MAX_VALUE ms, was ${trim.durationMs}"
        }
    }

    private const val ONE_SECOND_MS = 1_000L
    private const val HALF_SECOND_MS = 500L
}
