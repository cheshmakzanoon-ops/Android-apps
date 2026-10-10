package com.agency.timeflip.domain.model

import com.agency.timeflip.domain.geometry.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignmentTransformTest {

    private val frame = PixelSize(1000, 2000)

    private fun assertPoint(expectedX: Double, expectedY: Double, actual: Point) {
        assertEquals(expectedX, actual.x, 1e-6)
        assertEquals(expectedY, actual.y, 1e-6)
    }

    @Test
    fun identityMatrixMapsEveryPointToItself() {
        val matrix = AlignmentTransform.IDENTITY.toMatrix(frame)
        assertPoint(123.0, 456.0, matrix.transformPoint(123.0, 456.0))
    }

    @Test
    fun offsetsAreFractionsOfFrameSize() {
        // 0.1 × 1000 px = 100 px right; −0.25 × 2000 px = 500 px up.
        val matrix = AlignmentTransform(offsetX = 0.1, offsetY = -0.25).toMatrix(frame)
        assertPoint(100.0, -500.0, matrix.transformPoint(0.0, 0.0))
    }

    @Test
    fun scaleKeepsCentreFixedAndDoublesDistancesFromIt() {
        val matrix = AlignmentTransform(scale = 2.0).toMatrix(frame)
        assertPoint(500.0, 1000.0, matrix.transformPoint(500.0, 1000.0))
        // Top-left corner sits (−500, −1000) from the centre; doubling gives (−1000, −2000) from it.
        assertPoint(-500.0, -1000.0, matrix.transformPoint(0.0, 0.0))
    }

    @Test
    fun rotationPreservesDistanceFromCentre() {
        val square = PixelSize(100, 100)
        val matrix = AlignmentTransform(rotationDegrees = 30.0).toMatrix(square)
        val rotated = matrix.transformPoint(100.0, 50.0)
        // Start 50 px right of the centre (50, 50); a pure rotation keeps that 50 px radius.
        assertEquals(50.0, Math.hypot(rotated.x - 50.0, rotated.y - 50.0), 1e-6)
    }

    @Test
    fun quarterTurnDirectionIsClockwiseOnScreen() {
        // Rotation by +45° takes the point at 3 o'clock toward 4–5 o'clock (positive y is down on screen).
        val square = PixelSize(100, 100)
        val rotated = AlignmentTransform(rotationDegrees = 45.0).toMatrix(square).transformPoint(100.0, 50.0)
        assertTrue("expected y below centre, got ${rotated.y}", rotated.y > 50.0)
    }

    @Test
    fun outOfRangeValuesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { AlignmentTransform(scale = 0.1) }
        assertThrows(IllegalArgumentException::class.java) { AlignmentTransform(rotationDegrees = 90.0) }
        assertThrows(IllegalArgumentException::class.java) { AlignmentTransform(offsetX = 1.5) }
    }

    @Test
    fun clampedBringsGestureValuesIntoRange() {
        val clamped = AlignmentTransform.clamped(scale = 10.0, rotationDegrees = -180.0, offsetX = 4.0, offsetY = Double.NaN)
        assertEquals(AlignmentTransform.MAX_SCALE, clamped.scale, 0.0)
        assertEquals(-AlignmentTransform.MAX_ROTATION_DEGREES, clamped.rotationDegrees, 0.0)
        assertEquals(AlignmentTransform.MAX_OFFSET, clamped.offsetX, 0.0)
        assertEquals(0.0, clamped.offsetY, 0.0)
    }
}
