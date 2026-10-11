package com.agency.photoduel.engine.pure

/**
 * Pure image geometry for the photo preparation pipeline: aspect-preserving fits, EXIF orientation
 * transforms and decode sampling. No Android types, so every rule is unit-tested on the JVM.
 */

/** A positive pixel size. */
data class PixelSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "Pixel size must be positive: ${width}x$height" }
    }

    val longEdge: Int get() = maxOf(width, height)
    val shortEdge: Int get() = minOf(width, height)
    val pixelCount: Long get() = width.toLong() * height.toLong()
}

/** A pixel coordinate, zero-based, x to the right and y downward. */
data class PixelPoint(val x: Int, val y: Int)

/**
 * How to turn the stored pixels of a photo into the upright image the camera meant.
 *
 * Order of application: first the optional mirror flips (about the centre of the stored image), then
 * a clockwise rotation by [rotationDegrees]. [rotationDegrees] is one of 0, 90, 180, 270.
 */
data class OrientationTransform(
    val rotationDegrees: Int,
    val flipHorizontal: Boolean,
    val flipVertical: Boolean,
) {
    init {
        require(rotationDegrees == 0 || rotationDegrees == 90 || rotationDegrees == 180 || rotationDegrees == 270) {
            "Rotation must be 0, 90, 180 or 270 but was $rotationDegrees"
        }
    }

    /** True for the quarter turns, which swap the width and the height of the image. */
    val swapsAxes: Boolean get() = rotationDegrees == 90 || rotationDegrees == 270

    val isIdentity: Boolean get() = rotationDegrees == 0 && !flipHorizontal && !flipVertical

    /** Size of the upright image for a stored image of the given size. */
    fun orientedSize(storedWidth: Int, storedHeight: Int): PixelSize =
        if (swapsAxes) PixelSize(storedHeight, storedWidth) else PixelSize(storedWidth, storedHeight)

    /**
     * Where the stored pixel ([x], [y]) ends up in the upright image. This is the reference
     * definition of the transform; the Android pipeline builds a [android.graphics.Matrix] that must
     * agree with it.
     */
    fun mapPixel(x: Int, y: Int, storedWidth: Int, storedHeight: Int): PixelPoint {
        require(x in 0 until storedWidth && y in 0 until storedHeight) { "Pixel outside the stored image" }
        val fx = if (flipHorizontal) storedWidth - 1 - x else x
        val fy = if (flipVertical) storedHeight - 1 - y else y
        return when (rotationDegrees) {
            0 -> PixelPoint(fx, fy)
            90 -> PixelPoint(storedHeight - 1 - fy, fx)
            180 -> PixelPoint(storedWidth - 1 - fx, storedHeight - 1 - fy)
            else -> PixelPoint(fy, storedWidth - 1 - fx)
        }
    }

    companion object {
        val IDENTITY = OrientationTransform(rotationDegrees = 0, flipHorizontal = false, flipVertical = false)
    }
}

/** The two output sizes of one prepared photo. [thumbnail] never exceeds [full] in either axis. */
data class PreparedSizes(val full: PixelSize, val thumbnail: PixelSize)

object ImageGeometry {
    /** Long edge limit of the full variant. Mirrors the server (docs/architecture.md section 9). */
    const val FULL_MAX_LONG_EDGE: Int = 1600

    /** Long edge limit of the thumbnail variant. */
    const val THUMBNAIL_MAX_LONG_EDGE: Int = 480

    /** EXIF Orientation tag values. Values outside 1..8 are treated as "normal". */
    const val ORIENTATION_NORMAL: Int = 1

    /** Default ceiling for a decoded bitmap, in pixels (12 MP, 48 MB as ARGB_8888). */
    const val DEFAULT_DECODE_PIXEL_BUDGET: Long = 12_000_000L

    /**
     * Fits [width] x [height] inside a square of [maxLongEdge], keeping the aspect ratio. Never
     * upscales: an image whose long edge is already at most [maxLongEdge] is returned unchanged.
     *
     * The long edge becomes exactly [maxLongEdge]. The short edge is rounded half up in integer
     * arithmetic, so it differs from the exact value by at most half a pixel, and is at least 1.
     */
    fun fit(width: Int, height: Int, maxLongEdge: Int): PixelSize {
        require(width > 0 && height > 0) { "Image size must be positive: ${width}x$height" }
        require(maxLongEdge > 0) { "maxLongEdge must be positive" }
        val longEdge = maxOf(width, height)
        if (longEdge <= maxLongEdge) return PixelSize(width, height)
        val shortEdge = minOf(width, height)
        val scaledShort = ((2L * shortEdge * maxLongEdge + longEdge) / (2L * longEdge)).toInt().coerceAtLeast(1)
        return if (width >= height) PixelSize(maxLongEdge, scaledShort) else PixelSize(scaledShort, maxLongEdge)
    }

    fun fitFull(width: Int, height: Int): PixelSize = fit(width, height, FULL_MAX_LONG_EDGE)

    fun fitThumbnail(width: Int, height: Int): PixelSize = fit(width, height, THUMBNAIL_MAX_LONG_EDGE)

