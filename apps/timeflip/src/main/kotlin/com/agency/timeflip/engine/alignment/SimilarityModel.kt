package com.agency.timeflip.engine.alignment

import com.agency.timeflip.domain.model.AlignmentTransform
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.random.Random

/**
 * Similarity transform in complex form: q = z·p + w, with z = zr + i·zi and points written as x + i·y.
 * |z| is the scale and arg(z) the rotation. Positive angles turn x toward y, matching the screen
 * convention used by [AlignmentTransform.toMatrix].
 */
internal data class Similarity(val zr: Double, val zi: Double, val wr: Double, val wi: Double) {
    val scale: Double get() = hypot(zr, zi)

    val rotationDegrees: Double get() = Math.toDegrees(atan2(zi, zr))

    fun mapX(x: Double, y: Double): Double = zr * x - zi * y + wr

    fun mapY(x: Double, y: Double): Double = zi * x + zr * y + wi

    /**
     * Converts this AFTER→BEFORE mapping into the domain convention p' = R(θ)·s·(p − c) + c + t, where
     * c is the centre of a [width]×[height] raster and t = (offsetX·W, offsetY·H).
     *
     * Derivation: q = z·p + w equals z·(p − c) + c + t exactly when t = w + (z − 1)·c.
     *
     * Returns null when any component is outside the domain bounds. Out-of-bounds models are
     * rejected, never clamped into a valid transform.
     */
    fun toAlignmentOrNull(width: Int, height: Int): AlignmentTransform? {
        val cx = width / 2.0
        val cy = height / 2.0
        val tx = wr + (zr - 1.0) * cx - zi * cy
        val ty = wi + (zr - 1.0) * cy + zi * cx
        val s = scale
        val degrees = rotationDegrees
        val offsetX = tx / width
        val offsetY = ty / height
        if (!(s.isFinite() && degrees.isFinite() && offsetX.isFinite() && offsetY.isFinite())) return null
        if (s < AlignmentTransform.MIN_SCALE || s > AlignmentTransform.MAX_SCALE) return null
        if (abs(degrees) > AlignmentTransform.MAX_ROTATION_DEGREES) return null
        if (abs(offsetX) > AlignmentTransform.MAX_OFFSET || abs(offsetY) > AlignmentTransform.MAX_OFFSET) return null
        return AlignmentTransform(scale = s, rotationDegrees = degrees, offsetX = offsetX, offsetY = offsetY)
    }
}

/** Point correspondences in working-raster pixels: FROM is the AFTER photograph, TO the BEFORE one. */
internal class Correspondences(
    val count: Int,
    val fromX: DoubleArray,
    val fromY: DoubleArray,
    val toX: DoubleArray,
    val toY: DoubleArray,
)

/** Exact similarity through two correspondences: z = (q2 − q1) / (p2 − p1), then w = q1 − z·p1. */
internal fun fitTwo(c: Correspondences, i: Int, j: Int, minSeparation: Double): Similarity? {
    val dpx = c.fromX[j] - c.fromX[i]
    val dpy = c.fromY[j] - c.fromY[i]
    val denominator = dpx * dpx + dpy * dpy
    if (denominator < minSeparation * minSeparation) return null
    val dqx = c.toX[j] - c.toX[i]
    val dqy = c.toY[j] - c.toY[i]
    val zr = (dqx * dpx + dqy * dpy) / denominator
    val zi = (dqy * dpx - dqx * dpy) / denominator
    val p1x = c.fromX[i]
    val p1y = c.fromY[i]
    val wr = c.toX[i] - (zr * p1x - zi * p1y)
    val wi = c.toY[i] - (zr * p1y + zi * p1x)
    return Similarity(zr, zi, wr, wi)
}

/**
 * Least-squares similarity over the correspondences in [indices]. Centre both point sets, then
 * z = Σ conj(p̄)·q̄ / Σ |p̄|² (complex), and w = mean(q) − z·mean(p). Returns null when the points
 * have no spread to determine a scale.
 */
