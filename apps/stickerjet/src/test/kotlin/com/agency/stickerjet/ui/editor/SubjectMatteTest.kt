package com.agency.stickerjet.ui.editor

import com.agency.stickerjet.domain.AlphaMask
import com.agency.stickerjet.domain.ArgbFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SubjectMatteTest {

    private val opaqueRed = 0xFFFF0000.toInt()
    private val frame = ArgbFrame(width = 2, height = 1, pixels = intArrayOf(opaqueRed, opaqueRed), timestampMs = 0)

    @Test
    fun no_mask_keeps_every_pixel() {
        assertArrayEquals(frame.pixels, SubjectMatte.compose(frame, null))
    }

    @Test
    fun zero_alpha_makes_the_pixel_transparent_and_keeps_its_colour() {
        val mask = AlphaMask(2, 1, byteArrayOf(0, 0xFF.toByte()))
        val out = SubjectMatte.compose(frame, mask)
        assertEquals(0x00FF0000, out[0])
        assertEquals(opaqueRed, out[1])
    }

    @Test
    fun partial_alpha_scales_the_frame_alpha() {
        val mask = AlphaMask(2, 1, byteArrayOf(128.toByte(), 128.toByte()))
        val out = SubjectMatte.compose(frame, mask)
        assertEquals(128, (out[0] ushr 24) and 0xFF)
    }

    @Test
    fun mismatched_mask_size_is_rejected() {
        val mask = AlphaMask(1, 1, byteArrayOf(0xFF.toByte()))
        try {
            SubjectMatte.compose(frame, mask)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun brush_radii_are_normalized_and_ordered() {
        assertEquals(true, BrushSize.SMALL.radius < BrushSize.MEDIUM.radius)
        assertEquals(true, BrushSize.MEDIUM.radius < BrushSize.LARGE.radius)
    }
}
