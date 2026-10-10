package com.agency.stickerjet.engine

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class LoopTimingTest {

    @Test
    fun twoSecondsAtTwelveFpsIsTwentyFourFrames() {
        assertEquals(24, LoopTiming.frameCount(2_000L))
        assertEquals(6, LoopTiming.frameCount(500L))
        assertEquals(36, LoopTiming.frameCount(3_000L))
    }

    @Test
    fun partialFramesFloorButNeverDropBelowOne() {
        assertEquals(18, LoopTiming.frameCount(1_500L))
        assertEquals(1, LoopTiming.frameCount(10L))
    }

    @Test
    fun durationsAreEightyThreeOrEightyFourAndSumToTheLoop() {
        val durations = LoopTiming.durationsMs(24)
        assertEquals(2_000, durations.sum())
        assertEquals(setOf(83, 84), durations.toSet())
        assertContentEquals(intArrayOf(83, 83, 84, 83, 83, 84), durations.copyOfRange(0, 6))
    }

    @Test
    fun timestampsStartAtTrimStartAndFollowTheFrameGrid() {
        val stamps = LoopTiming.timestampsMs(startMs = 1_000L, frameCount = 4)
        assertEquals(listOf(1_000L, 1_083L, 1_166L, 1_250L), stamps)
    }
}
