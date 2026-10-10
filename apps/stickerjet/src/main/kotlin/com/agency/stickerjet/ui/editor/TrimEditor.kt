package com.agency.stickerjet.ui.editor

import com.agency.stickerjet.domain.StickerLimits
import com.agency.stickerjet.domain.TrimRange

/**
 * Handle rules for the trim sliders. Each handle moves while the other stays put, and the result is
 * always inside the source and within the sticker length limits. The domain's TrimPolicy.clamp is the
 * final guard when the window is saved, so a rule here can never produce an invalid project.
 */
object TrimEditor {

    /**
     * Moves the start handle with the end fixed. The start is pulled back or forward so that the window
     * stays between [StickerLimits.MIN_STICKER_MS] and [StickerLimits.MAX_STICKER_MS] long.
     */
    fun moveStart(trim: TrimRange, requestedStartMs: Long, sourceMs: Long): TrimRange {
        require(sourceMs >= StickerLimits.MIN_STICKER_MS) { "Source is shorter than the sticker minimum" }
        val end = trim.endMs.coerceIn(StickerLimits.MIN_STICKER_MS, sourceMs)
        val lowest = maxOf(0L, end - StickerLimits.MAX_STICKER_MS)
        val highest = end - StickerLimits.MIN_STICKER_MS
        return TrimRange(startMs = requestedStartMs.coerceIn(lowest, highest), endMs = end)
    }

    /**
     * Moves the end handle with the start fixed, keeping the window within the length limits and the
     * source.
     */
    fun moveEnd(trim: TrimRange, requestedEndMs: Long, sourceMs: Long): TrimRange {
        require(sourceMs >= StickerLimits.MIN_STICKER_MS) { "Source is shorter than the sticker minimum" }
        val start = trim.startMs.coerceIn(0L, sourceMs - StickerLimits.MIN_STICKER_MS)
        val lowest = start + StickerLimits.MIN_STICKER_MS
        val highest = minOf(sourceMs, start + StickerLimits.MAX_STICKER_MS)
        return TrimRange(startMs = start, endMs = requestedEndMs.coerceIn(lowest, highest))
    }

    /** Range the start slider can reach: the earliest start to the last start that leaves a minimum-length window. */
    fun startRange(sourceMs: Long): ClosedFloatingPointRange<Float> =
        0f..(sourceMs - StickerLimits.MIN_STICKER_MS).coerceAtLeast(0L).toFloat()

    /** Range the end slider can reach: the first end that allows a minimum-length window to the source end. */
    fun endRange(sourceMs: Long): ClosedFloatingPointRange<Float> =
        StickerLimits.MIN_STICKER_MS.toFloat()..sourceMs.toFloat()
}
