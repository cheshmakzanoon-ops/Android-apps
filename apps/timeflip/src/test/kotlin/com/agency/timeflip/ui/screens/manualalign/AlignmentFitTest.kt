package com.agency.timeflip.ui.screens.manualalign

import com.agency.timeflip.domain.geometry.PhotoPlacement
import com.agency.timeflip.domain.model.AlignmentTransform
import com.agency.timeflip.domain.model.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignmentFitTest {

    private val after = PixelSize(1200, 1600)
    private val frame = PixelSize(720, 1280)

    @Test
    fun fitZoomsInOnlyAsFarAsNeededAndKeepsRotationAndPosition() {
        val start = AlignmentTransform(scale = 1.0, rotationDegrees = 5.0, offsetX = 0.3, offsetY = -0.05)
        assertTrue(PhotoPlacement.coverage(after, frame, start) < 1.0)

        val fitted = AlignmentFit.fitToCover(start, after, frame)

        assertEquals(1.0, PhotoPlacement.coverage(after, frame, fitted), 1e-9)
        assertTrue(fitted.scale > start.scale)
        assertEquals(start.rotationDegrees, fitted.rotationDegrees, 1e-9)
        assertEquals(start.offsetX, fitted.offsetX, 1e-9)
        assertEquals(start.offsetY, fitted.offsetY, 1e-9)
    }

    @Test
    fun fitLeavesAlreadyCoveringTransformsUnchanged() {
        assertEquals(AlignmentTransform.IDENTITY, AlignmentFit.fitToCover(AlignmentTransform.IDENTITY, after, frame))
    }

    @Test
    fun fitReturnsTheInputWhenNoScaleCanCoverTheFrame() {
        val unreachable = AlignmentTransform(scale = 1.0, rotationDegrees = 45.0, offsetX = 1.0, offsetY = 1.0)

        assertEquals(unreachable, AlignmentFit.fitToCover(unreachable, after, frame))
    }
}
