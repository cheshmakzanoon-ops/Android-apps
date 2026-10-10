package com.agency.stickerjet.engine

import com.agency.stickerjet.domain.StickerLimits

/**
 * Frame timing of the exported loop, following architecture §3.1 (`ExportPlan`).
 * Frame `i` starts at `i * 1000 / fps` ms (integer division) and lasts until frame `i + 1` starts, so the
 * durations are 83 or 84 ms at 12 fps and sum exactly to `frameCount * 1000 / fps`.
 */
object LoopTiming {

    /** Number of frames in a loop of [durationMs]. Floors partial frames, never fewer than one. */
    fun frameCount(durationMs: Long, fps: Int = StickerLimits.FPS): Int =
        maxOf(1L, durationMs * fps / 1000L).toInt()

    /** Source timestamps to decode: `startMs + i * 1000 / fps` for each frame index. */
    fun timestampsMs(startMs: Long, frameCount: Int, fps: Int = StickerLimits.FPS): List<Long> =
        List(frameCount) { i -> startMs + startOf(i, fps) }

    /** Per-frame display durations in milliseconds. They sum to `frameCount * 1000 / fps`. */
    fun durationsMs(frameCount: Int, fps: Int = StickerLimits.FPS): IntArray =
        IntArray(frameCount) { i -> (startOf(i + 1, fps) - startOf(i, fps)).toInt() }

    private fun startOf(index: Int, fps: Int): Long = index.toLong() * 1000L / fps
}
