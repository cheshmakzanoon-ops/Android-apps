package com.agency.stickerjet.domain

import kotlinx.coroutines.flow.Flow

/**
 * Typed ports between the domain and the Android layers. The domain depends only on these
 * interfaces. Implementations live in the app module: data/ for storage, engine/ for media.
 * A change to a port is a contract change: update the fakes in the test sources and notify
 * every consumer first (project-outline-android-apps.md §3.3).
 */

/** Persistence of [StickerProject] documents. Implementations must write atomically. */
interface ProjectRepository {
    suspend fun list(): List<StickerProject>
    suspend fun load(id: String): StickerProject?
    suspend fun save(project: StickerProject)

    /** Removes the project document and every file it owns, including cached assets. */
    suspend fun delete(id: String)
}

/** One decoded frame as packed 0xAARRGGBB pixels, row-major, [width] * [height] entries. */
class ArgbFrame(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    val timestampMs: Long,
) {
    init {
        require(width > 0 && height > 0) { "Frame dimensions must be positive" }
        require(pixels.size == width * height) { "Pixel count does not match dimensions" }
    }
}

/**
 * Streams frames of a stored source clip at the requested timestamps, longest side ≤ [maxSidePx].
 *
 * [projectId] identifies the project whose source clip is read. The adapter maps the id to the stored
 * source file (for example `projects/<projectId>/source.mp4`), so callers never handle file names or URIs.
 */
interface FrameSource {
    fun frames(projectId: String, timestampsMs: List<Long>, maxSidePx: Int): Flow<ArgbFrame>
}

/** Per-pixel foreground confidence, 0..255, row-major, [width] * [height] entries. */
class AlphaMask(
    val width: Int,
    val height: Int,
    val alpha: ByteArray,
) {
    init {
        require(alpha.size == width * height) { "Mask size does not match dimensions" }
    }
}

/**
 * Person-focused foreground segmentation. Returns null when the frame contains no confident subject,
 * so callers can fall back to the original background instead of producing an empty sticker.
 */
interface SubjectSegmenter {
    suspend fun segment(frame: ArgbFrame): AlphaMask?
}