    /** True when [exifOrientation] is one of the eight defined values. */
    fun isDefinedOrientation(exifOrientation: Int): Boolean = exifOrientation in 1..8

    /**
     * Maps an EXIF Orientation value to the transform that makes the stored pixels upright.
     * Value 1 and any undefined value (0, 9 and above, negatives) give the identity.
     *
     * | value | meaning (0th row, 0th column) | rotation | flips            |
     * |-------|-------------------------------|----------|------------------|
     * | 1     | top, left                     | 0        | none             |
     * | 2     | top, right                    | 0        | horizontal       |
     * | 3     | bottom, right                 | 180      | none             |
     * | 4     | bottom, left                  | 0        | vertical         |
     * | 5     | left, top                     | 270      | horizontal       |
     * | 6     | right, top                    | 90       | none             |
     * | 7     | right, bottom                 | 90       | horizontal       |
     * | 8     | left, bottom                  | 270      | none             |
     */
    fun transformFor(exifOrientation: Int): OrientationTransform = when (exifOrientation) {
        2 -> OrientationTransform(0, flipHorizontal = true, flipVertical = false)
        3 -> OrientationTransform(180, flipHorizontal = false, flipVertical = false)
        4 -> OrientationTransform(0, flipHorizontal = false, flipVertical = true)
        5 -> OrientationTransform(270, flipHorizontal = true, flipVertical = false)
        6 -> OrientationTransform(90, flipHorizontal = false, flipVertical = false)
        7 -> OrientationTransform(90, flipHorizontal = true, flipVertical = false)
        8 -> OrientationTransform(270, flipHorizontal = false, flipVertical = false)
        else -> OrientationTransform.IDENTITY
    }

    /** Size of the upright image: orientations 5 to 8 swap the width and the height. */
    fun orientedSize(storedWidth: Int, storedHeight: Int, exifOrientation: Int): PixelSize =
        transformFor(exifOrientation).orientedSize(storedWidth, storedHeight)

    /** Output sizes of both variants for a stored image of the given size and EXIF orientation. */
    fun outputSizes(storedWidth: Int, storedHeight: Int, exifOrientation: Int): PreparedSizes {
        val upright = orientedSize(storedWidth, storedHeight, exifOrientation)
        return PreparedSizes(
            full = fitFull(upright.width, upright.height),
            thumbnail = fitThumbnail(upright.width, upright.height),
        )
    }

    /**
     * Power-of-two `inSampleSize` for decoding a [width] x [height] image.
     *
     * The sample size is the largest power of two that still leaves a decoded long edge of at least
     * [targetLongEdge] (so the final downscale is under 2x and stays sharp), raised further, if
     * needed, until the decoded bitmap has at most [maxDecodedPixels] pixels. Memory wins over
     * sharpness when the two conflict. The result is at least 1.
     */
    fun chooseSampleSize(
        width: Int,
        height: Int,
        targetLongEdge: Int = FULL_MAX_LONG_EDGE,
        maxDecodedPixels: Long = DEFAULT_DECODE_PIXEL_BUDGET,
    ): Int {
        require(width > 0 && height > 0) { "Image size must be positive: ${width}x$height" }
        require(targetLongEdge > 0) { "targetLongEdge must be positive" }
        require(maxDecodedPixels > 0) { "maxDecodedPixels must be positive" }
        val longEdge = maxOf(width, height)
        var sample = 1
        while (sample < MAX_SAMPLE_SIZE && longEdge / (sample * 2) >= targetLongEdge) sample *= 2
        while (sample < MAX_SAMPLE_SIZE && decodedPixels(width, height, sample) > maxDecodedPixels) sample *= 2
        return sample
    }

    /** Pixel count of a decode with the given power-of-two sample size (decoders round up). */
    fun decodedPixels(width: Int, height: Int, sampleSize: Int): Long {
        require(sampleSize >= 1) { "sampleSize must be at least 1" }
        val w = (width.toLong() + sampleSize - 1) / sampleSize
        val h = (height.toLong() + sampleSize - 1) / sampleSize
        return w * h
    }

    /**
     * Sizes to pass through when shrinking from [from] to [to] so that no single step reduces an
     * axis by more than 2x. Plain bilinear scaling aliases badly beyond 2x, so large reductions are
     * done in halving steps. The list ends with [to] and is empty when the sizes are equal.
     */
    fun scaleSteps(from: PixelSize, to: PixelSize): List<PixelSize> {
        require(to.width <= from.width && to.height <= from.height) { "scaleSteps never upscales" }
        if (from == to) return emptyList()
        val steps = ArrayList<PixelSize>()
        var current = from
        while (current.width > 2 * to.width || current.height > 2 * to.height) {
            current = PixelSize(
                width = maxOf((current.width + 1) / 2, to.width),
                height = maxOf((current.height + 1) / 2, to.height),
            )
            steps += current
        }
        if (current != to) steps += to
        return steps
    }

    private const val MAX_SAMPLE_SIZE: Int = 1 shl 20
}
