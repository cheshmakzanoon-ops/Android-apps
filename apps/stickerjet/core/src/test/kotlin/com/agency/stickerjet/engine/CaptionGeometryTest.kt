package com.agency.stickerjet.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaptionGeometryTest {

    private val ascent = -40f
    private val descent = 10f

    private fun near(expected: Float, actual: Float, message: String) {
        assertTrue(abs(expected - actual) <= 1e-3f, "$message: expected $expected, was $actual")
    }

    @Test
    fun platesStayInsideTheCanvasForAnyTextWidthUpToTheMargins() {
        for (size in listOf(256, 512)) {
            val widths = listOf(0f, 1f, CaptionGeometry.maxTextWidth(size) / 2, CaptionGeometry.maxTextWidth(size))
            for (width in widths) {
                for (top in listOf(true, false)) {
                    val plate = CaptionGeometry.plate(size, width, ascent, descent, top)
                    assertTrue(plate.left >= 0f && plate.left < size, "left inside canvas")
                    assertTrue(plate.right > 0f && plate.right <= size, "right inside canvas")
                    assertTrue(plate.top >= 0f && plate.top < size, "top inside canvas")
                    assertTrue(plate.bottom > 0f && plate.bottom <= size, "bottom inside canvas")
                }
            }
        }
    }

    @Test
    fun longestTextLeavesHalfAMarginOnEachSide() {
        val size = 512
        val plate = CaptionGeometry.plate(size, CaptionGeometry.maxTextWidth(size), ascent, descent, top = true)
        val half = CaptionGeometry.margin(size) / 2
        assertTrue(plate.left >= half - 1e-3f, "left keeps half a margin")
        assertTrue(plate.right <= size - half + 1e-3f, "right keeps half a margin")
    }

    @Test
    fun plateIsCentredHorizontally() {
        val plate = CaptionGeometry.plate(512, 200f, ascent, descent, top = false)
        near(256f, (plate.left + plate.right) / 2, "centre")
    }

    @Test
    fun topPlateSitsOnTheTopMarginAndBottomPlateOnTheBottomMargin() {
        val size = 512
        val margin = CaptionGeometry.margin(size)
        val pad = margin / 2
        val top = CaptionGeometry.plate(size, 100f, ascent, descent, top = true)
        near(margin - pad, top.top, "top plate top")
        assertTrue(top.bottom < size / 2f, "top plate stays in the upper half")

        val bottom = CaptionGeometry.plate(size, 100f, ascent, descent, top = false)
        near(size - margin + pad, bottom.bottom, "bottom plate bottom")
        assertTrue(bottom.top > size / 2f, "bottom plate stays in the lower half")
    }

    @Test
    fun baselinesFollowTheRequestedPosition() {
        val size = 512
        val top = CaptionGeometry.baseline(size, ascent, descent, top = true)
        val bottom = CaptionGeometry.baseline(size, ascent, descent, top = false)
        near(CaptionGeometry.margin(size) - ascent, top, "top baseline")
        near(size - CaptionGeometry.margin(size) - descent, bottom, "bottom baseline")
        assertTrue(top < bottom, "top baseline is above the bottom baseline")
    }

    @Test
    fun lightFillGetsADarkPlateAndOutline() {
        val white = 0xFFFFFFFF.toInt()
        assertTrue(CaptionGeometry.isLightFill(white), "white is light")
        assertEquals(0x8C, (CaptionGeometry.plateArgb(white) ushr 24) and 0xFF)
        assertEquals(0x000000, CaptionGeometry.plateArgb(white) and 0xFFFFFF)
        assertEquals(0xFF000000.toInt(), CaptionGeometry.outlineArgb(white))
    }

    @Test
    fun darkFillGetsALightPlateAndOutline() {
        val black = 0xFF000000.toInt()
        assertTrue(!CaptionGeometry.isLightFill(black), "black is dark")
        assertEquals(0xFFFFFF, CaptionGeometry.plateArgb(black) and 0xFFFFFF)
        assertEquals(0xFFFFFFFF.toInt(), CaptionGeometry.outlineArgb(black))
    }

    @Test
    fun luminanceWeightsGreenMostAndIgnoresAlpha() {
        val green = CaptionGeometry.luminance(0xFF00FF00.toInt())
        val red = CaptionGeometry.luminance(0xFFFF0000.toInt())
        val blue = CaptionGeometry.luminance(0xFF0000FF.toInt())
        assertTrue(green > red && red > blue, "green > red > blue")
        near(CaptionGeometry.luminance(0xFFFFFFFF.toInt()), CaptionGeometry.luminance(0x00FFFFFF), "alpha ignored")
    }
}
