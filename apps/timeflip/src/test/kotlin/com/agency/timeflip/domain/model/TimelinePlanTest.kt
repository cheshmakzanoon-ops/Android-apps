package com.agency.timeflip.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelinePlanTest {

    @Test
    fun defaultTimelineIsTheSumOfItsParts() {
        val plan = TimelinePlan(transitionMs = 1200)
        assertEquals(800 + 1200 + 800, plan.totalDurationMs)
        assertEquals(84, plan.frameCount) // 2.8 s × 30 fps
    }

    @Test
    fun frameCountRoundsUpToAWholeFrame() {
        // 1000 ms at 30 fps is exactly 30 frames; 1001 ms needs one extra frame.
        assertEquals(30, TimelinePlan(transitionMs = 1000, holdBeforeMs = 0, holdAfterMs = 0).frameCount)
        assertEquals(31, TimelinePlan(transitionMs = 1001, holdBeforeMs = 0, holdAfterMs = 0).frameCount)
    }

    @Test
    fun presentationTimestampsAreExactAndMonotonic() {
        val plan = TimelinePlan(transitionMs = 1000, holdBeforeMs = 0, holdAfterMs = 0, fps = 30)
        assertEquals(0L, plan.presentationTimeUs(0))
        assertEquals(33_333L, plan.presentationTimeUs(1))
        assertEquals(966_666L, plan.presentationTimeUs(29))
        var previous = -1L
        for (i in 0 until plan.frameCount) {
            val pts = plan.presentationTimeUs(i)
            assertTrue("timestamp $i must increase", pts > previous)
            previous = pts
        }
    }

    @Test
    fun presentationTimeRejectsOutOfRangeFrames() {
        val plan = TimelinePlan(transitionMs = 1000, holdBeforeMs = 0, holdAfterMs = 0)
        assertThrows(IllegalArgumentException::class.java) { plan.presentationTimeUs(plan.frameCount) }
    }

    @Test
    fun linearProgressIsZeroDuringLeadHoldAndOneAfterTransition() {
        val plan = TimelinePlan(transitionMs = 1000, holdBeforeMs = 800, holdAfterMs = 800, fps = 30)
        assertEquals(0.0, plan.linearProgress(0), 0.0)
        assertEquals(0.0, plan.linearProgress(23), 0.0) // 766 ms, still in the lead hold
        assertEquals(0.5, plan.linearProgress(39), 0.05) // 1300 ms, mid transition
        assertEquals(1.0, plan.linearProgress(plan.frameCount - 1), 0.0)
    }
}
