package com.agency.stickerjet.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StickerLimitsTest {

    @Test
    fun pinnedProductLimitsDoNotDriftSilently() {
        assertEquals(512, StickerLimits.CANVAS_PX)
        assertEquals(12, StickerLimits.FPS)
        assertEquals(500L, StickerLimits.MIN_STICKER_MS)
        assertEquals(3_000L, StickerLimits.MAX_STICKER_MS)
        assertEquals(500L * 1024L, StickerLimits.MAX_EXPORT_BYTES)
    }

    @Test
    fun defaultStickerLengthIsInsideAllowedRange() {
        assertTrue(StickerLimits.DEFAULT_STICKER_MS in StickerLimits.MIN_STICKER_MS..StickerLimits.MAX_STICKER_MS)
    }

    @Test
    fun trimRangeReportsDuration() {
        assertEquals(1_500L, TrimRange(startMs = 1_000L, endMs = 2_500L).durationMs)
    }
}
