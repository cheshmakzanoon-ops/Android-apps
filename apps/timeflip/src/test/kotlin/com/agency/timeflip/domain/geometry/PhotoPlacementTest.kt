package com.agency.timeflip.domain.geometry

import com.agency.timeflip.domain.model.AlignmentTransform
import com.agency.timeflip.domain.model.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoPlacementTest {

    private val frame = PixelSize(1080, 1920)

    @Test
    fun coverBaseFillsFrameForWideSource() {
        val source = PixelSize(4000, 3000)
        val matrix = PhotoPlacement.coverBase(source, frame)
        // Height drives the scale for a wide photo in a tall frame.
        assertEquals(1920.0 / 3000.0, matrix.a, 1e-9)
        assertEquals(0.0, matrix.e + matrix.a * source.width / 2.0 - frame.width / 2.0, 1e-6)
    }

    @Test
    fun identityAlignmentOnCoverBaseHasFullCoverage() {
        val source = PixelSize(4000, 3000)
        assertEquals(1.0, PhotoPlacement.coverage(source, frame, AlignmentTransform.IDENTITY), 1e-9)
    }

    @Test
    fun largeOffsetExposesEmptyEdges() {
        val source = PixelSize(1200, 1600)
        val shifted = AlignmentTransform(offsetX = 0.5)
        val coverage = PhotoPlacement.coverage(source, frame, shifted)
        assertTrue("coverage should drop below full, was $coverage", coverage < 1.0)
        assertTrue(coverage > 0.0)
    }

    @Test
    fun shrinkingBelowCoverExposesEdges() {
        val source = PixelSize(1200, 1600)
        val shrunk = AlignmentTransform(scale = 0.5)
        assertTrue(PhotoPlacement.coverage(source, frame, shrunk) < 1.0)
    }

    @Test
    fun placementAppliesUserOffsetInFrameUnits() {
        val source = PixelSize(1080, 1920)
        val base = PhotoPlacement.placement(source, frame, AlignmentTransform.IDENTITY)
        val moved = PhotoPlacement.placement(source, frame, AlignmentTransform(offsetX = 0.1, offsetY = -0.05))
        val origin = base.transformPoint(0.0, 0.0)
        val movedOrigin = moved.transformPoint(0.0, 0.0)
        assertEquals(origin.x + 0.1 * frame.width, movedOrigin.x, 1e-6)
        assertEquals(origin.y - 0.05 * frame.height, movedOrigin.y, 1e-6)
    }
}
