package com.agency.timeflip.engine.alignment

import com.agency.timeflip.domain.image.GrayRaster
import com.agency.timeflip.domain.model.AlignmentEstimate
import com.agency.timeflip.domain.model.AlignmentStatus
import com.agency.timeflip.domain.model.AlignmentTransform
import com.agency.timeflip.domain.model.PixelSize
import com.agency.timeflip.domain.port.AlignmentEngine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val MAX_KEYPOINTS = 500
private const val KEYPOINT_MARGIN = PATCH_RADIUS + 1
private const val MATCH_RATIO = 0.8
private const val MIN_INLIERS = 8
private const val INLIER_FRACTION = 0.01
private const val MIN_INLIER_PX = 1.5
private const val MIN_SAMPLE_SEPARATION_FRACTION = 0.1
private const val ASPECT_TOLERANCE = 0.01

/**
 * Feature-based estimate of how the AFTER photograph sits over the BEFORE photograph.
 *
 * Pipeline: Harris corners (at most 500 per raster), 15×15 normalised patch descriptors, mutual
 * nearest-neighbour matching with a ratio test, then RANSAC over a similarity transform and a
 * least-squares refinement. Pure Kotlin: no Android types, so it runs on the JVM.
 *
 * The result is returned as ACCEPTED only when confidence reaches [AlignmentEstimate.ACCEPT_CONFIDENCE]
 * and the transform lies inside the domain bounds. Otherwise it returns NEEDS_MANUAL with
 * [AlignmentTransform.IDENTITY]. Fewer than 8 inliers gives FAILED. Implausible estimates are never
 * clamped into valid ones.
 *
 * Both rasters must have the same size and share the aspect ratio of [frame], because they are the
 * working frame coordinates. Results are deterministic for the same inputs and [seed].
 */
class FeatureAligner(private val seed: Long = DEFAULT_SEED) : AlignmentEngine {

    override suspend fun estimate(before: GrayRaster, after: GrayRaster, frame: PixelSize): AlignmentEstimate {
        require(before.width == after.width && before.height == after.height) {
            "BEFORE and AFTER rasters must share the working frame"
        }
        val width = before.width
        val height = before.height
        require(abs(width.toDouble() / height - frame.aspectRatio) <= ASPECT_TOLERANCE * frame.aspectRatio) {
            "working raster ${width}x$height does not match the frame aspect ratio"
        }

        currentCoroutineContext().ensureActive()
        val smoothedBefore = smooth3x3(before.pixels, width, height)
        val beforePatches = buildPatches(
            smoothedBefore,
            width,
            detectCorners(width, height, smoothedBefore, MAX_KEYPOINTS, KEYPOINT_MARGIN),
        )

        currentCoroutineContext().ensureActive()
        val smoothedAfter = smooth3x3(after.pixels, width, height)
        val afterPatches = buildPatches(
            smoothedAfter,
            width,
            detectCorners(width, height, smoothedAfter, MAX_KEYPOINTS, KEYPOINT_MARGIN),
        )

        currentCoroutineContext().ensureActive()
        val matches = mutualRatioMatches(afterPatches, beforePatches, MATCH_RATIO)
        if (matches.count < MIN_INLIERS) {
            return AlignmentEstimate(AlignmentTransform.IDENTITY, 0.0, 0, AlignmentStatus.FAILED)
        }
        val correspondences = matches.toCorrespondences(afterPatches, beforePatches)
        return estimateFromCorrespondences(seed, correspondences, width, height, matches.count)
    }

    companion object {
        /** Fixed RANSAC seed ("TFD4"), so identical inputs always give identical output. */
        const val DEFAULT_SEED: Long = 0x54464434L
    }
}

/**
 * Turns correspondences into a status-bearing estimate. Shared by [FeatureAligner.estimate] and the
 * tests, which feed known correspondences straight into the solver.
 *
 * @param putativeMatches number of correspondences before RANSAC (used for the inlier ratio).
 */
internal suspend fun estimateFromCorrespondences(
    seed: Long,
    correspondences: Correspondences,
    width: Int,
    height: Int,
    putativeMatches: Int,
): AlignmentEstimate {
    val longestEdge = max(width, height).toDouble()
    val threshold = max(MIN_INLIER_PX, INLIER_FRACTION * longestEdge)
    val minSeparation = MIN_SAMPLE_SEPARATION_FRACTION * longestEdge
    val outcome = SimilarityRansac(seed).run(correspondences, threshold, minSeparation)
    val inlierCount = outcome?.inliers?.size ?: 0
    if (outcome == null || inlierCount < MIN_INLIERS) {
        return AlignmentEstimate(AlignmentTransform.IDENTITY, 0.0, inlierCount, AlignmentStatus.FAILED)
    }

    val transform = outcome.model.toAlignmentOrNull(width, height)
        ?: return AlignmentEstimate(AlignmentTransform.IDENTITY, 0.0, inlierCount, AlignmentStatus.NEEDS_MANUAL)

    val confidence = confidenceOf(outcome.inliers, correspondences, width, height, putativeMatches)
    return if (confidence >= AlignmentEstimate.ACCEPT_CONFIDENCE) {
        AlignmentEstimate(transform, confidence, inlierCount, AlignmentStatus.ACCEPTED)
    } else {
        AlignmentEstimate(AlignmentTransform.IDENTITY, confidence, inlierCount, AlignmentStatus.NEEDS_MANUAL)
    }
}

/**
 * Confidence in 0..1. It is the geometric mean of an inlier-count score and an inlier-ratio score,
 * scaled by how widely the inliers spread over the raster. Both count and ratio must be high, so a
 * handful of lucky matches cannot be accepted.
 */
private fun confidenceOf(
    inliers: IntArray,
    c: Correspondences,
    width: Int,
    height: Int,
    putativeMatches: Int,
): Double {
    val n = inliers.size
    val countScore = ((n - 10) / 30.0).coerceIn(0.0, 1.0)
    val ratioScore = ((n.toDouble() / putativeMatches - 0.3) / 0.4).coerceIn(0.0, 1.0)

    var minX = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE
    var minY = Double.MAX_VALUE
    var maxY = -Double.MAX_VALUE
    for (k in inliers) {
        minX = min(minX, c.toX[k])
        maxX = max(maxX, c.toX[k])
        minY = min(minY, c.toY[k])
        maxY = max(maxY, c.toY[k])
    }
    val spread = ((maxX - minX) / width + (maxY - minY) / height) / 2.0
    val spreadScore = ((spread - 0.2) / 0.3).coerceIn(0.0, 1.0)

    val confidence = spreadScore * sqrt(countScore * ratioScore)
    return if (confidence.isFinite()) confidence.coerceIn(0.0, 1.0) else 0.0
}
