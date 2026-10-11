package com.agency.stickerjet.domain

/*
 * Editing rules for [StickerProject]. Each function returns a new project and sets updatedAtMs to nowMs.
 * The project itself is never mutated. The rules are:
 * - withTrim clamps the window (a UI drag can pass any range). See [TrimPolicy.clamp].
 * - withTransform, withCaption, withStroke and withTitle reject values that cannot be meant
 *   (NaN, non-positive scale, out-of-range colour, empty stroke). Such values are caller bugs and throw
 *   IllegalArgumentException. Caller-facing values that have a natural range are clamped or normalized instead.
 */

/** Sets the sticker window. The requested range is clamped into the source, see [TrimPolicy.clamp]. */
fun StickerProject.withTrim(trim: TrimRange, nowMs: Long): StickerProject =
    copy(trim = TrimPolicy.clamp(trim, source.durationMs), updatedAtMs = nowMs)

/**
 * Sets placement. The scale must be finite and positive, and the offsets must be finite.
 * [Transform.quarterTurns] is normalized to 0..3, so -1 becomes 3 and 5 becomes 1.
 */
fun StickerProject.withTransform(transform: Transform, nowMs: Long): StickerProject {
    require(transform.scale.isFinite() && transform.scale > 0f) {
        "Scale must be a positive finite number, was ${transform.scale}"
    }
    require(transform.offsetX.isFinite() && transform.offsetY.isFinite()) {
        "Offsets must be finite, were ${transform.offsetX}, ${transform.offsetY}"
    }
    return copy(
        transform = transform.copy(quarterTurns = Math.floorMod(transform.quarterTurns, QUARTER_TURNS_PER_FULL)),
        updatedAtMs = nowMs,
    )
}

/**
 * Sets the caption. The text is normalized by [CaptionPolicy.normalize]. [Caption.argb] must fit in
 * 32 bits as 0xAARRGGBB.
 */
fun StickerProject.withCaption(caption: Caption, nowMs: Long): StickerProject {
    require(caption.argb in 0L..MAX_ARGB) { "Caption colour must fit in 0xAARRGGBB, was ${caption.argb}" }
    return copy(
        caption = caption.copy(text = CaptionPolicy.normalize(caption.text)),
        updatedAtMs = nowMs,
    )
}

/** Sets the background mode. Existing mask strokes are kept, so switching back restores the correction. */
fun StickerProject.withBackground(mode: BackgroundMode, nowMs: Long): StickerProject =
    copy(background = mode, updatedAtMs = nowMs)

/**
 * Appends a mask correction. The stroke needs at least one point, every point must lie in 0..1, and the
 * radius must be finite and positive. Strokes keep their order, so the mask can be rebuilt exactly.
 */
fun StickerProject.withStroke(stroke: MaskStroke, nowMs: Long): StickerProject {
    require(stroke.points.isNotEmpty()) { "A mask stroke needs at least one point" }
    require(stroke.points.all { it.x in 0f..1f && it.y in 0f..1f }) { "Stroke points must lie in 0..1" }
    require(stroke.radius.isFinite() && stroke.radius > 0f) { "Stroke radius must be positive, was ${stroke.radius}" }
    return copy(maskStrokes = maskStrokes + stroke, updatedAtMs = nowMs)
}

/**
 * Removes every mask correction, so the mask falls back to the segmentation result alone. Only
 * maskStrokes and updatedAtMs change. The background mode is kept.
 */
fun StickerProject.withoutStrokes(nowMs: Long): StickerProject =
    copy(maskStrokes = emptyList(), updatedAtMs = nowMs)

/** Renames the project. The title is normalized by [StickerProjects.normalizeTitle]. */
fun StickerProject.withTitle(title: String, nowMs: Long): StickerProject =
    copy(title = StickerProjects.normalizeTitle(title), updatedAtMs = nowMs)

private const val QUARTER_TURNS_PER_FULL = 4
private const val MAX_ARGB = 0xFFFF_FFFFL
