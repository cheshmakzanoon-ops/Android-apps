package com.agency.timeflip.engine.alignment

import com.agency.timeflip.domain.model.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkingRasterTest {

    @Test
    fun portraitPresetGetsNarrowRasterOfSameAspect() {
        assertEquals(PixelSize(144, 256), WorkingRaster.workingFrame(PixelSize(1080, 1920)))
        assertEquals(PixelSize(144, 256), WorkingRaster.workingFrame(PixelSize(720, 1280)))
    }

    @Test
    fun landscapeAndSquarePresetsGetMatchingRasters() {
        assertEquals(PixelSize(256, 144), WorkingRaster.workingFrame(PixelSize(1920, 1080)))
        assertEquals(PixelSize(256, 256), WorkingRaster.workingFrame(PixelSize(1080, 1080)))
    }

    @Test
    fun grayFromArgbUsesBt601Luma() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val pureRed = 0xFFFF0000.toInt()
        val gray = WorkingRaster.grayFromArgb(intArrayOf(white, black, pureRed), 3, 1)
        assertEquals(1.0f, gray[0, 0], 1e-3f)
        assertEquals(0.0f, gray[1, 0], 1e-6f)
        assertEquals(0.299f, gray[2, 0], 1e-3f)
    }
}
