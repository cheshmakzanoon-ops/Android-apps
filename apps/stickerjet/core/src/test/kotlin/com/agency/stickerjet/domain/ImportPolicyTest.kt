package com.agency.stickerjet.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class ImportPolicyTest {

    private val megabyte = 1024L * 1024L

    private fun probe(
        durationMs: Long? = 10_000L,
        width: Int? = 640,
        height: Int? = 480,
        rotationDegrees: Int = 0,
        hasVideoTrack: Boolean = true,
        sizeBytes: Long = 5L * megabyte,
    ) = ProbeResult(durationMs, width, height, rotationDegrees, hasVideoTrack, sizeBytes)

    private fun rejected(reason: ImportRejection) = ImportDecision.Rejected(reason)

    @Test
    fun typicalClipIsAccepted() {
        assertEquals(
            ImportDecision.Accepted(durationMs = 10_000L, orientedWidth = 640, orientedHeight = 480, rotationDegrees = 0),
            ImportPolicy.evaluate(probe()),
        )
    }

    @Test
    fun fileWithoutVideoTrackIsNotAVideo() {
        assertEquals(rejected(ImportRejection.NOT_A_VIDEO), ImportPolicy.evaluate(probe(hasVideoTrack = false)))
    }

    @Test
    fun notAVideoIsReportedBeforeAnyOtherProblem() {
        val audioOnly = probe(hasVideoTrack = false, durationMs = null, width = null, height = null, sizeBytes = 999L * megabyte)
        assertEquals(rejected(ImportRejection.NOT_A_VIDEO), ImportPolicy.evaluate(audioOnly))
    }

    @Test
    fun missingOrNonPositiveDurationIsUnreadable() {
        for (duration in listOf(null, 0L, -1L)) {
            assertEquals(rejected(ImportRejection.UNREADABLE), ImportPolicy.evaluate(probe(durationMs = duration)), "duration $duration")
        }
    }

    @Test
    fun missingOrNonPositiveWidthIsUnreadable() {
        for (width in listOf(null, 0, -640)) {
            assertEquals(rejected(ImportRejection.UNREADABLE), ImportPolicy.evaluate(probe(width = width)), "width $width")
        }
    }

    @Test
    fun missingOrNonPositiveHeightIsUnreadable() {
        for (height in listOf(null, 0, -480)) {
            assertEquals(rejected(ImportRejection.UNREADABLE), ImportPolicy.evaluate(probe(height = height)), "height $height")
        }
    }

    @Test
    fun unreadableIsReportedBeforeSizeAndLengthProblems() {
        val broken = probe(durationMs = null, width = 0, sizeBytes = 999L * megabyte)
        assertEquals(rejected(ImportRejection.UNREADABLE), ImportPolicy.evaluate(broken))
    }

    @Test
    fun durationOneMillisecondBelowTheMinimumIsTooShort() {
        assertEquals(rejected(ImportRejection.TOO_SHORT), ImportPolicy.evaluate(probe(durationMs = 499L)))
    }

    @Test
    fun durationOfExactlyTheMinimumIsAccepted() {
        assertEquals(
            ImportDecision.Accepted(durationMs = 500L, orientedWidth = 640, orientedHeight = 480, rotationDegrees = 0),
            ImportPolicy.evaluate(probe(durationMs = 500L)),
        )
    }

    @Test
    fun durationOfExactlyTheMaximumIsAccepted() {
        assertEquals(
            ImportDecision.Accepted(durationMs = 30_000L, orientedWidth = 640, orientedHeight = 480, rotationDegrees = 0),
            ImportPolicy.evaluate(probe(durationMs = 30_000L)),
        )
    }

    @Test
    fun durationOneMillisecondAboveTheMaximumIsTooLong() {
        assertEquals(rejected(ImportRejection.TOO_LONG), ImportPolicy.evaluate(probe(durationMs = 30_001L)))
    }

    @Test
    fun sizeOfExactly150MegabytesIsAccepted() {
        val limit = 150L * megabyte
        assertEquals(ImportDecision.Accepted(10_000L, 640, 480, 0), ImportPolicy.evaluate(probe(sizeBytes = limit)))
    }

    @Test
    fun sizeOneByteAbove150MegabytesIsTooLarge() {
        assertEquals(rejected(ImportRejection.TOO_LARGE), ImportPolicy.evaluate(probe(sizeBytes = 150L * megabyte + 1)))
    }

    @Test
    fun lengthChecksAreReportedBeforeSize() {
        assertEquals(rejected(ImportRejection.TOO_SHORT), ImportPolicy.evaluate(probe(durationMs = 100L, sizeBytes = 200L * megabyte)))
        assertEquals(rejected(ImportRejection.TOO_LONG), ImportPolicy.evaluate(probe(durationMs = 40_000L, sizeBytes = 200L * megabyte)))
    }

    @Test
    fun rotationsSwapOrientedSizeOnlyForQuarterAndThreeQuarterTurns() {
        val cases = listOf(
            0 to Pair(640, 480),
            90 to Pair(480, 640),
            180 to Pair(640, 480),
            270 to Pair(480, 640),
        )
        for ((rotation, oriented) in cases) {
            val decision = ImportPolicy.evaluate(probe(rotationDegrees = rotation))
            assertEquals(
                ImportDecision.Accepted(10_000L, oriented.first, oriented.second, rotation),
                decision,
                "rotation $rotation",
            )
        }
    }

    @Test
    fun negativeAndWrappedRotationsAreNormalizedBeforeSwapping() {
        assertEquals(ImportDecision.Accepted(10_000L, 480, 640, 270), ImportPolicy.evaluate(probe(rotationDegrees = -90)))
        assertEquals(ImportDecision.Accepted(10_000L, 480, 640, 90), ImportPolicy.evaluate(probe(rotationDegrees = 450)))
        assertEquals(ImportDecision.Accepted(10_000L, 640, 480, 0), ImportPolicy.evaluate(probe(rotationDegrees = 360)))
        assertEquals(ImportDecision.Accepted(10_000L, 640, 480, 180), ImportPolicy.evaluate(probe(rotationDegrees = -180)))
    }
}
