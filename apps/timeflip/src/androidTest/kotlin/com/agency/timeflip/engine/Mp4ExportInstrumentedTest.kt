package com.agency.timeflip.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agency.timeflip.domain.model.AlignmentTransform
import com.agency.timeflip.domain.model.Easing
import com.agency.timeflip.domain.model.ExportProfile
import com.agency.timeflip.domain.model.ExportProgress
import com.agency.timeflip.domain.model.ExportRequest
import com.agency.timeflip.domain.model.ImagePair
import com.agency.timeflip.domain.model.OutputPreset
import com.agency.timeflip.domain.model.PhotoId
import com.agency.timeflip.domain.model.PhotoSlot
import com.agency.timeflip.domain.model.PhotoSourceRef
import com.agency.timeflip.domain.model.PixelSize
import com.agency.timeflip.domain.model.ProjectId
import com.agency.timeflip.domain.model.SourcePhoto
import com.agency.timeflip.domain.model.TimelinePlan
import com.agency.timeflip.domain.model.TransitionDefinition
import com.agency.timeflip.domain.model.TransitionKind
import com.agency.timeflip.domain.model.TransitionParameters
import com.agency.timeflip.domain.port.PhotoStore
import com.agency.timeflip.engine.export.Mp4VideoExporter
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * Exports a short synthetic project end to end and reads the MP4 back. Requires a device or emulator
 * with an AVC encoder, so it runs in CI rather than in the JVM harness.
 */
@RunWith(AndroidJUnit4::class)
class Mp4ExportInstrumentedTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun exportsValidMp4WhoseFirstAndLastFramesDiffer() {
        val photosDir = temporaryFolder.newFolder("photos")
        val exportsDir = temporaryFolder.newFolder("exports")

        val beforeId = PhotoId("synthetic-before")
        val afterId = PhotoId("synthetic-after")
        val beforeFile = File(photosDir, "before.jpg")
        val afterFile = File(photosDir, "after.jpg")
        writeJpeg(syntheticPhoto(warm = true), beforeFile)
        writeJpeg(syntheticPhoto(warm = false), afterFile)

        val store = TestPhotoStore(mapOf(beforeId to beforeFile, afterId to afterFile))
        val before = SourcePhoto(beforeId, PhotoSlot.BEFORE, PixelSize(PHOTO_WIDTH, PHOTO_HEIGHT), beforeFile.length(), 0L)
        val after = SourcePhoto(afterId, PhotoSlot.AFTER, PixelSize(PHOTO_WIDTH, PHOTO_HEIGHT), afterFile.length(), 0L)

        val timeline = TimelinePlan(transitionMs = 600, holdBeforeMs = 200, holdAfterMs = 200, fps = FPS)
        val request = ExportRequest(
            projectId = ProjectId("instrumented-project"),
            projectName = "Instrumented",
            pair = ImagePair(before, after),
            alignment = AlignmentTransform.IDENTITY,
            transition = TransitionDefinition(
                kind = TransitionKind.HORIZONTAL_REVEAL,
                parameters = TransitionParameters(durationMs = 600, easing = Easing.LINEAR),
            ),
            timeline = timeline,
            profile = ExportProfile(preset = OutputPreset.PORTRAIT_720, fps = FPS),
        )

        val exporter = Mp4VideoExporter(store, exportsDir, nowMs = { 1_700_000_000_000L })
        val events = runBlocking { exporter.export(request).toList() }

        val terminals = events.filter {
            it is ExportProgress.Finished || it is ExportProgress.Failed || it == ExportProgress.Cancelled
        }
        assertEquals("exactly one terminal event", 1, terminals.size)
        assertEquals(terminals.single(), events.last())
        val finished = events.last() as? ExportProgress.Finished
            ?: throw AssertionError("export did not finish: ${events.last()}")

        val rendering = events.filterIsInstance<ExportProgress.Rendering>()
        assertEquals(timeline.frameCount, rendering.size)
        assertEquals(timeline.frameCount, rendering.last().framesDone)

        val video = finished.video
        assertEquals(OutputPreset.PORTRAIT_720.width, video.width)
        assertEquals(OutputPreset.PORTRAIT_720.height, video.height)
        assertTrue(video.fileName.endsWith(".mp4"))

        val file = File(exportsDir, video.fileName)
        assertTrue("exported file missing", file.isFile)
        assertFalse("partial files must not remain", exportsDir.listFiles().orEmpty().any { it.name.endsWith(".part") })

