package com.agency.stickerjet.engine

import com.agency.stickerjet.domain.ArgbFrame
import com.agency.stickerjet.domain.AlphaMask
import com.agency.stickerjet.domain.Transform
import kotlin.math.floor
import kotlin.math.min

/**
 * Places one source frame on the square sticker canvas and multiplies in the refined alpha.
 *
 * Geometry. Let `q = quarterTurns mod 4` (clockwise). The rotated source is `W x H` when q is even and
 * `H x W` when q is odd. It is fitted to the canvas (`fit = min(canvas/rotW, canvas/rotH)`), then scaled by
 * `transform.scale`. The centre sits at canvas centre plus `offset * canvasPx`. For each canvas pixel
 * centre, the inverse mapping gives rotated coordinates `(u, v)` and then source coordinates:
 *
 * | q | source x | source y |
 * |---|----------|----------|
 * | 0 | u        | v        |
 * | 1 | v        | H - u    |
 * | 2 | W - u    | H - v    |
 * | 3 | W - v    | u        |
 *
 * Sampling. Bilinear, premultiplied by the source alpha times the mask alpha. Each neighbour is weighted by
 * `bilinear weight * alpha`, so a neighbour the mask removes contributes no colour. This is what keeps the
 * edge of a removed background free of fringes. Pixels whose sample point falls outside `[0, W) x [0, H)` are
 * `0x00000000`. Output is non-premultiplied `0xAARRGGBB`.
 *
 * Identity placement of an opaque square frame onto a canvas of the same size copies the pixels exactly.
 */
object StickerComposer {

    /**
     * Fills `canvasPx * canvasPx` entries of [out] with the composed sticker. [alpha] null means fully
     * opaque. Its dimensions must match [source].
     */
    fun compose(source: ArgbFrame, alpha: AlphaMask?, transform: Transform, canvasPx: Int, out: IntArray) {
        require(canvasPx > 0) { "Canvas must be positive" }
        require(out.size == canvasPx * canvasPx) { "Output must hold ${canvasPx * canvasPx} pixels" }
        require(transform.scale > 0f && transform.scale.isFinite()) { "Scale must be a positive number" }
        require(transform.offsetX.isFinite() && transform.offsetY.isFinite()) { "Offsets must be finite" }
        if (alpha != null) {
            require(alpha.width == source.width && alpha.height == source.height) {
                "Alpha mask is ${alpha.width}x${alpha.height}, source is ${source.width}x${source.height}"
            }
        }
        val quarter = Math.floorMod(transform.quarterTurns, 4)
        val swapped = quarter % 2 == 1
        val rotW = if (swapped) source.height else source.width
        val rotH = if (swapped) source.width else source.height
        val fit = min(canvasPx / rotW.toFloat(), canvasPx / rotH.toFloat())
        val k = fit * transform.scale
        val cx = canvasPx / 2f + transform.offsetX * canvasPx
        val cy = canvasPx / 2f + transform.offsetY * canvasPx
        val sampler = Sampler(source, alpha)
        for (y in 0 until canvasPx) {
            val v = (y + 0.5f - cy) / k + rotH / 2f
            val row = y * canvasPx
            for (x in 0 until canvasPx) {
                val u = (x + 0.5f - cx) / k + rotW / 2f
                out[row + x] = when (quarter) {
                    0 -> sampler.sample(u, v)
                    1 -> sampler.sample(v, source.height - u)
                    2 -> sampler.sample(source.width - u, source.height - v)
                    else -> sampler.sample(source.width - v, u)
                }
            }
        }
    }

    /** Premultiplied bilinear sampler over one source frame and an optional mask. */
    private class Sampler(private val src: ArgbFrame, private val mask: AlphaMask?) {
        private val w = src.width
        private val h = src.height
        private var accA = 0f
        private var accR = 0f
        private var accG = 0f
        private var accB = 0f

        fun sample(sx: Float, sy: Float): Int {
            if (sx < 0f || sy < 0f || sx >= w || sy >= h) return 0
            val fx = sx - 0.5f
            val fy = sy - 0.5f
            val x0 = floor(fx).toInt()
            val y0 = floor(fy).toInt()
            val tx = fx - x0
            val ty = fy - y0
            accA = 0f
            accR = 0f
            accG = 0f
            accB = 0f
            val xa = x0.coerceIn(0, w - 1)
            val xb = (x0 + 1).coerceIn(0, w - 1)
            val ya = y0.coerceIn(0, h - 1)
            val yb = (y0 + 1).coerceIn(0, h - 1)
            accumulate(ya * w + xa, (1 - tx) * (1 - ty))
            accumulate(ya * w + xb, tx * (1 - ty))
            accumulate(yb * w + xa, (1 - tx) * ty)
            accumulate(yb * w + xb, tx * ty)
            if (accA <= 0f) return 0
            val alpha = (accA * 255f + 0.5f).toInt().coerceIn(0, 255)
            val r = (accR / accA + 0.5f).toInt().coerceIn(0, 255)
            val g = (accG / accA + 0.5f).toInt().coerceIn(0, 255)
            val b = (accB / accA + 0.5f).toInt().coerceIn(0, 255)
            return (alpha shl 24) or (r shl 16) or (g shl 8) or b
        }

        private fun accumulate(index: Int, weight: Float) {
            val p = src.pixels[index]
            val maskAlpha = if (mask == null) 1f else (mask.alpha[index].toInt() and 0xFF) / 255f
            val a = ((p ushr 24) / 255f) * maskAlpha * weight
            accA += a
            accR += a * ((p shr 16) and 0xFF)
            accG += a * ((p shr 8) and 0xFF)
            accB += a * (p and 0xFF)
        }
    }
}
