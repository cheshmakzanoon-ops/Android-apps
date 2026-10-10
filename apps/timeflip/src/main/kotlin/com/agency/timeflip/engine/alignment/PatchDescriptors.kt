package com.agency.timeflip.engine.alignment

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.sqrt

/** Half-width of the square descriptor patch. A radius of 7 gives a 15×15 patch. */
internal const val PATCH_RADIUS = 7
private const val PATCH_SIDE = 2 * PATCH_RADIUS + 1
internal const val PATCH_LENGTH = PATCH_SIDE * PATCH_SIDE
private const val MIN_PATCH_STD = 1e-3

/**
 * Keypoints with zero-mean, unit-variance descriptors. Coordinates are continuous: the centre of
 * pixel index i is i + 0.5, so every point uses the same convention as the frame centre.
 */
internal class PatchSet(
    val count: Int,
    val xy: DoubleArray,
    val descriptors: FloatArray,
)

/** Mutual, ratio-tested correspondences. Entry k pairs FROM keypoint fromIndex[k] with TO keypoint toIndex[k]. */
internal class MatchSet(
    val count: Int,
    val fromIndex: IntArray,
    val toIndex: IntArray,
)

/** Builds normalised patches for [corners]. Flat patches with no texture are skipped. */
internal suspend fun buildPatches(smoothed: FloatArray, width: Int, corners: List<Corner>): PatchSet {
    val n = corners.size
    val xy = DoubleArray(n * 2)
    val descriptors = FloatArray(n * PATCH_LENGTH)
    val patch = FloatArray(PATCH_LENGTH)
    var count = 0
    for ((index, corner) in corners.withIndex()) {
        if (index % 64 == 0) currentCoroutineContext().ensureActive()
        var k = 0
        for (dy in -PATCH_RADIUS..PATCH_RADIUS) {
            val row = (corner.y + dy) * width
            for (dx in -PATCH_RADIUS..PATCH_RADIUS) {
                patch[k++] = smoothed[row + corner.x + dx]
            }
        }
        var mean = 0.0
        for (v in patch) mean += v
        mean /= PATCH_LENGTH
        var variance = 0.0
        for (v in patch) {
            val d = v - mean
            variance += d * d
        }
        val std = sqrt(variance / PATCH_LENGTH)
        if (std < MIN_PATCH_STD) continue
        val base = count * PATCH_LENGTH
        for (i in 0 until PATCH_LENGTH) {
            descriptors[base + i] = ((patch[i] - mean) / std).toFloat()
        }
        xy[count * 2] = corner.x + 0.5
        xy[count * 2 + 1] = corner.y + 0.5
        count++
    }
    return PatchSet(count, xy.copyOf(count * 2), descriptors.copyOf(count * PATCH_LENGTH))
}

/**
 * Mutual nearest-neighbour matching with a Lowe-style ratio test in both directions. A pair
 * survives only if each keypoint is the other's best match and both best matches beat their
 * second-best candidate by [ratio] on Euclidean distance.
 */
internal suspend fun mutualRatioMatches(from: PatchSet, to: PatchSet, ratio: Double): MatchSet {
    val nFrom = from.count
    val nTo = to.count
    val fromBest = FloatArray(nFrom) { Float.MAX_VALUE }
    val fromSecond = FloatArray(nFrom) { Float.MAX_VALUE }
    val fromBestIndex = IntArray(nFrom) { -1 }
    val toBest = FloatArray(nTo) { Float.MAX_VALUE }
    val toSecond = FloatArray(nTo) { Float.MAX_VALUE }
    val toBestIndex = IntArray(nTo) { -1 }

    for (i in 0 until nFrom) {
        if (i % 16 == 0) currentCoroutineContext().ensureActive()
        val a = i * PATCH_LENGTH
        for (j in 0 until nTo) {
            val b = j * PATCH_LENGTH
            var d = 0f
            for (k in 0 until PATCH_LENGTH) {
                val diff = from.descriptors[a + k] - to.descriptors[b + k]
                d += diff * diff
            }
            if (d < fromBest[i]) {
                fromSecond[i] = fromBest[i]
                fromBest[i] = d
                fromBestIndex[i] = j
            } else if (d < fromSecond[i]) {
                fromSecond[i] = d
            }
            if (d < toBest[j]) {
                toSecond[j] = toBest[j]
                toBest[j] = d
                toBestIndex[j] = i
            } else if (d < toSecond[j]) {
                toSecond[j] = d
            }
        }
    }

    val capacity = minOf(nFrom, nTo)
    val fromOut = IntArray(capacity)
    val toOut = IntArray(capacity)
    var count = 0
    for (i in 0 until nFrom) {
        val j = fromBestIndex[i]
        if (j < 0 || toBestIndex[j] != i) continue
        if (passesRatio(fromBest[i], fromSecond[i], ratio) && passesRatio(toBest[j], toSecond[j], ratio)) {
            fromOut[count] = i
            toOut[count] = j
            count++
        }
    }
    return MatchSet(count, fromOut.copyOf(count), toOut.copyOf(count))
}

internal fun MatchSet.toCorrespondences(from: PatchSet, to: PatchSet): Correspondences {
    val fromX = DoubleArray(count)
    val fromY = DoubleArray(count)
    val toX = DoubleArray(count)
    val toY = DoubleArray(count)
    for (k in 0 until count) {
        val i = fromIndex[k]
        val j = toIndex[k]
        fromX[k] = from.xy[2 * i]
        fromY[k] = from.xy[2 * i + 1]
        toX[k] = to.xy[2 * j]
        toY[k] = to.xy[2 * j + 1]
    }
    return Correspondences(count, fromX, fromY, toX, toY)
}

private fun passesRatio(best: Float, second: Float, ratio: Double): Boolean {
    // A single candidate cannot be ratio-tested, so it is treated as ambiguous.
    if (second == Float.MAX_VALUE) return false
    return sqrt(best.toDouble()) < ratio * sqrt(second.toDouble())
}
