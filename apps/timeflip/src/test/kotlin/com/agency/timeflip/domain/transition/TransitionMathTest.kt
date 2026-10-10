package com.agency.timeflip.domain.transition

import com.agency.timeflip.domain.model.Easing
import com.agency.timeflip.domain.model.PixelSize
import com.agency.timeflip.domain.model.TransitionDefinition
import com.agency.timeflip.domain.model.TransitionKind
import com.agency.timeflip.domain.model.TransitionParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionMathTest {

    private val frame = PixelSize(1000, 2000)

    private fun linear(kind: TransitionKind, reversed: Boolean = false, angle: Double = 0.0) = TransitionDefinition(
        kind = kind,
        parameters = TransitionParameters(easing = Easing.LINEAR, reversed = reversed, wipeAngleDegrees = angle),
    )

    @Test
    fun easingsHitEndpointsAndStayInRange() {
        for (easing in Easing.entries) {
            assertEquals(0.0, Easings.apply(easing, 0.0), 1e-12)
            assertEquals(1.0, Easings.apply(easing, 1.0), 1e-12)
            var previous = 0.0
            for (step in 1..100) {
                val value = Easings.apply(easing, step / 100.0)
                assertTrue("$easing must be monotonic", value >= previous - 1e-12)
                assertTrue(value in 0.0..1.0)
                previous = value
            }
        }
    }

    @Test
    fun easeInOutCubicIsSymmetricAroundMidpoint() {
        assertEquals(0.5, Easings.apply(Easing.EASE_IN_OUT_CUBIC, 0.5), 1e-12)
        assertEquals(
            1.0 - Easings.apply(Easing.EASE_IN_OUT_CUBIC, 0.3),
            Easings.apply(Easing.EASE_IN_OUT_CUBIC, 0.7),
            1e-12,
        )
    }

    @Test
    fun horizontalRevealUncoversLeftToRight() {
        val mid = TransitionMath.frameAt(linear(TransitionKind.HORIZONTAL_REVEAL), 0.5, frame)
        assertEquals(1.0, mid.afterAlpha, 0.0)
        assertEquals(ClipShape.Rect(0.0, 0.0, 500.0, 2000.0), mid.afterClip)
    }

    @Test
    fun reversedHorizontalRevealUncoversRightToLeft() {
        val mid = TransitionMath.frameAt(linear(TransitionKind.HORIZONTAL_REVEAL, reversed = true), 0.25, frame)
        assertEquals(ClipShape.Rect(750.0, 0.0, 1000.0, 2000.0), mid.afterClip)
    }

    @Test
    fun verticalRevealUncoversTopToBottom() {
        val quarter = TransitionMath.frameAt(linear(TransitionKind.VERTICAL_REVEAL), 0.25, frame)
        assertEquals(ClipShape.Rect(0.0, 0.0, 1000.0, 500.0), quarter.afterClip)
    }

    @Test
    fun crossfadeAlphaFollowsProgressWithFullClip() {
        val frame40 = TransitionMath.frameAt(linear(TransitionKind.CROSSFADE), 0.4, frame)
        assertEquals(0.4, frame40.afterAlpha, 1e-12)
        assertEquals(ClipShape.Full, frame40.afterClip)
    }

    @Test
    fun wipeAtZeroDegreesSweepsLeftToRightAcrossCorners() {
        // Projections of the corners onto (1, 0) are 0 and 1000, so progress 0.5 places the edge at x = 500.
        val mid = TransitionMath.frameAt(linear(TransitionKind.DIRECTIONAL_WIPE, angle = 0.0), 0.5, frame)
        val clip = mid.afterClip as ClipShape.HalfPlane
        assertEquals(1.0, clip.normalX, 1e-9)
        assertEquals(0.0, clip.normalY, 1e-9)
        assertEquals(500.0, clip.offset, 1e-6)
    }

    @Test
    fun wipeNormalIsUnitLengthForAnyAngle() {
        for (angle in listOf(0.0, 30.0, 90.0, 137.0, -45.0)) {
            val clip = TransitionMath.frameAt(linear(TransitionKind.DIRECTIONAL_WIPE, angle = angle), 0.3, frame).afterClip as ClipShape.HalfPlane
            assertEquals(1.0, Math.hypot(clip.normalX, clip.normalY), 1e-9)
        }
    }

    @Test
    fun endpointsAreEmptyThenFull() {
        for (kind in TransitionKind.entries) {
            val start = TransitionMath.frameAt(linear(kind), 0.0, frame)
            val end = TransitionMath.frameAt(linear(kind), 1.0, frame)
            assertEquals(ClipShape.Empty, start.afterClip)
            assertEquals(0.0, start.afterAlpha, 0.0)
            assertEquals(ClipShape.Full, end.afterClip)
            assertEquals(1.0, end.afterAlpha, 0.0)
        }
    }
}
