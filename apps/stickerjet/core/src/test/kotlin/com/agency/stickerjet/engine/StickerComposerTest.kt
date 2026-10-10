package com.agency.stickerjet.engine

import com.agency.stickerjet.domain.AlphaMask
import com.agency.stickerjet.domain.ArgbFrame
import com.agency.stickerjet.domain.Transform
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StickerComposerTest {

    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    private fun frame(w: Int, h: Int, pixel: (Int, Int) -> Int) =
        ArgbFrame(w, h, IntArray(w * h) { i -> pixel(i % w, i / w) }, timestampMs = 0)

    private fun compose(source: ArgbFrame, transform: Transform, canvas: Int, alpha: AlphaMask? = null): IntArray {
        val out = IntArray(canvas * canvas)
        StickerComposer.compose(source, alpha, transform, canvas, out)
        return out
    }

    private fun IntArray.at(x: Int, y: Int, canvas: Int): Int = this[y * canvas + x]

    private fun alphaOf(argb: Int): Int = argb ushr 24

    @Test
    fun identityPlacementCopiesAnOpaqueSquareExactly() {
        val source = frame(8, 8) { x, y -> (0xFF shl 24) or (x * 31 shl 16) or (y * 17 shl 8) or (x + y) }
        val out = compose(source, Transform(), canvas = 8)
        assertContentEquals(source.pixels, out)
    }

    @Test
    fun identityWithFullMaskStillCopiesExactly() {
        val source = frame(8, 8) { x, y -> (0xFF shl 24) or (x * 20 shl 16) or (y * 20) }
        val mask = AlphaMask(8, 8, ByteArray(64) { (-1).toByte() })
        assertContentEquals(source.pixels, compose(source, Transform(), canvas = 8, alpha = mask))
    }

    @Test
    fun wideSourceIsLetterboxedTopAndBottom() {
        val source = frame(4, 2) { _, _ -> red }
        val out = compose(source, Transform(), canvas = 8)
        assertEquals(0, alphaOf(out.at(3, 1, 8)), "row 1 is above the fitted image")
        assertEquals(255, alphaOf(out.at(3, 2, 8)))
        assertEquals(255, alphaOf(out.at(3, 5, 8)))
        assertEquals(0, alphaOf(out.at(3, 6, 8)), "row 6 is below the fitted image")
    }

    @Test
    fun quarterTurnRotatesClockwise() {
        // Source 4x2: top-left red, top-right green, bottom-left blue, bottom-right white.
        val colours = intArrayOf(red, 0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(),
            blue, 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt())
        val source = ArgbFrame(4, 2, colours, timestampMs = 0)
        val out = compose(source, Transform(quarterTurns = 1), canvas = 8)
        assertEquals(red, out.at(5, 0, 8), "source top-left goes to the top-right")
        assertEquals(blue, out.at(2, 0, 8), "source bottom-left goes to the top-left")
        assertEquals(0xFFFFFFFF.toInt(), out.at(2, 7, 8), "source bottom-right goes to the bottom-left")
        assertEquals(0xFF00FF00.toInt(), out.at(5, 7, 8), "source top-right goes to the bottom-right")
    }

    @Test
    fun halfTurnFlipsBothAxes() {
        val colours = IntArray(8) { if (it == 0) red else if (it == 7) blue else 0xFF808080.toInt() }
        val source = ArgbFrame(4, 2, colours, timestampMs = 0)
        val out = compose(source, Transform(quarterTurns = 2), canvas = 8)
        assertEquals(red, out.at(7, 5, 8), "source top-left goes to the bottom-right")
        assertEquals(blue, out.at(0, 2, 8), "source bottom-right goes to the top-left")
    }

    @Test
    fun quarterTurnsAreNormalizedModuloFour() {
        val source = frame(4, 2) { x, y -> (0xFF shl 24) or (x * 60 shl 16) or (y * 100) }
        assertContentEquals(
            compose(source, Transform(quarterTurns = 3), 8),
            compose(source, Transform(quarterTurns = -1), 8),
            "-1 equals 3",
        )
        assertContentEquals(
            compose(source, Transform(quarterTurns = 1), 8),
            compose(source, Transform(quarterTurns = 5), 8),
            "5 equals 1",
        )
    }

    @Test
    fun halfScaleShrinksAroundTheCentre() {
        val source = frame(8, 8) { _, _ -> red }
        val out = compose(source, Transform(scale = 0.5f), canvas = 8)
        assertEquals(0, alphaOf(out.at(0, 0, 8)))
        assertEquals(0, alphaOf(out.at(1, 1, 8)))
        assertEquals(255, alphaOf(out.at(2, 2, 8)))
        assertEquals(255, alphaOf(out.at(5, 5, 8)))
        assertEquals(0, alphaOf(out.at(6, 6, 8)))
    }

    @Test
    fun doubleScaleClipsInsteadOfWrapping() {
        val source = frame(8, 8) { _, _ -> red }
        val out = compose(source, Transform(scale = 2f), canvas = 8)
        assertEquals(255, alphaOf(out.at(0, 0, 8)), "corners are still covered by the enlarged source")
        assertEquals(255, alphaOf(out.at(7, 7, 8)))
    }

    @Test
    fun offsetIsNormalizedToCanvasSize() {
        val source = frame(8, 8) { _, _ -> red }
        val right = compose(source, Transform(offsetX = 0.25f), canvas = 8)
        assertEquals(0, alphaOf(right.at(1, 0, 8)), "shifted two pixels right, so column 1 is empty")
        assertEquals(255, alphaOf(right.at(2, 0, 8)))
        assertEquals(255, alphaOf(right.at(7, 0, 8)))

        val gone = compose(source, Transform(offsetX = -1f), canvas = 8)
        assertContentEquals(IntArray(64), gone, "an offset that moves the frame off canvas leaves it transparent")
    }

    @Test
    fun maskAlphaMultipliesSourceAlpha() {
        val source = frame(8, 8) { _, _ -> red }
        val half = AlphaMask(8, 8, ByteArray(64) { 128.toByte() })
        val out = compose(source, Transform(), canvas = 8, alpha = half)
        assertEquals(128, alphaOf(out.at(4, 4, 8)))
        assertEquals(0xFF0000, out.at(4, 4, 8) and 0xFFFFFF, "colour is unchanged by the mask")
    }

    @Test
    fun sourceAlphaMultipliesMaskAlpha() {
        val source = frame(8, 8) { _, _ -> (64 shl 24) or 0x00FF00 }
        val out = compose(source, Transform(), canvas = 8)
        assertEquals(64, alphaOf(out.at(3, 3, 8)))
    }

    @Test
    fun maskEdgeDoesNotBleedColourIntoTheKeptSide() {
        // Left column red and kept, right column blue and removed; canvas 4 doubles each pixel.
        val source = frame(2, 2) { x, _ -> if (x == 0) red else blue }
        val keep = AlphaMask(2, 2, byteArrayOf(-1, 0, -1, 0))
        val out = compose(source, Transform(), canvas = 4, alpha = keep)
        val edge = out.at(1, 1, 4)
        assertEquals(191, alphaOf(edge), "three quarters of the red neighbour survives")
        assertEquals(0xFF0000, edge and 0xFFFFFF, "no blue fringe from the removed neighbour")
    }

    @Test
    fun rejectsMismatchedBuffersAndBadScale() {
        val source = frame(4, 4) { _, _ -> red }
        assertFailsWith<IllegalArgumentException> { StickerComposer.compose(source, null, Transform(), 8, IntArray(10)) }
        assertFailsWith<IllegalArgumentException> {
            StickerComposer.compose(source, AlphaMask(2, 2, ByteArray(4)), Transform(), 8, IntArray(64))
        }
        assertFailsWith<IllegalArgumentException> { compose(source, Transform(scale = 0f), 8) }
    }
}
