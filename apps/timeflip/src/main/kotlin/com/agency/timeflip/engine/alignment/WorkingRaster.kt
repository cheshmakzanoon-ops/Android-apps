package com.agency.timeflip.engine.alignment

import com.agency.timeflip.domain.image.GrayRaster
import com.agency.timeflip.domain.model.PixelSize
import kotlin.math.roundToInt

/** Pure helpers that prepare the working rasters used by [FeatureAligner]. No platform types. */
object WorkingRaster {

    /** Longest edge of the working raster. Small enough to keep alignment fast on a phone. */
    const val LONGEST_EDGE = 256

    /**
     * Working frame with [longestEdge] on its longest edge and the same aspect ratio as [frame].
     * Portrait frames get a narrow raster and landscape frames a flat one.
     */
    fun workingFrame(frame: PixelSize, longestEdge: Int = LONGEST_EDGE): PixelSize {
        require(longestEdge > 0) { "longestEdge must be positive" }
        return if (frame.width >= frame.height) {
            PixelSize(longestEdge, (longestEdge / frame.aspectRatio).roundToInt().coerceAtLeast(1))
        } else {
            PixelSize((longestEdge * frame.aspectRatio).roundToInt().coerceAtLeast(1), longestEdge)
        }
    }

    /** BT.601 luma in 0..1 from row-major packed ARGB_8888 pixels. Alpha is ignored. */
    fun grayFromArgb(argb: IntArray, width: Int, height: Int): GrayRaster {
        require(argb.size == width * height) { "pixel count does not match ${width}x$height" }
        val pixels = FloatArray(width * height)
        for (i in pixels.indices) {
            val p = argb[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            pixels[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }
        return GrayRaster(width, height, pixels)
    }
}
