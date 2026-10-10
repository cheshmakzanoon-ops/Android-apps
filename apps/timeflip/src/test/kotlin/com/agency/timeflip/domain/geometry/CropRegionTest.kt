package com.agency.timeflip.domain.geometry

import com.agency.timeflip.domain.model.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CropRegionTest {

    @Test
    fun centredCoverOfWideSourceKeepsFullHeightAndTargetAspect() {
        val crop = CropRegion.centredCover(PixelSize(4000, 3000), PixelSize(1080, 1920))
        assertEquals(3000.0, crop.height, 1e-9)
        assertEquals(1080.0 / 1920.0, crop.aspectRatio, 1e-9)
        assertEquals((4000 - crop.width) / 2.0, crop.left, 1e-9)
    }

    @Test
    fun centredCoverOfTallSourceKeepsFullWidth() {
        val crop = CropRegion.centredCover(PixelSize(1000, 3000), PixelSize(1000, 1000))
        assertEquals(1000.0, crop.width, 1e-9)
        assertEquals(1.0, crop.aspectRatio, 1e-9)
    }

    @Test
    fun invalidAreaIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { CropRegion(10.0, 0.0, 10.0, 5.0) }
    }
}
