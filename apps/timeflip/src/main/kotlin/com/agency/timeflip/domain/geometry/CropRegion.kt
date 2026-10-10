package com.agency.timeflip.domain.geometry

import com.agency.timeflip.domain.model.PixelSize

/** Axis-aligned region in source-pixel coordinates. */
data class CropRegion(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) { "Crop must be finite" }
        require(right > left && bottom > top) { "Crop must have positive area" }
    }

    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val aspectRatio: Double get() = width / height

    companion object {
        fun full(size: PixelSize) = CropRegion(0.0, 0.0, size.width.toDouble(), size.height.toDouble())

        /** The largest centred region of [source] with the same aspect ratio as [frame]. */
        fun centredCover(source: PixelSize, frame: PixelSize): CropRegion {
            val sourceAspect = source.aspectRatio
            val frameAspect = frame.aspectRatio
            return if (sourceAspect > frameAspect) {
                val width = source.height * frameAspect
                val left = (source.width - width) / 2.0
                CropRegion(left, 0.0, left + width, source.height.toDouble())
            } else {
                val height = source.width / frameAspect
                val top = (source.height - height) / 2.0
                CropRegion(0.0, top, source.width.toDouble(), top + height)
            }
        }
    }
}
