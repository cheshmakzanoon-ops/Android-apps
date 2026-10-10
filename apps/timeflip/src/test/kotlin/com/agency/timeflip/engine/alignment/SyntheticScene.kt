package com.agency.timeflip.engine.alignment

import com.agency.timeflip.domain.image.GrayRaster
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedural test scene: boxes, discs and sharp strips painted in order over a grey background.
 * Evaluated analytically at any continuous coordinate, so warps are rendered without resampling.
 */
internal class SyntheticScene private constructor(private val shapes: List<Shape>) {

    private interface Shape {
        val value: Float
        fun contains(x: Double, y: Double): Boolean
    }

    private class Box(
        private val x0: Double,
        private val y0: Double,
        private val x1: Double,
        private val y1: Double,
        override val value: Float,
    ) : Shape {
        override fun contains(x: Double, y: Double): Boolean = x >= x0 && x < x1 && y >= y0 && y < y1
    }

    private class Disc(
        private val cx: Double,
        private val cy: Double,
        private val radius: Double,
        override val value: Float,
    ) : Shape {
        override fun contains(x: Double, y: Double): Boolean {
            val dx = x - cx
            val dy = y - cy
            return dx * dx + dy * dy <= radius * radius
        }
    }

    /** A straight strip with square ends, so it has four sharp corners. */
    private class Strip(
        private val ax: Double,
        private val ay: Double,
        private val ux: Double,
        private val uy: Double,
        private val length: Double,
        private val halfWidth: Double,
        override val value: Float,
    ) : Shape {
        override fun contains(x: Double, y: Double): Boolean {
            val dx = x - ax
            val dy = y - ay
            val along = dx * ux + dy * uy
            val across = -dx * uy + dy * ux
            return along >= 0.0 && along <= length && abs(across) <= halfWidth
        }
    }

    fun valueAt(x: Double, y: Double): Float {
        var value = BACKGROUND
        for (shape in shapes) {
            if (shape.contains(x, y)) value = shape.value
        }
        return value
    }

    companion object {
        const val BACKGROUND = 0.5f

        /** Shapes span a region larger than the frame, so small warps still see content everywhere. */
        fun generate(seed: Long, width: Int, height: Int): SyntheticScene {
            val random = Random(seed)
            val shapes = ArrayList<Shape>()
            val minX = -64.0
            val maxX = width + 64.0
            val minY = -64.0
            val maxY = height + 64.0

            fun uniform(low: Double, high: Double): Double = low + random.nextDouble() * (high - low)

            // Values sit well away from the background so every edge has real contrast.
            fun intensity(): Float {
                val v = random.nextFloat()
                return if (v < 0.5f) v * 0.5f else 0.75f + (v - 0.5f) * 0.5f
            }

            repeat(28) {
                val x0 = uniform(minX, maxX)
                val y0 = uniform(minY, maxY)
                shapes.add(Box(x0, y0, x0 + uniform(12.0, 70.0), y0 + uniform(12.0, 60.0), intensity()))
            }
            repeat(8) {
                shapes.add(Disc(uniform(minX, maxX), uniform(minY, maxY), uniform(8.0, 30.0), intensity()))
            }
            repeat(12) {
                val angle = uniform(0.0, 2.0 * Math.PI)
                shapes.add(
                    Strip(
                        ax = uniform(minX, maxX),
                        ay = uniform(minY, maxY),
                        ux = cos(angle),
                        uy = sin(angle),
                        length = uniform(30.0, 120.0),
                        halfWidth = uniform(1.5, 3.5),
                        value = intensity(),
                    ),
                )
            }
            return SyntheticScene(shapes)
        }
    }
}

/** Amplitude of the uniform sensor-like noise added to every rendered pixel. */
private const val NOISE_AMPLITUDE = 0.01f

/**
 * Renders the scene as seen through a similarity warp. The raster pixel p shows scene(z·p + w),
 * so the AFTER→BEFORE truth [warp] is exactly what a matcher should recover.
 */
internal fun renderWarped(
    scene: SyntheticScene,
    width: Int,
    height: Int,
    warp: Similarity,
    noiseSeed: Long,
): GrayRaster {
    val random = Random(noiseSeed)
    val pixels = FloatArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val px = x + 0.5
            val py = y + 0.5
            val value = scene.valueAt(warp.mapX(px, py), warp.mapY(px, py))
            val noise = (random.nextDouble() * 2.0 - 1.0).toFloat() * NOISE_AMPLITUDE
            pixels[y * width + x] = (value + noise).coerceIn(0f, 1f)
        }
    }
    return GrayRaster(width, height, pixels)
}

/**
 * Builds the AFTER→BEFORE similarity for the domain convention p' = R(θ)·s·(p − c) + c + t, where
 * the frame centre c is that of a [width]×[height] raster and t is given in raster pixels.
 */
internal fun truthSimilarity(
    scale: Double,
    rotationDegrees: Double,
    translateX: Double,
    translateY: Double,
    width: Int,
    height: Int,
): Similarity {
    val radians = Math.toRadians(rotationDegrees)
    val zr = scale * cos(radians)
    val zi = scale * sin(radians)
    val cx = width / 2.0
    val cy = height / 2.0
    return Similarity(
        zr = zr,
        zi = zi,
        wr = cx + translateX - (zr * cx - zi * cy),
        wi = cy + translateY - (zi * cx + zr * cy),
    )
}

internal val IDENTITY_WARP = Similarity(1.0, 0.0, 0.0, 0.0)
