package com.agency.timeflip.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import com.agency.timeflip.domain.error.ErrorCategory
import com.agency.timeflip.domain.error.TimeFlipException
import com.agency.timeflip.domain.model.PixelSize
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Uses real native bitmap decoding so that sizes reflect actual JPEG files. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageNormalizerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun exifOrientationSixSwapsOrientedWidthAndHeight() {
        val input = writeJpeg("rotated.jpg", width = 40, height = 20, orientation = ExifInterface.ORIENTATION_ROTATE_90)
        val output = File(tmp.root, "out.jpg")

        val size = ImageNormalizer.normalize(input, output)

        assertEquals(PixelSize(width = 20, height = 40), size)
        assertEquals(PixelSize(width = 20, height = 40), boundsOf(output))
    }

    @Test
    fun everyExifOrientationReportsTheExpectedAxisSwap() {
        for (orientation in 1..8) {
            val input = writeJpeg("o$orientation.jpg", width = 40, height = 20, orientation = orientation)
            val output = File(tmp.root, "out-$orientation.jpg")
            val expected = if (ImageNormalizer.swapsAxes(orientation)) PixelSize(20, 40) else PixelSize(40, 20)

            assertEquals("orientation $orientation", expected, ImageNormalizer.normalize(input, output))
            assertEquals("orientation $orientation", expected, boundsOf(output))
        }
    }

    @Test
    fun outputHasNoOrientationTagLeftToApply() {
        val input = writeJpeg("tagged.jpg", width = 40, height = 20, orientation = ExifInterface.ORIENTATION_ROTATE_90)
        val output = File(tmp.root, "clean.jpg")

        ImageNormalizer.normalize(input, output)

        val tag = ExifInterface(output).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        assertTrue(tag != ExifInterface.ORIENTATION_ROTATE_90)
    }

    @Test
    fun longestEdgeIsScaledExactlyToTheRequestedLimit() {
        val input = writeJpeg("scaled.jpg", width = 40, height = 20, orientation = ExifInterface.ORIENTATION_ROTATE_90)
        val output = File(tmp.root, "scaled-out.jpg")

        val size = ImageNormalizer.normalize(input, output, maxEdge = 10)

        assertEquals(PixelSize(width = 5, height = 10), size)
        assertEquals(PixelSize(width = 5, height = 10), boundsOf(output))
    }

    @Test
    fun undecodableFileIsRejectedAsUnsupportedFormat() {
        val input = File(tmp.root, "not-an-image.tmp").apply { writeText("plain text, not a picture") }
        val output = File(tmp.root, "never.jpg")

        val error = assertThrows(TimeFlipException::class.java) { ImageNormalizer.normalize(input, output) }

        assertEquals(ErrorCategory.UNSUPPORTED_FORMAT, error.error.category)
        assertFalse(output.exists())
    }

    @Test
    fun sampleSizeKeepsDecodedEdgeWithinMemoryAndQualityBounds() {
        // Downscaling a 4096 px copy to 1000 px must not decode below 1000 px, or the result would upscale.
        assertEquals(4, ImageNormalizer.sampleSizeFor(longestEdge = 4096, maxEdge = 1000))
        // A 4032 px photo is already within the 4096 px limit, so no sampling is needed.
        assertEquals(1, ImageNormalizer.sampleSizeFor(longestEdge = 4032, maxEdge = 4096))
        // An 8160 px photo is sampled by 2 (4080 px) so the decoded bitmap stays within the 4096 px limit.
        assertEquals(2, ImageNormalizer.sampleSizeFor(longestEdge = 8160, maxEdge = 4096))
        // The smallest possible inputs must terminate.
        assertEquals(1, ImageNormalizer.sampleSizeFor(longestEdge = 1, maxEdge = 1))
    }

    private fun writeJpeg(name: String, width: Int, height: Int, orientation: Int): File {
        val file = File(tmp.root, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        val exif = ExifInterface(file)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        exif.saveAttributes()
        return file
    }

    private fun boundsOf(file: File): PixelSize {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return PixelSize(options.outWidth, options.outHeight)
    }
}
