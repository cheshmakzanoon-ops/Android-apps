package com.agency.stickerjet.engine

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device check of the platform encoder: Bitmap.compress output must be a static WebP that the pure
 * animation writer accepts, and the inspector must read it back. Runs on the device under test, so the
 * platform's WEBP_LOSSY path (API 30+) or WEBP path (API 26 to 29) is what gets exercised.
 */
@RunWith(AndroidJUnit4::class)
class FrameEncoderRoundTripTest {

    private fun frame(colour: Int, alpha: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(CANVAS, CANVAS, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(CANVAS * CANVAS) { i ->
            if (i % CANVAS < CANVAS / 2) (alpha shl 24) or colour else 0
        }
        bitmap.setPixels(pixels, 0, CANVAS, 0, 0, CANVAS, CANVAS)
        return bitmap
    }

    @Test
    fun platformEncoderOutputMuxesIntoAnAnimation() {
        val encoder = FrameEncoder()
        val bitmaps = listOf(frame(0xFF3366, 255), frame(0x2266FF, 200), frame(0x00FF00, 255))
        val webpFrames = try {
            bitmaps.map { WebpFrame(encoder.encode(it, quality = 80), durationMs = 100) }
        } finally {
            bitmaps.forEach { it.recycle() }
        }

        val file = AnimatedWebpWriter(CANVAS).write(webpFrames, loopCount = 0)
        val info = WebpInspector.inspect(file)
        assertEquals(CANVAS, info.canvasWidth)
        assertEquals(CANVAS, info.canvasHeight)
        assertEquals(3, info.frameCount)
        assertEquals(listOf(100, 100, 100), info.frameDurationsMs)
        assertTrue(info.isAnimated)
    }

    @Test
    fun singleEncodedFrameIsAStillImage() {
        val bitmap = frame(0xFF3366, 255)
        val bytes = try {
            FrameEncoder().encode(bitmap, quality = 85)
        } finally {
            bitmap.recycle()
        }
        val info = WebpInspector.inspect(AnimatedWebpWriter(CANVAS).write(listOf(WebpFrame(bytes, 100))))
        assertFalse(info.isAnimated)
        assertEquals(1, info.frameCount)
    }

    private companion object {
        const val CANVAS = 64
    }
}
