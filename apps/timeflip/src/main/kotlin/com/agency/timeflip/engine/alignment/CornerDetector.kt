package com.agency.timeflip.engine.alignment

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Integer pixel position of a corner together with its Harris response. */
internal data class Corner(val x: Int, val y: Int, val response: Float)

private const val HARRIS_K = 0.04f
private const val WINDOW_RADIUS = 2
private const val WINDOW_SIDE = 2 * WINDOW_RADIUS + 1
private const val NMS_RADIUS = 3
private const val RELATIVE_THRESHOLD = 0.005f
private const val ABSOLUTE_THRESHOLD = 1e-7f

/** 3×3 binomial smoothing with clamped borders. Returns a new row-major array. */
internal suspend fun smooth3x3(source: FloatArray, width: Int, height: Int): FloatArray {
    val out = FloatArray(width * height)
    for (y in 0 until height) {
        if (y % 16 == 0) currentCoroutineContext().ensureActive()
        val up = max(y - 1, 0) * width
        val row = y * width
        val down = min(y + 1, height - 1) * width
        for (x in 0 until width) {
            val left = max(x - 1, 0)
            val right = min(x + 1, width - 1)
            out[row + x] = (
                source[up + left] + 2f * source[up + x] + source[up + right] +
                    2f * source[row + left] + 4f * source[row + x] + 2f * source[row + right] +
                    source[down + left] + 2f * source[down + x] + source[down + right]
                ) / 16f
        }
    }
    return out
}

/**
 * Harris corner detection on a smoothed raster. Responses are computed from Sobel gradients summed
 * over a 5×5 Gaussian window (sigma 1). Only strict local maxima within [NMS_RADIUS] survive, and
 * corners closer than [margin] to the raster edge are dropped so every descriptor patch fits.
 * The result is sorted by response (strongest first, then row, then column) and capped at [maxCount].
 */
internal suspend fun detectCorners(
    width: Int,
    height: Int,
    smoothed: FloatArray,
    maxCount: Int,
    margin: Int,
): List<Corner> {
    val size = width * height
    val ixx = FloatArray(size)
    val iyy = FloatArray(size)
    val ixy = FloatArray(size)
    for (y in 0 until height) {
        if (y % 16 == 0) currentCoroutineContext().ensureActive()
        val up = max(y - 1, 0) * width
        val row = y * width
        val down = min(y + 1, height - 1) * width
        for (x in 0 until width) {
            val left = max(x - 1, 0)
            val right = min(x + 1, width - 1)
            val gx = (
                smoothed[up + right] + 2f * smoothed[row + right] + smoothed[down + right] -
                    smoothed[up + left] - 2f * smoothed[row + left] - smoothed[down + left]
                ) / 8f
            val gy = (
                smoothed[down + left] + 2f * smoothed[down + x] + smoothed[down + right] -
                    smoothed[up + left] - 2f * smoothed[up + x] - smoothed[up + right]
                ) / 8f
            ixx[row + x] = gx * gx
            iyy[row + x] = gy * gy
            ixy[row + x] = gx * gy
        }
    }

    val weights = windowWeights()
    val response = FloatArray(size)
    var maxResponse = 0f
    for (y in 0 until height) {
        if (y % 16 == 0) currentCoroutineContext().ensureActive()
        for (x in 0 until width) {
            var sxx = 0f
            var syy = 0f
            var sxy = 0f
            for (dy in -WINDOW_RADIUS..WINDOW_RADIUS) {
                val rowOffset = clampIndex(y + dy, height - 1) * width
                for (dx in -WINDOW_RADIUS..WINDOW_RADIUS) {
                    val index = rowOffset + clampIndex(x + dx, width - 1)
                    val w = weights[(dy + WINDOW_RADIUS) * WINDOW_SIDE + dx + WINDOW_RADIUS]
                    sxx += w * ixx[index]
                    syy += w * iyy[index]
                    sxy += w * ixy[index]
                }
            }
            val trace = sxx + syy
            val r = sxx * syy - sxy * sxy - HARRIS_K * trace * trace
            response[y * width + x] = r
            if (r > maxResponse) maxResponse = r
        }
    }

    val threshold = max(maxResponse * RELATIVE_THRESHOLD, ABSOLUTE_THRESHOLD)
    val corners = ArrayList<Corner>()
    for (y in margin until height - margin) {
        if (y % 16 == 0) currentCoroutineContext().ensureActive()
        for (x in margin until width - margin) {
            val r = response[y * width + x]
            if (r > threshold && isStrictLocalMaximum(response, width, height, x, y, r)) {
                corners.add(Corner(x, y, r))
            }
        }
    }

    corners.sortWith(compareByDescending<Corner> { it.response }.thenBy { it.y }.thenBy { it.x })
    return if (corners.size > maxCount) corners.subList(0, maxCount).toList() else corners
}

private fun clampIndex(value: Int, last: Int): Int = value.coerceIn(0, last)

private fun windowWeights(): FloatArray = FloatArray(WINDOW_SIDE * WINDOW_SIDE) { index ->
    val dy = index / WINDOW_SIDE - WINDOW_RADIUS
    val dx = index % WINDOW_SIDE - WINDOW_RADIUS
    exp(-(dx * dx + dy * dy) / 2.0).toFloat()
}

/**
 * A pixel is kept only if no neighbour within [NMS_RADIUS] is stronger. Exact ties go to the
 * earliest pixel in raster order, so a flat plateau yields a single corner.
 */
private fun isStrictLocalMaximum(
    response: FloatArray,
    width: Int,
    height: Int,
    x: Int,
    y: Int,
    r: Float,
): Boolean {
    for (dy in -NMS_RADIUS..NMS_RADIUS) {
        val yy = y + dy
        if (yy < 0 || yy >= height) continue
        for (dx in -NMS_RADIUS..NMS_RADIUS) {
            if (dx == 0 && dy == 0) continue
            val xx = x + dx
            if (xx < 0 || xx >= width) continue
            val other = response[yy * width + xx]
            if (other > r) return false
            if (other == r && (yy < y || (yy == y && xx < x))) return false
        }
    }
    return true
}
