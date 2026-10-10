package com.agency.timeflip.domain.image

/**
 * Row-major grayscale image in 0..1, produced by the engine for alignment. Platform-free.
 */
class GrayRaster(val width: Int, val height: Int, val pixels: FloatArray) {
    init {
        require(width > 0 && height > 0) { "raster must be non-empty" }
        require(pixels.size == width * height) { "pixel count ${pixels.size} does not match ${width}x$height" }
    }

    operator fun get(x: Int, y: Int): Float = pixels[y * width + x]
}
