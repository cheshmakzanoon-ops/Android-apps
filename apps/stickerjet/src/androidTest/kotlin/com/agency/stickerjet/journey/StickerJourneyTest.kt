package com.agency.stickerjet.journey

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agency.stickerjet.data.FileProjectRepository
import com.agency.stickerjet.data.ImportFailure
import com.agency.stickerjet.data.ImportOutcome
import com.agency.stickerjet.data.ImportService
import com.agency.stickerjet.data.ProjectEnvelope
import com.agency.stickerjet.data.ProjectFiles
import com.agency.stickerjet.domain.ExportPlan
import com.agency.stickerjet.domain.ImportRejection
import com.agency.stickerjet.domain.StickerLimits
import com.agency.stickerjet.domain.StickerProject
import com.agency.stickerjet.domain.TrimPolicy
import com.agency.stickerjet.engine.ExportFailure
import com.agency.stickerjet.engine.ExportOutcome
import com.agency.stickerjet.engine.FrameEncoder
import com.agency.stickerjet.engine.MediaFrameSource
import com.agency.stickerjet.engine.MlKitSubjectSegmenter
import com.agency.stickerjet.engine.StickerExporter
import com.agency.stickerjet.engine.WebpInspector
import com.agency.stickerjet.platform.ExportSharing
import com.agency.stickerjet.platform.H264Capability
import com.agency.stickerjet.platform.H264DecodeCapability
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * End-to-end journey with REAL adapters and no fakes:
 *
 *   bundled clip -> MediaStore content URI (like a Photo Picker pick) -> ImportService -> project trim
 *   -> StickerExporter (MediaFrameSource, MlKitSubjectSegmenter, FrameEncoder) -> file on disk
 *   -> WebpInspector (structure) -> ExportSharing (FileProvider URI, grants, chooser)
 *
 * Fixture: `apps/stickerjet/assets/source/journey-clip.mp4` is added to the androidTest assets by
 * build.gradle.kts. It must be an H.264 MP4 of 3 to 30 seconds, 150 MB at most, and should show one
 * clearly visible person, so that the segmentation path runs. The test asserts subjectFrames only
 * as a conditional (see below), so a clip with no person still tests the fallback.
 *
 * The journey writes its export to `files/journey-output/journey-export.webp` on success. CI reads
 * that file with `adb exec-out run-as` and checks it with tools/verify_animated_webp.py.
 *
 * NOT covered here (each is a QA row in docs/qa-test-plan.md, or out of scope):
 * - Recipient apps and messengers: the chooser is resolved, but no receiver is driven and no
 *   messenger is asked to accept, preview or install the sticker. StickerJet never claims that.
 * - The Photo Picker UI itself (rememberVideoPicker runs inside a Compose screen; it is manual QA).
 * - Revoked permission and a removed original during import, low storage, offline use, cancel,
 *   process death and upgrade. The negative tests cover only invalid input and a missing file.
 * - Matte quality and visual appearance. The test checks structure, timing and size, not looks.
 * - Independent decoding of the WebP. That is verify_animated_webp.py in CI, not this test.
 * - Physical devices and devices without an H.264 decoder. On those the test is skipped (Assume),
 *   which the QA plan records as NOT RUN, not as a pass.
 */
@RunWith(AndroidJUnit4::class)
class StickerJourneyTest {

    @get:Rule
    val timeout: Timeout = Timeout.builder().withTimeout(5, TimeUnit.MINUTES).build()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** The app under test. Instrumented tests run in the app process, so this is the real app context. */
    private val context: Context = instrumentation.targetContext

    /** The test APK. Holds the bundled fixture clip in its assets. */
    private val fixtureContext: Context = instrumentation.context

    private val clock: () -> Long = { System.currentTimeMillis() }

    private val runRoot: File = File(context.filesDir, "journey-test/${UUID.randomUUID()}")
    private val files = ProjectFiles(File(runRoot, "projects"))

    // The same Json factory ImportService uses, so documents written by one are read by the other.
    private val repository = FileProjectRepository(files, ProjectEnvelope.createJson(), clock)
    private val importService = ImportService(context.contentResolver, files, clock)
    private val exporter = StickerExporter(
        frames = MediaFrameSource { projectId -> files.sourceFile(projectId) },
        segmenter = MlKitSubjectSegmenter(),
        encoder = FrameEncoder(),
        repository = repository,
        files = files,
        clock = clock,
    )

