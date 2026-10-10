package com.agency.stickerjet.domain

/**
 * Rules for the sticker window inside a source clip.
 *
 * A valid trim lies inside the source, `0 <= startMs < endMs <= sourceMs`, and its length is within
 * [StickerLimits.MIN_STICKER_MS]..[StickerLimits.MAX_STICKER_MS]. One exception exists: a source shorter
 * than the minimum has no valid window, so its trim is the full source. [ImportPolicy] rejects such a
 * source as TOO_SHORT, so a project never holds that trim.
 */
object TrimPolicy {

    /** The window selected at import: the first [StickerLimits.DEFAULT_STICKER_MS], clamped to the source. */
    fun defaultTrim(sourceMs: Long): TrimRange =
        clamp(TrimRange(startMs = 0L, endMs = StickerLimits.DEFAULT_STICKER_MS), sourceMs)

    /**
     * Returns the nearest valid window to [trim] inside a source of [sourceMs] milliseconds.
     *
     * - The length is the requested length limited to `[MIN, min(MAX, sourceMs)]`. An inverted or empty
     *   request counts as length zero, so it becomes the minimum length.
     * - The start is kept when possible. If the window would run past the source end, it slides back so
     *   that it ends exactly at the source end. A negative start becomes 0.
     * - A source shorter than the minimum returns the full source (see the class comment).
     * - A non-positive [sourceMs] returns `TrimRange(0, 0)`.
     *
     * The function is idempotent: clamping a valid trim returns it unchanged.
     */
    fun clamp(trim: TrimRange, sourceMs: Long): TrimRange {
        if (sourceMs <= 0L) return TrimRange(startMs = 0L, endMs = 0L)
        if (sourceMs < StickerLimits.MIN_STICKER_MS) return TrimRange(startMs = 0L, endMs = sourceMs)
        val maxLength = minOf(StickerLimits.MAX_STICKER_MS, sourceMs)
        val length = requestedLength(trim).coerceIn(StickerLimits.MIN_STICKER_MS, maxLength)
        // length <= sourceMs, so the upper bound of coerceIn is never negative.
        val start = trim.startMs.coerceIn(0L, sourceMs - length)
        return TrimRange(startMs = start, endMs = start + length)
    }

    /**
     * True when [trim] lies inside `[0, sourceMs]` and its length is within the sticker limits.
     * Boundaries are inclusive: a length of exactly [StickerLimits.MIN_STICKER_MS] or
     * [StickerLimits.MAX_STICKER_MS] is valid, and so is a window ending exactly at [sourceMs].
     */
    fun isValid(trim: TrimRange, sourceMs: Long): Boolean =
        trim.startMs >= 0L &&
            trim.endMs > trim.startMs &&
            trim.endMs <= sourceMs &&
            trim.durationMs in StickerLimits.MIN_STICKER_MS..StickerLimits.MAX_STICKER_MS

    /** `endMs - startMs` without overflow: an inverted range gives 0, an overflowing one gives [Long.MAX_VALUE]. */
    private fun requestedLength(trim: TrimRange): Long {
        if (trim.endMs <= trim.startMs) return 0L
        val difference = trim.endMs - trim.startMs
        return if (difference < 0L) Long.MAX_VALUE else difference
    }
}
