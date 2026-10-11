package com.agency.photoduel.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.PreparedPhoto
import com.agency.photoduel.engine.android.AndroidPhotoPipeline
import com.agency.photoduel.engine.android.PhotoPipelineLimits
import com.agency.photoduel.engine.pure.ImageGeometry
import com.agency.photoduel.engine.pure.JpegMetadata
import com.agency.photoduel.engine.pure.JpegStripResult
import com.agency.photoduel.engine.pure.PhotoPreparationException
import com.agency.photoduel.engine.pure.PhotoPreparationFailure
import com.agency.photoduel.engine.pure.PixelSize
import com.agency.photoduel.engine.pure.UploadPolicy
import com.agency.photoduel.ui.ProcessingStage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real pipeline on an emulator or device. Photos are generated in code (see [TestImages]).
 * The pure rules behind it are covered by the JVM tests in src/test/.../engine/pure.
 */
@RunWith(AndroidJUnit4::class)
class AndroidPhotoPipelineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var inputDir: File
    private val pipelineWorkDir: File get() = File(context.cacheDir, "photoduel-prepare")

    @Before
    fun setUp() {
        inputDir = TestImages.cacheSubDirectory(context, "pipeline-test-input")
        inputDir.listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() {
        inputDir.deleteRecursively()
    }

    private fun prepare(
        file: File,
        pipeline: AndroidPhotoPipeline = AndroidPhotoPipeline(context),
        stages: MutableList<ProcessingStage> = CopyOnWriteArrayList(),
    ): PreparedPhoto = runBlocking { pipeline.prepare(TestImages.uriOf(file)) { stages += it } }

    private fun decode(bytes: ByteArray): Bitmap {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull("output must be a decodable image", bitmap)
        return bitmap!!
    }

    private fun assertPipelineWorkDirIsEmpty() {
        val leftovers = pipelineWorkDir.listFiles()?.toList().orEmpty()
        assertTrue("temporary files left behind: ${leftovers.size}", leftovers.isEmpty())
    }

    // ---- decoding a generated bitmap ---------------------------------------------------------------

    @Test
    fun preparesAFullAndAThumbnailFromAGeneratedPhoto() {
        val file = TestImages.writeJpeg(inputDir, "landscape.jpg", 3000, 2000)
        val stages = CopyOnWriteArrayList<ProcessingStage>()

        val photo = prepare(file, stages = stages)

        assertEquals(PixelSize(1600, 1067), PixelSize(photo.width, photo.height))
        val full = decode(photo.fullJpeg)
        val thumbnail = decode(photo.thumbnailJpeg)
        try {
            assertEquals(1600, full.width)
            assertEquals(1067, full.height)
            assertEquals(480, thumbnail.width)
            assertEquals(320, thumbnail.height)
        } finally {
            full.recycle()
            thumbnail.recycle()
        }
        // Both are JPEG by magic bytes and inside the budgets the server limits allow.
        assertEquals(com.agency.photoduel.engine.pure.ImageFormat.JPEG, UploadPolicy.detectFormat(photo.fullJpeg))
        assertEquals(com.agency.photoduel.engine.pure.ImageFormat.JPEG, UploadPolicy.detectFormat(photo.thumbnailJpeg))
        assertTrue("full size ${photo.fullJpeg.size}", photo.fullJpeg.size <= 1_572_864)
        assertTrue("thumbnail size ${photo.thumbnailJpeg.size}", photo.thumbnailJpeg.size <= 300 * 1024)
        assertEquals(
            listOf(ProcessingStage.READING, ProcessingStage.DECODING, ProcessingStage.ENCODING, ProcessingStage.DONE),
            stages.toList(),
        )
        assertPipelineWorkDirIsEmpty()
    }

    @Test
    fun portraitPhotoKeepsItsAspectRatio() {
        val file = TestImages.writeJpeg(inputDir, "portrait.jpg", 1500, 3000)
        val photo = prepare(file)
        assertEquals(PixelSize(800, 1600), PixelSize(photo.width, photo.height))
        val thumbnail = decode(photo.thumbnailJpeg)
        try {
            assertEquals(PixelSize(240, 480), PixelSize(thumbnail.width, thumbnail.height))
        } finally {
            thumbnail.recycle()
        }
    }

    @Test
    fun neverUpscalesASmallPhoto() {
        val file = TestImages.writeJpeg(inputDir, "small.jpg", 500, 300)
        val photo = prepare(file)
        assertEquals(PixelSize(500, 300), PixelSize(photo.width, photo.height))
        val full = decode(photo.fullJpeg)
        val thumbnail = decode(photo.thumbnailJpeg)
        try {
            assertEquals(PixelSize(500, 300), PixelSize(full.width, full.height))
            assertEquals(PixelSize(480, 288), PixelSize(thumbnail.width, thumbnail.height))
        } finally {
            full.recycle()
            thumbnail.recycle()
        }
    }

    @Test
    fun flattensTransparentPngPixelsOntoWhite() {
        val file = TestImages.writeHalfTransparentPng(inputDir, "transparent.png", 400, 300)
        val photo = prepare(file)
        val full = decode(photo.fullJpeg)
        try {
            val transparentSide = full.getPixel(full.width / 4, full.height / 2)
            val opaqueSide = full.getPixel(3 * full.width / 4, full.height / 2)
            assertTrue("transparent area must become white, was ${Integer.toHexString(transparentSide)}", TestImages.isClose(Color.WHITE, transparentSide))
            assertTrue("opaque area keeps its colour", TestImages.isClose(TestImages.RED, opaqueSide))
        } finally {
            full.recycle()
        }
    }

    // ---- orientation ---------------------------------------------------------------------------------

    @Test
    fun exifOrientationSixRotatesAQuarterTurnClockwise() {
        val file = TestImages.writeJpeg(inputDir, "rot6.jpg", 2400, 1200)
        TestImages.setOrientation(file, ExifInterface.ORIENTATION_ROTATE_90)

        val photo = prepare(file)

        // Upright image is 1200 x 2400, fitted to 800 x 1600. Stored top-left (red) is now top-right.
        assertEquals(PixelSize(800, 1600), PixelSize(photo.width, photo.height))
        val full = decode(photo.fullJpeg)
        try {
            assertCorner("top-left", TestImages.BLUE, full, 0.25f, 0.25f)
            assertCorner("top-right", TestImages.RED, full, 0.75f, 0.25f)
            assertCorner("bottom-left", TestImages.YELLOW, full, 0.25f, 0.75f)
            assertCorner("bottom-right", TestImages.GREEN, full, 0.75f, 0.75f)
        } finally {
            full.recycle()
        }
    }

    @Test
    fun allEightExifOrientationsMatchThePureReferenceTransform() {
        val storedW = 2000
        val storedH = 1000
        for (orientation in 1..8) {
            val file = TestImages.writeJpeg(inputDir, "o$orientation.jpg", storedW, storedH)
            TestImages.setOrientation(file, orientation)
            val photo = prepare(file)

            val transform = ImageGeometry.transformFor(orientation)
            val upright = transform.orientedSize(storedW, storedH)
            val expectedSize = ImageGeometry.fitFull(upright.width, upright.height)
            assertEquals("size for orientation $orientation", expectedSize, PixelSize(photo.width, photo.height))

            val full = decode(photo.fullJpeg)
            try {
                val quadrantColors = listOf(
                    Triple(0.25f, 0.25f, TestImages.RED),
                    Triple(0.75f, 0.25f, TestImages.GREEN),
                    Triple(0.25f, 0.75f, TestImages.BLUE),
                    Triple(0.75f, 0.75f, TestImages.YELLOW),
                )
                for ((fx, fy, color) in quadrantColors) {
                    val stored = transform.mapPixel((fx * storedW).toInt(), (fy * storedH).toInt(), storedW, storedH)
                    val x = (stored.x.toFloat() / upright.width * full.width).toInt().coerceIn(0, full.width - 1)
                    val y = (stored.y.toFloat() / upright.height * full.height).toInt().coerceIn(0, full.height - 1)
                    assertTrue(
                        "orientation $orientation: quadrant ($fx,$fy) should be ${Integer.toHexString(color)} but pixel was ${Integer.toHexString(full.getPixel(x, y))}",
                        TestImages.isClose(color, full.getPixel(x, y)),
                    )
                }
            } finally {
                full.recycle()
            }
        }
    }

    private fun assertCorner(label: String, expected: Int, bitmap: Bitmap, fx: Float, fy: Float) {
        val actual = bitmap.getPixel((bitmap.width * fx).toInt(), (bitmap.height * fy).toInt())
        assertTrue(
            "$label should be ${Integer.toHexString(expected)} but was ${Integer.toHexString(actual)}",
            TestImages.isClose(expected, actual),
        )
    }

    // ---- metadata ------------------------------------------------------------------------------------

    @Test
    fun outputsCarryNoExifNoGpsAndNoMetadataSegments() {
        val file = TestImages.writeJpeg(inputDir, "gps.jpg", 2400, 1600)
        TestImages.addIdentifyingExif(file, latitude = 52.5200, longitude = 13.4050, make = "SecretMakeName")

        // Precondition: the source really holds GPS, as seen by the platform library and by our scanner.
        assertNotNull("test setup: source must have GPS", ExifInterface(file.absolutePath).latLong)
        val source = JpegMetadata.strip(file.readBytes())
        assertTrue(source is JpegStripResult.Stripped)
        assertTrue("pure scanner must see the GPS in a real ExifInterface file", (source as JpegStripResult.Stripped).containedGps)

        val photo = prepare(file)

        for ((label, bytes) in listOf("full" to photo.fullJpeg, "thumbnail" to photo.thumbnailJpeg)) {
            val result = JpegMetadata.strip(bytes)
            assertTrue("$label must be a clean JPEG", result is JpegStripResult.Stripped)
            result as JpegStripResult.Stripped
            assertFalse("$label contains GPS", result.containedGps)
            assertTrue("$label still has metadata segments: ${result.removedSegments}", result.removedSegments.isEmpty())
            assertEquals("$label has trailing bytes", 0, result.trailingBytesDropped)
            assertArrayEquals("$label must already be identical to its stripped form", bytes, result.bytes)
            assertNull("$label: platform EXIF reader still finds a position", ExifInterface(ByteArrayInputStream(bytes)).latLong)
            assertFalse("$label contains the Exif marker", String(bytes, Charsets.ISO_8859_1).contains("Exif"))
            assertFalse("$label contains the camera make", String(bytes, Charsets.ISO_8859_1).contains("SecretMakeName"))
        }
    }

    // ---- rejection -------------------------------------------------------------------------------------

    private fun assertRejected(file: File, expected: PhotoPreparationFailure, pipeline: AndroidPhotoPipeline = AndroidPhotoPipeline(context)) {
        try {
            prepare(file, pipeline)
            fail("expected $expected")
        } catch (e: PhotoPreparationException) {
            assertEquals(expected, e.failure)
            assertEquals(expected.code, e.error.code)
            val message = e.message.orEmpty()
            assertFalse("message must not leak the file name", message.contains(file.name) || message.contains(file.parent.orEmpty()))
        }
        assertPipelineWorkDirIsEmpty()
    }

    @Test
    fun rejectsATextFileAsUnsupportedMedia() {
        val file = File(inputDir, "notes.jpg").apply { writeText("this is not an image, whatever the name says") }
        assertRejected(file, PhotoPreparationFailure.UNSUPPORTED_FORMAT)
        assertEquals(ErrorCode.UNSUPPORTED_MEDIA, PhotoPreparationFailure.UNSUPPORTED_FORMAT.code)
    }

    @Test
    fun rejectsAGifEvenThoughTheNameSaysJpeg() {
        val file = File(inputDir, "anim.jpg").apply {
            writeBytes("GIF89a".toByteArray() + ByteArray(64) { 0 })
        }
        assertRejected(file, PhotoPreparationFailure.UNSUPPORTED_FORMAT)
    }

    @Test
    fun rejectsAPhotoWhoseShortEdgeIsUnder200Pixels() {
        val file = TestImages.writeJpeg(inputDir, "thin.jpg", 1000, 150)
        assertRejected(file, PhotoPreparationFailure.TOO_SMALL)
    }

    @Test
    fun rejectsATruncatedJpegHeaderThatCannotBeDecoded() {
        val whole = TestImages.writeJpeg(inputDir, "whole.jpg", 1000, 800)
        val cut = File(inputDir, "cut.jpg").apply { writeBytes(whole.readBytes().copyOf(12)) }
        // Twelve bytes keep the JPEG signature but have no frame header: bounds cannot be read.
        assertRejected(cut, PhotoPreparationFailure.DECODE_FAILED)
    }

    @Test
    fun rejectsAMissingSourceAsUnreadable() {
        val missing = File(inputDir, "does-not-exist.jpg")
        assertRejected(missing, PhotoPreparationFailure.SOURCE_UNREADABLE)
    }

    @Test
    fun rejectsASourceOverTheConfiguredByteCapWithPayloadTooLarge() {
        val file = TestImages.writeJpeg(inputDir, "big.jpg", 1200, 900)
        val pipeline = AndroidPhotoPipeline(context, limits = PhotoPipelineLimits(maxSourceBytes = 1_000))
        assertRejected(file, PhotoPreparationFailure.SOURCE_TOO_LARGE, pipeline)
        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, PhotoPreparationFailure.SOURCE_TOO_LARGE.code)
    }

    @Test
    fun aTightMemoryBudgetStillProducesTheRequestedSizeOrSmaller() {
        val file = TestImages.writeJpeg(inputDir, "tight.jpg", 3000, 2000)
        val pipeline = AndroidPhotoPipeline(context, limits = PhotoPipelineLimits(maxDecodedPixels = 500_000))
        val photo = prepare(file, pipeline)
        assertTrue(photo.width <= 1600 && photo.height <= 1600)
        assertTrue("width ${photo.width}", photo.width >= 1)
        val full = decode(photo.fullJpeg)
        try {
            assertEquals(photo.width, full.width)
            assertEquals(photo.height, full.height)
        } finally {
            full.recycle()
        }
    }

    // ---- cancellation ----------------------------------------------------------------------------------

    @Test
    fun aCancelledPrepareStopsAndLeavesNoTemporaryFiles() = runBlocking {
        val file = TestImages.writeJpeg(inputDir, "cancel.jpg", 3000, 2000)
        val pipeline = AndroidPhotoPipeline(context)
        val stages = CopyOnWriteArrayList<ProcessingStage>()
        var outcome: Throwable? = null

        lateinit var job: Job
        job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            try {
                pipeline.prepare(TestImages.uriOf(file)) { stage ->
                    stages += stage
                    if (stage == ProcessingStage.DECODING) job.cancel() // cancel while the temp file exists
                }
            } catch (t: Throwable) {
                outcome = t
                throw t
            }
        }
        job.start()
        job.join()

        assertTrue("job should be cancelled", job.isCancelled)
        assertTrue("cancellation must surface as CancellationException, was $outcome", outcome is CancellationException)
        assertTrue(ProcessingStage.DECODING in stages)
        assertFalse("a cancelled prepare must not reach ENCODING or DONE", ProcessingStage.ENCODING in stages || ProcessingStage.DONE in stages)
        assertPipelineWorkDirIsEmpty()
    }

    @Test
    fun aPrepareCancelledBeforeItStartsDoesNothing() = runBlocking {
        val file = TestImages.writeJpeg(inputDir, "never.jpg", 1000, 800)
        val pipeline = AndroidPhotoPipeline(context)
        val stages = CopyOnWriteArrayList<ProcessingStage>()
        val job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            pipeline.prepare(TestImages.uriOf(file)) { stages += it }
        }
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(stages.isEmpty())
        assertPipelineWorkDirIsEmpty()
    }
}