    private val mediaUris = mutableListOf<Uri>()
    private val shareFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        // The journey needs an H.264 decoder to import the fixture. Without one the test is
        // skipped and recorded as NOT RUN, never as a pass.
        Assume.assumeTrue(
            "device lists no H.264 decoder; journey not applicable",
            H264Capability.probe() is H264DecodeCapability.Supported,
        )
    }

    @After
    fun tearDown() {
        mediaUris.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        shareFiles.forEach { it.delete() }
        ExportSharing.clearShareCopies(context)
        runRoot.deleteRecursively()
    }

    @Test
    fun importTrimExportAndShareOneStickerEndToEnd() = runBlocking {
        val projectId = newProjectId()
        val project = importFixture(projectId)

        // Trim: the default window is the first DEFAULT_STICKER_MS of the clip, and it is valid.
        assertTrue("source copy missing in project directory", files.sourceFile(projectId).isFile)
        assertTrue(
            "fixture must be at least ${StickerLimits.DEFAULT_STICKER_MS} ms",
            project.source.durationMs >= StickerLimits.DEFAULT_STICKER_MS,
        )
        assertTrue(project.source.durationMs <= StickerLimits.MAX_SOURCE_MS)
        assertEquals(TrimPolicy.defaultTrim(project.source.durationMs), project.trim)
        assertTrue(TrimPolicy.isValid(project.trim, project.source.durationMs))
        assertEquals(StickerLimits.DEFAULT_STICKER_MS, project.trim.durationMs)

        // Export: a real file, produced by the real segmenter and encoder.
        val progress = mutableListOf<Float>()
        val outcome = exporter.export(project) { fraction -> progress += fraction }
        val success = outcome as? ExportOutcome.Success
            ?: throw AssertionError("export did not succeed: $outcome")
        assertTrue("progress must stay within 0..1", progress.all { it in 0f..1f })

        val exported = success.file
        assertTrue("export file missing: $exported", exported.isFile)
        assertTrue(
            "export of ${exported.length()} bytes exceeds ${StickerLimits.MAX_EXPORT_BYTES}",
            exported.length() <= StickerLimits.MAX_EXPORT_BYTES,
        )
        assertEquals(exported.name, success.record.fileName)
        assertEquals(exported.length(), success.record.sizeBytes)

        // The file is a complete animated WebP with the planned frames, durations and canvas.
        val info = WebpInspector.inspect(exported.readBytes())
        val plannedDurations = ExportPlan.frameDurationsMs(project.trim).toList()
        assertEquals(ExportPlan.frameTimestampsMs(project.trim).size, info.frameCount)
        assertEquals(success.totalFrames, info.frameCount)
        assertEquals(info.frameCount, success.record.frameCount)
        assertEquals(plannedDurations, info.frameDurationsMs)
        assertEquals(project.trim.durationMs, plannedDurations.sum().toLong())
        assertEquals(StickerLimits.CANVAS_PX, info.canvasWidth)
        assertEquals(StickerLimits.CANVAS_PX, info.canvasHeight)
        assertEquals(0, info.loopCount)
        // Masked frames must carry alpha. Without a detected person the fallback may be opaque.
        if (success.subjectFrames > 0) {
            assertTrue("masked frames require alpha chunks", info.hasAlphaChunks)
        }

        // Share: a share copy in cacheDir/share, a FileProvider URI, read grants and a chooser.
        val chooser = ExportSharing.shareIntent(context, exported)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue((chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)

        val send = chooser.innerSendIntent()
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/webp", send.type)
        assertTrue((send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)

        val stream = send.streamUri()
        assertEquals("${context.packageName}.fileprovider", stream.authority)
        assertEquals(stream, send.clipData?.getItemAt(0)?.uri)
        assertEquals("image/webp", context.contentResolver.getType(stream))

        // The provider serves exactly the exported bytes.
        val served = context.contentResolver.openInputStream(stream)?.use { it.readBytes() }
            ?: throw AssertionError("FileProvider refused $stream")
        assertArrayEquals(exported.readBytes(), served)

        // The chooser resolves to an activity. Recipient apps are NOT asserted (see class notes).
        assertNotNull("share chooser resolves to no activity", chooser.resolveActivity(context.packageManager))

        // Reopen from disk: the export is recorded on the project.
        val reopened = FileProjectRepository(files, ProjectEnvelope.createJson(), clock).load(projectId)
            ?: throw AssertionError("project not readable after export")
        assertTrue(reopened.exports.any { it.fileName == exported.name })

        keepForCi(exported)
    }

    @Test
    fun unsupportedFileIsRejectedAndLeavesNoProjectFiles() = runBlocking {
        val projectId = newProjectId()
        val uri = shareUri("journey-${UUID.randomUUID()}.txt", "This is not a video.".toByteArray())

        val outcome = importService.import(uri, projectId)

        val reason = (outcome as? ImportOutcome.Rejected)?.reason
            ?: throw AssertionError("expected Rejected for a text file, got $outcome")
        assertTrue(
            "unexpected rejection reason $reason",
            reason == ImportRejection.NOT_A_VIDEO || reason == ImportRejection.UNREADABLE,
        )
        assertFalse("rejected import must leave no project directory", files.dir(projectId).exists())
    }

    @Test
    fun missingSourceFailsCleanlyWithoutProjectFiles() = runBlocking {
        val projectId = newProjectId()
        // A provider URI whose file does not exist: the same shape as a revoked or deleted pick.
        val uri = shareUri("journey-missing-${UUID.randomUUID()}.mp4", bytes = null)

        val outcome = importService.import(uri, projectId)

        val clean = when (outcome) {
            is ImportOutcome.Failed ->
                outcome.cause == ImportFailure.ACCESS_REVOKED || outcome.cause == ImportFailure.IO_ERROR
            is ImportOutcome.Rejected -> outcome.reason == ImportRejection.UNREADABLE
            is ImportOutcome.Imported -> false
        }
        assertTrue("missing source must fail cleanly, got $outcome", clean)
        assertFalse("failed import must leave no source copy", files.sourceFile(projectId).exists())
    }

    @Test
    fun exportWithDeletedOriginalFailsAndKeepsTheDraft() = runBlocking {
        val projectId = newProjectId()
        val project = importFixture(projectId)
        assertTrue("could not remove the source copy for the test", files.sourceFile(projectId).delete())

        val outcome = exporter.export(project) { }

        assertEquals(ExportOutcome.Failed(ExportFailure.SOURCE_MISSING), outcome)
        assertNotNull("the draft must survive a failed export", repository.load(projectId))
        assertTrue(
            "a failed export must not leave a partial file",
            files.exportsDir(projectId).listFiles().orEmpty().isEmpty(),
        )
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun newProjectId(): String = "journey-${UUID.randomUUID()}"

    /** Bundled fixture bytes from the test APK. A missing fixture fails with a pointer to the docs. */
    private fun fixtureBytes(): ByteArray = try {
        fixtureContext.assets.open(FIXTURE_ASSET).use { it.readBytes() }
    } catch (e: IOException) {
        throw AssertionError("fixture $FIXTURE_ASSET is missing from the test assets (see docs/qa-test-plan.md)", e)
    }

    /** Imports the fixture through a MediaStore content URI and saves the project, as the app does. */
    private suspend fun importFixture(projectId: String): StickerProject {
        val uri = mediaStoreUri("journey-${UUID.randomUUID()}.mp4", "video/mp4", fixtureBytes())
        val outcome = importService.import(uri, projectId)
        val project = (outcome as? ImportOutcome.Imported)?.project
            ?: throw AssertionError("fixture import did not succeed: $outcome")
        repository.save(project)
        return project
    }

    /** Inserts [bytes] into MediaStore as a video owned by this app. Returns its content:// URI. */
    private fun mediaStoreUri(displayName: String, mimeType: String, bytes: ByteArray): Uri {
        val resolver = context.contentResolver
        val pending = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/StickerJetJourney")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, pending)
            ?: throw AssertionError("MediaStore refused to insert $displayName")
        mediaUris += uri
        val out = resolver.openOutputStream(uri) ?: throw AssertionError("cannot write to $uri")
        out.use { it.write(bytes) }
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )
        return uri
    }

    /**
     * A FileProvider URI for a file under cacheDir/share, the only directory the provider exposes.
     * With [bytes] null the file is never created, which models a missing or revoked original.
     */
    private fun shareUri(name: String, bytes: ByteArray? = null): Uri {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        val file = File(dir, name)
        shareFiles += file
        if (bytes != null) file.writeBytes(bytes)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Leaves the export where CI can read it with run-as. Debug builds are debuggable. */
    private fun keepForCi(exported: File) {
        val dir = File(context.filesDir, JOURNEY_OUTPUT_DIR).apply { mkdirs() }
        exported.copyTo(File(dir, JOURNEY_OUTPUT_NAME), overwrite = true)
    }

    @Suppress("DEPRECATION")
    private fun Intent.innerSendIntent(): Intent {
        val inner = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_INTENT)
        }
        return inner ?: throw AssertionError("chooser has no EXTRA_INTENT")
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamUri(): Uri {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_STREAM)
        }
        return uri ?: throw AssertionError("send intent has no EXTRA_STREAM")
    }

    private companion object {
        /** Must match ExportSharing's share directory and file_paths.xml. */
        const val SHARE_DIR = "share"
        const val FIXTURE_ASSET = "journey-clip.mp4"
        const val JOURNEY_OUTPUT_DIR = "journey-output"
        const val JOURNEY_OUTPUT_NAME = "journey-export.webp"
    }
}