        assertAvcTrack(file, OutputPreset.PORTRAIT_720)
        assertDurationWithinTolerance(file, timeline.totalDurationMs)
        assertFirstAndLastFramesDiffer(file)
    }

    private fun assertAvcTrack(file: File, preset: OutputPreset) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            assertEquals(1, extractor.trackCount)
            val format: MediaFormat = extractor.getTrackFormat(0)
            assertEquals("video/avc", format.getString(MediaFormat.KEY_MIME))
            assertEquals(preset.width, format.getInteger(MediaFormat.KEY_WIDTH))
            assertEquals(preset.height, format.getInteger(MediaFormat.KEY_HEIGHT))
            extractor.selectTrack(0)
            var samples = 0
            while (extractor.sampleTime >= 0) {
                samples++
                if (!extractor.advance()) break
            }
            assertTrue("at least one sample", samples > 0)
        } finally {
            extractor.release()
        }
    }

    private fun assertDurationWithinTolerance(file: File, expectedMs: Int) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = requireNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) {
                "duration metadata missing"
            }.toLong()
            val tolerance = expectedMs * 0.02
            assertTrue(
                "duration $durationMs ms is not within 2% of $expectedMs ms",
                abs(durationMs - expectedMs).toDouble() <= tolerance,
            )
        } finally {
            retriever.release()
        }
    }

    private fun assertFirstAndLastFramesDiffer(file: File) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val first = requireNotNull(retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST)) {
                "first frame could not be decoded"
            }
            val last = requireNotNull(retriever.getFrameAtTime(LAST_FRAME_US, MediaMetadataRetriever.OPTION_CLOSEST)) {
                "last frame could not be decoded"
            }
            assertEquals(first.width, last.width)
            assertEquals(first.height, last.height)
            val difference = meanAbsoluteDifference(first, last)
            // Warm and cool synthetic photos differ strongly, so a real transition gives a large mean difference.
            assertTrue("first and last frames are too similar: mean difference $difference", difference > 30.0)
        } finally {
            retriever.release()
        }
    }

    private fun meanAbsoluteDifference(a: Bitmap, b: Bitmap): Double {
        var total = 0L
        var count = 0
        val step = 16
        for (y in 0 until a.height step step) {
            for (x in 0 until a.width step step) {
                val pa = a.getPixel(x, y)
                val pb = b.getPixel(x, y)
                total += abs(Color.red(pa) - Color.red(pb)) +
                    abs(Color.green(pa) - Color.green(pb)) +
                    abs(Color.blue(pa) - Color.blue(pb))
                count += 3
            }
        }
        return total.toDouble() / count
    }

    /** Two-tone scene with a disc and boxes. Warm and cool palettes differ strongly. */
    private fun syntheticPhoto(warm: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(PHOTO_WIDTH, PHOTO_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = if (warm) Color.rgb(230, 120, 40) else Color.rgb(40, 110, 220)
        canvas.drawRect(0f, 0f, PHOTO_WIDTH.toFloat(), PHOTO_HEIGHT.toFloat(), paint)
        paint.color = if (warm) Color.rgb(250, 230, 90) else Color.rgb(30, 200, 170)
        canvas.drawCircle(PHOTO_WIDTH * 0.35f, PHOTO_HEIGHT * 0.4f, PHOTO_WIDTH * 0.22f, paint)
        paint.color = if (warm) Color.rgb(90, 30, 20) else Color.rgb(15, 25, 70)
        canvas.drawRect(
            PHOTO_WIDTH * 0.5f,
            PHOTO_HEIGHT * 0.6f,
            PHOTO_WIDTH * 0.9f,
            PHOTO_HEIGHT * 0.9f,
            paint,
        )
        return bitmap
    }

    private fun writeJpeg(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            assertTrue("JPEG encode", bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out))
        }
        bitmap.recycle()
    }

    /** Serves the synthetic private copies. Import is not exercised by this test. */
    private class TestPhotoStore(private val paths: Map<PhotoId, File>) : PhotoStore {
        override suspend fun importPhoto(source: PhotoSourceRef, slot: PhotoSlot, nowEpochMs: Long): SourcePhoto =
            throw UnsupportedOperationException("import is not used by this test")

        override suspend fun originalPath(id: PhotoId): String? = paths[id]?.takeIf { it.isFile }?.absolutePath

        override suspend fun thumbnailPath(id: PhotoId, maxEdge: Int): String? = originalPath(id)

        override suspend fun delete(id: PhotoId) = Unit
    }

    private companion object {
        const val PHOTO_WIDTH = 1200
        const val PHOTO_HEIGHT = 1600
        const val FPS = 30

        /** Timeline is 1000 ms at 30 fps, so the last frame is near 0.97 s. */
        const val LAST_FRAME_US = 960_000L
    }
}
