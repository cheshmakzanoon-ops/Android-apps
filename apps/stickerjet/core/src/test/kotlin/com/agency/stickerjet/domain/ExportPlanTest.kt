package com.agency.stickerjet.domain

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExportPlanTest {

    @Test
    fun frameCountFollowsRoundedTwelveFps() {
        assertEquals(6, ExportPlan.frameCount(TrimRange(0, 500)))
        assertEquals(24, ExportPlan.frameCount(TrimRange(0, 2_000)))
        assertEquals(36, ExportPlan.frameCount(TrimRange(0, 3_000)))
    }

    @Test
    fun frameCountRoundsHalvesUpAndNeverGoesBelowOne() {
        assertEquals(5, ExportPlan.frameCount(TrimRange(0, 375))) // 4.5 frames, rounds up
        assertEquals(8, ExportPlan.frameCount(TrimRange(0, 625))) // 7.5 frames, rounds up
        assertEquals(11, ExportPlan.frameCount(TrimRange(0, 875))) // 10.5 frames, rounds up
        assertEquals(1, ExportPlan.frameCount(TrimRange(0, 1))) // 0.012 frames, clamped to one
    }

    @Test
    fun timestampsStartAtTheTrimStartAndUseFloorOfIndexTimesThousandOverFps() {
        assertEquals(
            listOf(1_000L, 1_083L, 1_166L, 1_250L, 1_333L, 1_416L, 1_500L, 1_583L),
            ExportPlan.frameTimestampsMs(TrimRange(1_000, 1_625)),
        )
    }

    @Test
    fun durationsFollowTheEightyThreeEightyFourPatternAtTwoSeconds() {
        val expected = IntArray(24) { if (it % 3 == 2) 84 else 83 }
        assertContentEquals(expected, ExportPlan.frameDurationsMs(TrimRange(0, 2_000)))
    }

    @Test
    fun durationsOfHalfASecondAddUpExactly() {
        assertContentEquals(intArrayOf(83, 83, 84, 83, 83, 84), ExportPlan.frameDurationsMs(TrimRange(0, 500)))
    }

    @Test
    fun durationsOfASingleFrameLastTheWholeLoop() {
        assertContentEquals(intArrayOf(1), ExportPlan.frameDurationsMs(TrimRange(7, 8)))
    }

    @Test
    fun durationsSumExactlyToTheLoopLengthForRandomTrims() {
        val random = Random(SEED)
        repeat(RUNS) {
            val length = random.nextLong(1L, 60_000L)
            val start = random.nextLong(0L, 1_000_000L)
            val trim = TrimRange(start, start + length)
            assertInvariants(trim)
        }
    }

    @Test
    fun durationsSumExactlyForEveryWholeMillisecondLengthInTheStickerRange() {
        for (length in StickerLimits.MIN_STICKER_MS..StickerLimits.MAX_STICKER_MS) {
            assertInvariants(TrimRange(0, length))
        }
    }

    @Test
    fun interiorDurationsAreEightyThreeOrEightyFourMilliseconds() {
        val random = Random(SEED + 1)
        repeat(RUNS) {
            val durations = ExportPlan.frameDurationsMs(TrimRange(0, random.nextLong(1L, 60_000L)))
            for (index in 0 until durations.size - 1) {
                assertTrue(durations[index] == 83 || durations[index] == 84, "interior duration ${durations[index]}")
            }
        }
    }

    @Test
    fun invalidTrimLengthIsRejected() {
        assertFailsWith<IllegalArgumentException> { ExportPlan.frameCount(TrimRange(5, 5)) }
        assertFailsWith<IllegalArgumentException> { ExportPlan.frameTimestampsMs(TrimRange(5, 1)) }
        assertFailsWith<IllegalArgumentException> { ExportPlan.frameDurationsMs(TrimRange(0, Int.MAX_VALUE.toLong() + 1)) }
    }

    /** Checks the invariants that the export pipeline relies on for one trim. */
    private fun assertInvariants(trim: TrimRange) {
        val timestamps = ExportPlan.frameTimestampsMs(trim)
        val durations = ExportPlan.frameDurationsMs(trim)
        val label = "trim $trim"

        assertEquals(timestamps.size, durations.size, label)
        assertEquals(ExportPlan.frameCount(trim), durations.size, label)
        assertEquals(trim.durationMs, durations.sumOf { it.toLong() }, "durations must sum to the loop length, $label")
        assertTrue(durations.all { it > 0 }, "every frame must last at least 1 ms, $label")
        assertEquals(trim.startMs, timestamps.first(), label)
        assertTrue(timestamps.last() < trim.endMs, "last frame must start inside the loop, $label")

        // Each timestamp is the trim start plus the sum of the durations before it.
        var elapsed = 0L
        timestamps.forEachIndexed { index, timestamp ->
            assertEquals(trim.startMs + elapsed, timestamp, "timestamp $index, $label")
            elapsed += durations[index]
        }
    }

    private companion object {
        const val SEED = 20_261_010
        const val RUNS = 2_000
    }
}
