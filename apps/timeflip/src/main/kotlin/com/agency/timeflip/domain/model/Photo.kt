package com.agency.timeflip.domain.model

import kotlin.math.roundToInt

enum class PhotoSlot { BEFORE, AFTER }

/** Pixel dimensions. [width] and [height] are always the dimensions after EXIF orientation. */
data class PixelSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "Pixel size must be positive, was ${width}x$height" }
    }

    val aspectRatio: Double get() = width.toDouble() / height.toDouble()

    val longestEdge: Int get() = maxOf(width, height)

    /** Returns this size scaled down so its longest edge is at most [maxEdge]; never upscales. */
    fun scaledToLongestEdge(maxEdge: Int): PixelSize {
        require(maxEdge > 0) { "maxEdge must be positive" }
        val longest = longestEdge
        if (longest <= maxEdge) return this
        val factor = maxEdge.toDouble() / longest
        return PixelSize(
            width = (width * factor).roundToInt().coerceAtLeast(1),
            height = (height * factor).roundToInt().coerceAtLeast(1),
        )
    }
}

/** A photograph imported into app-private storage. [size] is the oriented pixel size. */
data class SourcePhoto(
    val id: PhotoId,
    val slot: PhotoSlot,
    val size: PixelSize,
    val byteSize: Long,
    val importedAtEpochMs: Long,
) {
    init {
        require(byteSize >= 0) { "byteSize must not be negative" }
    }
}

/**
 * The before and after photographs of one transformation. The BEFORE photograph is the
 * reference frame; the AFTER photograph is placed by the project's AlignmentTransform.
 */
data class ImagePair(val before: SourcePhoto, val after: SourcePhoto) {
    init {
        require(before.slot == PhotoSlot.BEFORE) { "Before photograph has slot ${before.slot}" }
        require(after.slot == PhotoSlot.AFTER) { "After photograph has slot ${after.slot}" }
        require(before.id != after.id) { "Before and after must be different photographs" }
    }

    fun withReplaced(photo: SourcePhoto): ImagePair = when (photo.slot) {
        PhotoSlot.BEFORE -> copy(before = photo)
        PhotoSlot.AFTER -> copy(after = photo)
    }

    /**
     * Swaps the roles of the two photographs. The reference frame changes, so callers must reset
     * the alignment of the project.
     */
    fun swapped(): ImagePair = ImagePair(
        before = after.copy(slot = PhotoSlot.BEFORE),
        after = before.copy(slot = PhotoSlot.AFTER),
    )
}
