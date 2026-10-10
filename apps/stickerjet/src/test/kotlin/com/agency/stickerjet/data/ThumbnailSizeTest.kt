package com.agency.stickerjet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ThumbnailSizeTest {

    @Test
    fun portraitClipScalesItsHeightToTheLimit() {
        assertEquals(180 to 320, ThumbnailSize.fit(1080, 1920, 320))
    }

    @Test
    fun landscapeClipScalesItsWidthToTheLimit() {
        assertEquals(320 to 240, ThumbnailSize.fit(4000, 3000, 320))
    }

    @Test
    fun smallClipIsNeverUpscaled() {
        assertEquals(100 to 50, ThumbnailSize.fit(100, 50, 320))
    }

    @Test
    fun extremeAspectRatioKeepsAtLeastOnePixel() {
        assertEquals(1 to 320, ThumbnailSize.fit(1, 5000, 320))
    }

    @Test
    fun emptySizesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ThumbnailSize.fit(0, 10, 320) }
    }
}
