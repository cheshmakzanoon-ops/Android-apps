package com.agency.timeflip.engine.alignment

import com.agency.timeflip.domain.image.GrayRaster
import com.agency.timeflip.domain.model.AlignmentEstimate
import com.agency.timeflip.domain.model.AlignmentStatus
import com.agency.timeflip.domain.model.AlignmentTransform
import com.agency.timeflip.domain.model.PixelSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class FeatureAlignerTest {

    private val width = 256
    private val height = 144
    private val frame = PixelSize(width, height)

    private fun scene(seed: Long, w: Int = width, h: Int = height) = SyntheticScene.generate(seed, w, h)

    /** Before shows the scene directly; after shows it through [truth] (AFTER→BEFORE). */
    private fun pair(
        truth: Similarity,
        sceneSeed: Long,
        w: Int = width,
        h: Int = height,
    ): Pair<GrayRaster, GrayRaster> {
        val s = scene(sceneSeed, w, h)
        return renderWarped(s, w, h, IDENTITY_WARP, 11L) to renderWarped(s, w, h, truth, 12L)
    }

    /** Checks the estimate maps AFTER points to where the truth maps them, on a grid of frame points. */
    private fun assertMatchesTruth(
        estimate: AlignmentEstimate,
        truth: Similarity,
        frameSize: PixelSize,
        tolerancePx: Double,
    ) {
        val matrix = estimate.transform.toMatrix(frameSize)
        for (gy in listOf(0.25, 0.5, 0.75)) {
            for (gx in listOf(0.25, 0.5, 0.75)) {
                val px = gx * frameSize.width
                val py = gy * frameSize.height
                val mapped = matrix.transformPoint(px, py)
                assertEquals("x at ($px, $py)", truth.mapX(px, py), mapped.x, tolerancePx)
                assertEquals("y at ($px, $py)", truth.mapY(px, py), mapped.y, tolerancePx)
            }
        }
    }

    @Test
    fun recoversPureTranslationWithinHalfPixel() {
        val truth = truthSimilarity(1.0, 0.0, 12.0, 0.0, width, height)
        val (before, after) = pair(truth, sceneSeed = 1001L)
        val estimate = runBlocking { FeatureAligner().estimate(before, after, frame) }

        assertEquals(AlignmentStatus.ACCEPTED, estimate.status)
        assertEquals(12.0, estimate.transform.offsetX * width, 0.5)
        assertEquals(0.0, estimate.transform.offsetY * height, 0.5)
        assertMatchesTruth(estimate, truth, frame, tolerancePx = 0.5)
    }

    @Test
    fun recoversScaleOnePointOneAboutTheCentre() {
        val truth = truthSimilarity(1.1, 0.0, 0.0, 0.0, width, height)
        val (before, after) = pair(truth, sceneSeed = 2002L)
        val estimate = runBlocking { FeatureAligner().estimate(before, after, frame) }

        assertEquals(AlignmentStatus.ACCEPTED, estimate.status)
        assertEquals(1.1, estimate.transform.scale, 0.02)
        assertMatchesTruth(estimate, truth, frame, tolerancePx = 1.0)
    }

    @Test
    fun recoversRotationOfThreeDegrees() {
        val truth = truthSimilarity(1.0, 3.0, 0.0, 0.0, width, height)
        val (before, after) = pair(truth, sceneSeed = 3003L)
        val estimate = runBlocking { FeatureAligner().estimate(before, after, frame) }

        assertEquals(AlignmentStatus.ACCEPTED, estimate.status)
        assertEquals(3.0, estimate.transform.rotationDegrees, 0.3)
        assertMatchesTruth(estimate, truth, frame, tolerancePx = 1.0)
    }

    @Test
    fun unrelatedImagesAreNeverAccepted() {
        val seeds = listOf(4001L to 4002L, 4003L to 4004L, 4005L to 4006L)
        for ((beforeSeed, afterSeed) in seeds) {
            val before = renderWarped(scene(beforeSeed), width, height, IDENTITY_WARP, 21L)
            val after = renderWarped(scene(afterSeed), width, height, IDENTITY_WARP, 22L)
            val estimate = runBlocking { FeatureAligner().estimate(before, after, frame) }

            assertNotEquals(AlignmentStatus.ACCEPTED, estimate.status)
            assertTrue(
                "status was ${estimate.status}",
                estimate.status == AlignmentStatus.NEEDS_MANUAL || estimate.status == AlignmentStatus.FAILED,
            )
            assertEquals(AlignmentTransform.IDENTITY, estimate.transform)
        }
    }

    @Test
    fun wideAndTallWorkingFramesShareCoordinates() {
        val wideTruth = truthSimilarity(1.0, 0.0, 7.0, -5.0, 256, 144)
        val (wideBefore, wideAfter) = pair(wideTruth, sceneSeed = 5001L, w = 256, h = 144)
        val wide = runBlocking { FeatureAligner().estimate(wideBefore, wideAfter, PixelSize(256, 144)) }
        assertEquals(AlignmentStatus.ACCEPTED, wide.status)
        assertEquals(7.0, wide.transform.offsetX * 256, 0.5)
        assertEquals(-5.0, wide.transform.offsetY * 144, 0.5)
        assertMatchesTruth(wide, wideTruth, PixelSize(256, 144), tolerancePx = 0.5)

        val tallTruth = truthSimilarity(1.0, 0.0, -5.0, 7.0, 144, 256)
        val (tallBefore, tallAfter) = pair(tallTruth, sceneSeed = 5002L, w = 144, h = 256)
        val tall = runBlocking { FeatureAligner().estimate(tallBefore, tallAfter, PixelSize(144, 256)) }
        assertEquals(AlignmentStatus.ACCEPTED, tall.status)
        assertEquals(-5.0, tall.transform.offsetX * 144, 0.5)
        assertEquals(7.0, tall.transform.offsetY * 256, 0.5)
        assertMatchesTruth(tall, tallTruth, PixelSize(144, 256), tolerancePx = 0.5)
    }

    @Test
    fun outputIsDeterministic() {
        val truth = truthSimilarity(1.05, 2.0, 6.0, -4.0, width, height)
        val (before, after) = pair(truth, sceneSeed = 6006L)
        val first = runBlocking { FeatureAligner().estimate(before, after, frame) }
        val second = runBlocking { FeatureAligner().estimate(before, after, frame) }
        val sameInstance = FeatureAligner()
        val third = runBlocking { sameInstance.estimate(before, after, frame) }
        val fourth = runBlocking { sameInstance.estimate(before, after, frame) }

        assertEquals(first, second)
        assertEquals(third, fourth)
        assertEquals(first, third)
    }

    @Test
    fun fractionsDoNotDependOnAbsoluteFrameSize() {
        val truth = truthSimilarity(1.0, 0.0, 9.0, 0.0, 144, 256)
        val (before, after) = pair(truth, sceneSeed = 7007L, w = 144, h = 256)
        val small = runBlocking { FeatureAligner().estimate(before, after, PixelSize(144, 256)) }
        val large = runBlocking { FeatureAligner().estimate(before, after, PixelSize(1080, 1920)) }
        assertEquals(small, large)
    }

    @Test
    fun flatRasterFailsWithoutInliers() {
        val flat = GrayRaster(width, height, FloatArray(width * height) { 0.5f })
        val estimate = runBlocking { FeatureAligner().estimate(flat, flat, frame) }
        assertEquals(AlignmentStatus.FAILED, estimate.status)
        assertEquals(AlignmentTransform.IDENTITY, estimate.transform)
    }

    @Test
    fun cancelledCallerIsNotAnswered() {
        val (before, after) = pair(IDENTITY_WARP, sceneSeed = 8008L)
        val cancelled = Job().also { it.cancel() }
        var threw = false
        try {
            runBlocking(cancelled) { FeatureAligner().estimate(before, after, frame) }
        } catch (expected: CancellationException) {
            threw = true
        }
        assertTrue("estimate must stop when its caller is cancelled", threw)
    }

    @Test
    fun alignmentTransformRoundTripsThroughSimilarityModel() {
        val cases = listOf(
            AlignmentTransform(1.0, 0.0, 0.0, 0.0),
            AlignmentTransform(1.1, 3.0, 0.02, -0.015),
            AlignmentTransform(0.7, -12.5, -0.3, 0.4),
            AlignmentTransform(2.5, 44.0, 0.9, -0.9),
        )
        for (frameSize in listOf(PixelSize(1080, 1920), PixelSize(256, 144))) {
            for (transform in cases) {
                val matrix = transform.toMatrix(frameSize)
                // Matrix [a c e; b d f] encodes z = a + i·b and w = e + i·f, with a = d and c = −b.
                assertEquals(matrix.a, matrix.d, 1e-12)
                assertEquals(-matrix.b, matrix.c, 1e-12)
                val model = Similarity(zr = matrix.a, zi = matrix.b, wr = matrix.e, wi = matrix.f)
                val back = requireNotNull(model.toAlignmentOrNull(frameSize.width, frameSize.height)) {
                    "round trip rejected $transform"
                }

                assertEquals(transform.scale, back.scale, 1e-9)
                assertEquals(transform.rotationDegrees, back.rotationDegrees, 1e-9)
                assertEquals(transform.offsetX, back.offsetX, 1e-9)
                assertEquals(transform.offsetY, back.offsetY, 1e-9)

                for (px in listOf(0.0, 100.0, frameSize.width.toDouble())) {
                    for (py in listOf(0.0, 50.0, frameSize.height.toDouble())) {
                        val expected = matrix.transformPoint(px, py)
                        assertEquals(expected.x, model.mapX(px, py), 1e-9)
                        assertEquals(expected.y, model.mapY(px, py), 1e-9)
                    }
                }
            }
        }
    }

    @Test
    fun outOfBoundsModelIsRejectedNotClamped() {
        val tooLarge = Similarity(zr = 4.5, zi = 0.0, wr = 0.0, wi = 0.0)
        assertNull(tooLarge.toAlignmentOrNull(width, height))
        val tooRotated = truthSimilarity(1.0, 60.0, 0.0, 0.0, width, height)
        assertNull(tooRotated.toAlignmentOrNull(width, height))
        // An offset of 1.2 frame widths exceeds the ±1.0 bound.
        val tooFar = truthSimilarity(1.0, 0.0, 1.2 * width, 0.0, width, height)
        assertNull(tooFar.toAlignmentOrNull(width, height))
        // Just inside the bounds is still accepted.
        val edge = truthSimilarity(1.0, 0.0, 0.5 * width, 0.0, width, height)
        assertEquals(0.5, edge.toAlignmentOrNull(width, height)!!.offsetX, 1e-9)
    }

    @Test
    fun implausibleScaleFromConsistentCorrespondencesIsNeedsManual() {
        val truth = Similarity(zr = 4.5, zi = 0.0, wr = -100.0, wi = -20.0)
        val correspondences = syntheticCorrespondences(truth, count = 40, outlierFraction = 0.0, seed = 9001L)
        val estimate = runBlocking {
            estimateFromCorrespondences(FeatureAligner.DEFAULT_SEED, correspondences, width, height, 40)
        }
        assertEquals(AlignmentStatus.NEEDS_MANUAL, estimate.status)
        assertEquals(AlignmentTransform.IDENTITY, estimate.transform)
        assertTrue(estimate.inlierCount >= 40)
    }

    @Test
    fun solverRecoversSimilarityThroughOutliers() {
        val truth = truthSimilarity(1.1, 2.0, 10.0, -6.0, width, height)
        val correspondences = syntheticCorrespondences(truth, count = 60, outlierFraction = 0.3, seed = 9002L)
        val estimate = runBlocking {
            estimateFromCorrespondences(FeatureAligner.DEFAULT_SEED, correspondences, width, height, 60)
        }
        assertEquals(AlignmentStatus.ACCEPTED, estimate.status)
        assertEquals(1.1, estimate.transform.scale, 0.01)
        assertTrue(abs(estimate.transform.rotationDegrees - 2.0) < 0.2)
        assertMatchesTruth(estimate, truth, frame, tolerancePx = 1.0)
    }

    @Test
    fun randomCorrespondencesFail() {
        val random = Random(9003L)
        val count = 6
        val correspondences = Correspondences(
            count = count,
            fromX = DoubleArray(count) { random.nextDouble() * width },
            fromY = DoubleArray(count) { random.nextDouble() * height },
            toX = DoubleArray(count) { random.nextDouble() * width },
            toY = DoubleArray(count) { random.nextDouble() * height },
        )
        val estimate = runBlocking {
            estimateFromCorrespondences(FeatureAligner.DEFAULT_SEED, correspondences, width, height, count)
        }
        assertEquals(AlignmentStatus.FAILED, estimate.status)
        assertTrue(estimate.inlierCount < 8)
    }

    /** Correspondences that follow [truth] exactly, with a fraction replaced by random outliers. */
    private fun syntheticCorrespondences(
        truth: Similarity,
        count: Int,
        outlierFraction: Double,
        seed: Long,
    ): Correspondences {
        val random = Random(seed)
        val fromX = DoubleArray(count)
        val fromY = DoubleArray(count)
        val toX = DoubleArray(count)
        val toY = DoubleArray(count)
        for (k in 0 until count) {
            val px = random.nextDouble() * width
            val py = random.nextDouble() * height
            fromX[k] = px
            fromY[k] = py
            if (random.nextDouble() < outlierFraction) {
                toX[k] = random.nextDouble() * width * 2
                toY[k] = random.nextDouble() * height * 2
            } else {
                toX[k] = truth.mapX(px, py)
                toY[k] = truth.mapY(px, py)
            }
        }
        return Correspondences(count, fromX, fromY, toX, toY)
    }
}