internal fun fitLeastSquares(c: Correspondences, indices: IntArray): Similarity? {
    val n = indices.size
    if (n < 2) return null
    var meanPx = 0.0
    var meanPy = 0.0
    var meanQx = 0.0
    var meanQy = 0.0
    for (k in indices) {
        meanPx += c.fromX[k]
        meanPy += c.fromY[k]
        meanQx += c.toX[k]
        meanQy += c.toY[k]
    }
    meanPx /= n
    meanPy /= n
    meanQx /= n
    meanQy /= n
    var spread = 0.0
    var re = 0.0
    var im = 0.0
    for (k in indices) {
        val px = c.fromX[k] - meanPx
        val py = c.fromY[k] - meanPy
        val qx = c.toX[k] - meanQx
        val qy = c.toY[k] - meanQy
        spread += px * px + py * py
        re += px * qx + py * qy
        im += px * qy - py * qx
    }
    if (spread < 1e-9) return null
    val zr = re / spread
    val zi = im / spread
    return Similarity(
        zr = zr,
        zi = zi,
        wr = meanQx - (zr * meanPx - zi * meanPy),
        wi = meanQy - (zr * meanPy + zi * meanPx),
    )
}

internal fun countInliers(model: Similarity, c: Correspondences, threshold: Double): Int {
    val limit = threshold * threshold
    var count = 0
    for (k in 0 until c.count) {
        val dx = model.mapX(c.fromX[k], c.fromY[k]) - c.toX[k]
        val dy = model.mapY(c.fromX[k], c.fromY[k]) - c.toY[k]
        if (dx * dx + dy * dy <= limit) count++
    }
    return count
}

internal fun collectInliers(model: Similarity, c: Correspondences, threshold: Double): IntArray {
    val limit = threshold * threshold
    val out = IntArray(c.count)
    var count = 0
    for (k in 0 until c.count) {
        val dx = model.mapX(c.fromX[k], c.fromY[k]) - c.toX[k]
        val dy = model.mapY(c.fromX[k], c.fromY[k]) - c.toY[k]
        if (dx * dx + dy * dy <= limit) {
            out[count] = k
            count++
        }
    }
    return out.copyOf(count)
}

/**
 * RANSAC over similarity transforms with a fixed seed. Minimal sample: two correspondences
 * that are at least [minSeparation] apart in the AFTER raster. The best consensus is refined by
 * least squares over its inliers, and the inlier set is recomputed under the refined model.
 *
 * Hypotheses are not bounded during sampling. Bounds are checked once on the final model, so
 * an implausible scale or rotation can never be hidden by a clamped sample.
 */
internal class SimilarityRansac(private val seed: Long) {

    internal class Outcome(val model: Similarity, val inliers: IntArray)

    suspend fun run(c: Correspondences, threshold: Double, minSeparation: Double): Outcome? {
        if (c.count < 2) return null
        val random = Random(seed)
        var best: Similarity? = null
        var bestCount = 0
        var iteration = 0
        while (iteration < MAX_ITERATIONS) {
            if (iteration % 128 == 0) currentCoroutineContext().ensureActive()
            iteration++
            val i = random.nextInt(c.count)
            var j = random.nextInt(c.count - 1)
            if (j >= i) j++
            val candidate = fitTwo(c, i, j, minSeparation) ?: continue
            val count = countInliers(candidate, c, threshold)
            if (count > bestCount) {
                best = candidate
                bestCount = count
            }
            if (bestCount >= 2 && iteration >= max(MIN_ITERATIONS, requiredIterations(bestCount, c.count))) break
        }

        var model = best ?: return null
        var inliers = collectInliers(model, c, threshold)
        for (pass in 0 until REFINE_PASSES) {
            currentCoroutineContext().ensureActive()
            val refined = fitLeastSquares(c, inliers) ?: break
            val refinedInliers = collectInliers(refined, c, threshold)
            val stable = refinedInliers.contentEquals(inliers)
            model = refined
            inliers = refinedInliers
            if (stable) break
        }
        return Outcome(model, inliers)
    }

    private fun requiredIterations(inlierCount: Int, total: Int): Int {
        val inlierRatio = inlierCount.toDouble() / total
        val allInlierSample = inlierRatio * inlierRatio
        if (allInlierSample >= 1.0) return 0
        return ceil(ln(1.0 - CONFIDENCE) / ln(1.0 - allInlierSample)).toInt()
    }

    private companion object {
        const val MAX_ITERATIONS = 2000
        const val MIN_ITERATIONS = 200
        const val CONFIDENCE = 0.999
        const val REFINE_PASSES = 4
    }
}
