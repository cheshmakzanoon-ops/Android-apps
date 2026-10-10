package com.agency.stickerjet.engine

import com.agency.stickerjet.domain.AlphaMask
import com.agency.stickerjet.domain.MaskStroke
import com.agency.stickerjet.domain.NormalizedPoint
import com.agency.stickerjet.domain.StrokeMode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MaskRefinerTest {

    private fun zeros(w: Int, h: Int) = AlphaMask(w, h, ByteArray(w * h))

    private fun AlphaMask.at(x: Int, y: Int): Int = alpha[y * width + x].toInt() and 0xFF

    private fun stroke(mode: StrokeMode, x: Float, y: Float, radius: Float) =
        MaskStroke(mode, listOf(NormalizedPoint(x, y)), radius)

    @Test
    fun emptyStrokesReturnTheBaseUnchanged() {
        val base = AlphaMask(16, 12, ByteArray(16 * 12) { (it * 37 % 256).toByte() })
        val once = MaskRefiner.refine(base, 16, 12, emptyList())
        val twice = MaskRefiner.refine(once, 16, 12, emptyList())
        assertContentEquals(base.alpha, once.alpha)
        assertContentEquals(once.alpha, twice.alpha, "empty strokes are idempotent")
    }

    @Test
    fun emptyStrokesWithNullBaseAreFullyOpaque() {
        val mask = MaskRefiner.refine(null, 5, 3, emptyList())
        assertTrue(mask.alpha.all { it == (-1).toByte() })
    }

    @Test
    fun eraseClearsAndRestoreFillsInsideTheStroke() {
        val erased = MaskRefiner.refine(null, 100, 100, listOf(stroke(StrokeMode.ERASE, 0.5f, 0.5f, 0.2f)))
        assertEquals(0, erased.at(50, 50), "centre is erased")
        assertEquals(255, erased.at(2, 2), "far corner keeps the opaque base")

        val restored = MaskRefiner.refine(zeros(100, 100), 100, 100, listOf(stroke(StrokeMode.RESTORE, 0.5f, 0.5f, 0.2f)))
        assertEquals(255, restored.at(50, 50), "centre is restored")
        assertEquals(0, restored.at(2, 2), "far corner keeps the empty base")
    }

    @Test
    fun strokesApplyInOrderSoTheLastOneWins() {
        val point = listOf(NormalizedPoint(0.5f, 0.5f))
        val restoreThenErase = MaskRefiner.refine(
            zeros(64, 64), 64, 64,
            listOf(MaskStroke(StrokeMode.RESTORE, point, 0.2f), MaskStroke(StrokeMode.ERASE, point, 0.2f)),
        )
        assertEquals(0, restoreThenErase.at(32, 32), "erase came last")

        val eraseThenRestore = MaskRefiner.refine(
            zeros(64, 64), 64, 64,
            listOf(MaskStroke(StrokeMode.ERASE, point, 0.2f), MaskStroke(StrokeMode.RESTORE, point, 0.2f)),
        )
        assertEquals(255, eraseThenRestore.at(32, 32), "restore came last")
    }

    @Test
    fun radiusIsNormalizedToTheShorterSide() {
        // 200 x 100 frame: radius 0.1 of the shorter side (100 px) is 10 px, not 20 px.
        val mask = MaskRefiner.refine(zeros(200, 100), 200, 100, listOf(stroke(StrokeMode.RESTORE, 0.5f, 0.5f, 0.1f)))
        assertEquals(255, mask.at(105, 50), "5 px from the centre is inside a 10 px radius")
        assertEquals(0, mask.at(120, 50), "20 px from the centre is outside a 10 px radius")
    }

    @Test
    fun strokeOutsideTheFrameIsClampedNotRejected() {
        val stroke = MaskStroke(
            StrokeMode.RESTORE,
            listOf(NormalizedPoint(-0.5f, 2f), NormalizedPoint(1.5f, -1f)),
            radius = 0.5f,
        )
        val mask = MaskRefiner.refine(zeros(40, 30), 40, 30, listOf(stroke))
        assertEquals(255, mask.at(20, 15), "the clamped diagonal crosses the middle")
        assertEquals(mask.width * mask.height, mask.alpha.size)
    }

    @Test
    fun negativeRadiusActsAsATap() {
        // 0.525 of 20 px is 10.5 px, the centre of pixel (10, 10); a zero radius still paints that pixel.
        val mask = MaskRefiner.refine(zeros(20, 20), 20, 20, listOf(stroke(StrokeMode.RESTORE, 0.525f, 0.525f, -3f)))
        assertEquals(255, mask.at(10, 10), "the pixel under the tap is painted")
        assertEquals(0, mask.at(0, 0))
    }

    @Test
    fun farPixelsKeepTheirBaseValueAndTheEraseDiscIsEmpty() {
        // Left half opaque, right half empty; erase a small disc inside the opaque half.
        val base = AlphaMask(80, 80, ByteArray(80 * 80) { if (it % 80 < 40) (-1).toByte() else 0 })
        val mask = MaskRefiner.refine(base, 80, 80, listOf(stroke(StrokeMode.ERASE, 0.25f, 0.5f, 0.05f)))
        assertEquals(255, mask.at(30, 40), "away from the stroke the opaque block is untouched")
        assertEquals(0, mask.at(70, 40), "the empty block stays empty")
        assertEquals(0, mask.at(20, 40), "erased disc interior")
    }

    @Test
    fun baseDimensionsMustMatch() {
        assertFailsWith<IllegalArgumentException> {
            MaskRefiner.refine(zeros(4, 4), 5, 4, listOf(stroke(StrokeMode.ERASE, 0.5f, 0.5f, 0.1f)))
        }
    }
}
