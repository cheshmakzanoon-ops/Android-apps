package com.agency.stickerjet.data

import com.agency.stickerjet.domain.ProbeResult
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The Android-specific reads of an imported file. Production uses MediaMetadataRetriever, and tests
 * use a fake, so the import flow can be tested on the JVM.
 */
internal interface MediaProbe {
    /**
     * Reads the container facts of [file], which is inside app-private storage. A damaged file is
     * reported with missing facts and no video track, not by throwing.
     */
    fun probe(file: File): ProbeResult

    /**
     * Writes a JPEG of the first frame of [source] to [target], scaled so the longest side is at most
     * [maxSidePx]. Returns false when no frame can be decoded.
     */
    fun writeThumbnail(source: File, target: File, maxSidePx: Int): Boolean
}

/** Size arithmetic for thumbnails. It has no Android types, so it runs on the JVM. */
internal object ThumbnailSize {
    const val MAX_SIDE_PX: Int = 320

    /** Width and height scaled to fit [maxSidePx] with the aspect ratio kept. Never upscales. */
    fun fit(width: Int, height: Int, maxSidePx: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && maxSidePx > 0) { "Sizes must be positive" }
        val longest = max(width, height)
        if (longest <= maxSidePx) return width to height
        val scale = maxSidePx.toDouble() / longest
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }
}
