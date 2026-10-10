package com.agency.stickerjet.domain

import kotlinx.serialization.Serializable

/**
 * Metadata of an imported source clip, read once at import time. The clip itself is copied into
 * app-private storage, so [fileName] is relative to the project directory and never a content URI.
 */
@Serializable
data class SourceVideo(
    val fileName: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    /** Display rotation reported by the container: 0, 90, 180 or 270. */
    val rotationDegrees: Int,
    val sizeBytes: Long,
)

/** Half-open time window [startMs, endMs) of the source clip that becomes the sticker animation. */
@Serializable
data class TrimRange(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * Placement of the source frame on the square sticker canvas.
 * [scale] is relative to the fit-to-canvas size, [offsetX]/[offsetY] are normalized to canvas size,
 * and [quarterTurns] rotates the frame in 90-degree steps.
 */
@Serializable
data class Transform(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val quarterTurns: Int = 0,
)

@Serializable
enum class CaptionPosition { TOP, BOTTOM }

/** Optional caption drawn over the animation. [argb] is the text colour as 0xAARRGGBB. */
@Serializable
data class Caption(
    val text: String = "",
    val position: CaptionPosition = CaptionPosition.BOTTOM,
    val argb: Long = 0xFFFFFFFFL,
)

@Serializable
enum class BackgroundMode {
    /** Keep the original pixels. */
    ORIGINAL,

    /** Remove the background using the segmentation mask plus manual strokes. */
    REMOVE_BACKGROUND,
}

@Serializable
enum class StrokeMode { RESTORE, ERASE }

/** A point in normalized source-frame coordinates, each component in 0..1. */
@Serializable
data class NormalizedPoint(val x: Float, val y: Float)

/**
 * One manual mask correction. [radius] is normalized to the shorter side of the frame. Strokes are
 * kept in order so undo is exact and rebuilding the mask is deterministic.
 */
@Serializable
data class MaskStroke(
    val mode: StrokeMode,
    val points: List<NormalizedPoint>,
    val radius: Float,
)

/** Metadata of one exported file, kept so the library can show recent exports. */
@Serializable
data class ExportRecord(
    val fileName: String,
    val createdAtMs: Long,
    val sizeBytes: Long,
    val frameCount: Int,
    val durationMs: Long,
)

/** The complete editable state of one sticker. Persisted as JSON by the data layer. */
@Serializable
data class StickerProject(
    val id: String,
    val title: String,
    val source: SourceVideo,
    val trim: TrimRange,
    val transform: Transform = Transform(),
    val caption: Caption = Caption(),
    val background: BackgroundMode = BackgroundMode.ORIGINAL,
    val maskStrokes: List<MaskStroke> = emptyList(),
    val exports: List<ExportRecord> = emptyList(),
    val createdAtMs: Long,
    val updatedAtMs: Long,
)
