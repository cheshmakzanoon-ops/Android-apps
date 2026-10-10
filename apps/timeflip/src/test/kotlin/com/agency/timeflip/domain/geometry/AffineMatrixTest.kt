package com.agency.timeflip.domain.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AffineMatrixTest {

    private fun assertPoint(expectedX: Double, expectedY: Double, actual: Point) {
        assertEquals(expectedX, actual.x, 1e-9)
        assertEquals(expectedY, actual.y, 1e-9)
    }

    @Test
    fun identityLeavesPointsUnchanged() {
        assertPoint(3.5, -2.0, AffineMatrix.IDENTITY.transformPoint(3.5, -2.0))
    }

    @Test
    fun thenAppliesFirstTransformBeforeSecond() {
        val scaleThenShift = AffineMatrix.scale(2.0, 2.0).then(AffineMatrix.translation(10.0, 0.0))
        assertPoint(12.0, 4.0, scaleThenShift.transformPoint(1.0, 2.0))

        val shiftThenScale = AffineMatrix.translation(10.0, 0.0).then(AffineMatrix.scale(2.0, 2.0))
        assertPoint(22.0, 4.0, shiftThenScale.transformPoint(1.0, 2.0))
    }

    @Test
    fun inverseRoundTripsPoints() {
        val rotateScale = AffineMatrix(a = 0.0, b = 2.0, c = -2.0, d = 0.0, e = 5.0, f = -3.0)
        val inverse = checkNotNull(rotateScale.inverseOrNull())
        val original = rotateScale.transformPoint(7.0, -4.0)
        assertPoint(7.0, -4.0, inverse.transformPoint(original.x, original.y))
    }

    @Test
    fun singularMatrixHasNoInverse() {
        assertNull(AffineMatrix(1.0, 2.0, 2.0, 4.0, 0.0, 0.0).inverseOrNull())
    }
}
