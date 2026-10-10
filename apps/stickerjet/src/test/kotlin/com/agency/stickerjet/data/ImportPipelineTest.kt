package com.agency.stickerjet.data

import com.agency.stickerjet.domain.ImportRejection
import com.agency.stickerjet.domain.StickerLimits
import com.agency.stickerjet.domain.StickerProjects
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImportPipelineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var files: ProjectFiles
    private val data = ByteArray(4_096) { (it % 251).toByte() }

    @Before
    fun setUp() {
        files = projectFilesIn(tmp.root)
    }

    private fun pipeline(
        probe: FakeMediaProbe = FakeMediaProbe(),
        room: Long = Long.MAX_VALUE / 2,
        maxSource: Long = 1_000_000L,
    ): ImportPipeline = ImportPipeline(
        files = files,
        probe = probe,
        storage = StorageGuard(files.root, usableBytes = { room }),
        repository = repositoryFor(files),
        clock = { TEST_NOW_MS },
        maxSourceBytes = maxSource,
    )

    private fun clipOf(bytes: ByteArray = data, displayName: String = "My clip.final.mp4") = FakeClip(
        info = ClipInfo(sizeBytes = bytes.size.toLong(), displayName = displayName),
        open = { bytes.inputStream() },
    )

    private fun importClip(id: String, clip: ClipSource, pipeline: ImportPipeline): ImportOutcome =
        runBlocking { pipeline.run(id, clip) }

    private fun loadProject(id: String) = runBlocking { repositoryFor(files).load(id) }

    private fun assertNothingKept(id: String) {
        assertFalse("no project folder may remain", files.dir(id).exists())
    }

    @Test
    fun acceptedImportKeepsSourceThumbnailAndSavedProject() {
        val probe = FakeMediaProbe(facts = FakeMediaProbe.okFacts(rotation = 90))

        val outcome = importClip("p1", clipOf(), pipeline(probe))

        val project = (outcome as ImportOutcome.Imported).project
        assertEquals("p1", project.id)
        assertEquals(ProjectFiles.SOURCE_NAME, project.source.fileName)
        assertEquals(5_000L, project.source.durationMs)
        assertEquals("oriented width must swap for 90 degrees", 1920, project.source.width)
        assertEquals("oriented height must swap for 90 degrees", 1080, project.source.height)
        assertEquals(90, project.source.rotationDegrees)
        assertEquals(data.size.toLong(), project.source.sizeBytes)
        assertEquals("My clip.final", project.title)
        assertEquals(StickerLimits.DEFAULT_STICKER_MS, project.trim.durationMs)
        assertArrayEquals(data, files.sourceFile("p1").readBytes())
        assertTrue(files.thumbnailFile("p1").isFile)
        assertEquals(listOf(ThumbnailSize.MAX_SIDE_PX), probe.thumbnailSizes)
        assertEquals(project, loadProject("p1"))
        assertFalse(File(files.dir("p1"), "thumbnail.jpg.tmp").exists())
    }

    @Test
    fun unknownDisplayNameFallsBackToTheDefaultTitle() {
        val outcome = importClip("p1b", clipOf(displayName = ""), pipeline())

        val project = (outcome as ImportOutcome.Imported).project
        assertEquals(StickerProjects.FALLBACK_TITLE, project.title)
    }

    @Test
    fun clipAboveMaximumDurationIsRejectedAndNothingIsKept() {
        val probe = FakeMediaProbe(facts = FakeMediaProbe.okFacts().copy(durationMs = 60_000L))

        val outcome = importClip("p2", clipOf(), pipeline(probe))

        assertEquals(ImportOutcome.Rejected(ImportRejection.TOO_LONG), outcome)
        assertNothingKept("p2")
        assertTrue(runBlocking { repositoryFor(files).list() }.isEmpty())
    }

    @Test
    fun fileWithoutVideoTrackIsRejectedAsNotAVideo() {
        val probe = FakeMediaProbe(facts = FakeMediaProbe.okFacts().copy(hasVideoTrack = false))

        assertEquals(ImportOutcome.Rejected(ImportRejection.NOT_A_VIDEO), importClip("p3", clipOf(), pipeline(probe)))
        assertNothingKept("p3")
    }

    @Test
    fun probeCrashIsReportedAsUnreadable() {
        val probe = FakeMediaProbe(probeFailure = IllegalStateException("decoder crashed"))

        assertEquals(ImportOutcome.Rejected(ImportRejection.UNREADABLE), importClip("p4", clipOf(), pipeline(probe)))
        assertNothingKept("p4")
    }

    @Test
    fun copyOneByteOverTheCapIsRejectedAndDeletesThePartialCopy() {
        // The provider declares no size, so only the capped copy can notice the oversize clip.
        val clip = FakeClip(info = ClipInfo(sizeBytes = null, displayName = "x.mp4"), open = { data.inputStream() })

        val outcome = importClip("p5", clip, pipeline(maxSource = data.size - 1L))

        assertEquals(ImportOutcome.Rejected(ImportRejection.TOO_LARGE), outcome)
        assertNothingKept("p5")
    }

    @Test
    fun copyOfExactlyTheCapIsAccepted() {
        val outcome = importClip("p6", clipOf(), pipeline(maxSource = data.size.toLong()))

        assertTrue(outcome is ImportOutcome.Imported)
    }

    @Test
    fun declaredOversizeIsRefusedBeforeTheClipIsOpened() {
        val clip = FakeClip(info = ClipInfo(sizeBytes = 2_000_000L, displayName = "big.mp4"))

        assertEquals(ImportOutcome.Rejected(ImportRejection.TOO_LARGE), importClip("p7", clip, pipeline(maxSource = 1_000L)))
        assertEquals(0, clip.opened)
        assertNothingKept("p7")
    }

    @Test
    fun insufficientRoomIsLowStorageAndTheClipIsNotOpened() {
        val clip = clipOf()

        val outcome = importClip("p8", clip, pipeline(room = 10L * 1024L * 1024L))

        assertEquals(ImportOutcome.Failed(ImportFailure.LOW_STORAGE), outcome)
        assertEquals(0, clip.opened)
        assertNothingKept("p8")
    }

    @Test
    fun revokedAccessOnOpenIsAccessRevoked() {
        val clip = FakeClip(open = { throw FileNotFoundException("no longer served") })

        assertEquals(ImportOutcome.Failed(ImportFailure.ACCESS_REVOKED), importClip("p9", clip, pipeline()))
        assertNothingKept("p9")
    }

    @Test
    fun revokedAccessMidCopyIsAccessRevokedAndPartialFileIsGone() {
        val clip = FakeClip(open = { FlakyStream(data, failAt = 1_000, error = SecurityException("revoked")) })

        assertEquals(ImportOutcome.Failed(ImportFailure.ACCESS_REVOKED), importClip("p10", clip, pipeline()))
        assertNothingKept("p10")
    }

    @Test
    fun outOfSpaceMidCopyIsLowStorage() {
        val error = IOException("write failed: ENOSPC (No space left on device)")
        val clip = FakeClip(open = { FlakyStream(data, failAt = 1_000, error = error) })

        assertEquals(ImportOutcome.Failed(ImportFailure.LOW_STORAGE), importClip("p11", clip, pipeline()))
        assertNothingKept("p11")
    }

    @Test
    fun otherReadErrorIsIoError() {
        val clip = FakeClip(open = { FlakyStream(data, failAt = 1_000, error = IOException("read failed")) })

        assertEquals(ImportOutcome.Failed(ImportFailure.IO_ERROR), importClip("p12", clip, pipeline()))
        assertNothingKept("p12")
    }

    @Test
    fun missingThumbnailIsNotFatal() {
        val probe = FakeMediaProbe(thumbnailWritten = false)

        val outcome = importClip("p13", clipOf(), pipeline(probe))

        assertTrue(outcome is ImportOutcome.Imported)
        assertFalse(files.thumbnailFile("p13").exists())
    }

    @Test
    fun thumbnailCrashIsNotFatal() {
        val probe = FakeMediaProbe(thumbnailFailure = IllegalStateException("bitmap failed"))

        val outcome = importClip("p14", clipOf(), pipeline(probe))

        assertTrue(outcome is ImportOutcome.Imported)
        assertFalse(files.thumbnailFile("p14").exists())
        assertNotNull(loadProject("p14"))
    }

    @Test
    fun unexpectedExceptionPropagatesAndCleansUp() {
        val clip = FakeClip(open = { FlakyStream(data, failAt = 1_000, error = IllegalStateException("bug")) })

        try {
            importClip("p15", clip, pipeline())
            fail("an unexpected exception must not be swallowed")
        } catch (expected: IllegalStateException) {
            assertEquals("bug", expected.message)
        }
        assertNothingKept("p15")
    }

    @Test
    fun existingProjectIdIsRefusedAndUntouched() {
        runBlocking { repositoryFor(files).save(sampleProject(id = "taken", title = "Mine")) }

        assertThrows(IllegalArgumentException::class.java) {
            importClip("taken", clipOf(), pipeline())
        }

        assertEquals("Mine", loadProject("taken")?.title)
    }

    @Test
    fun leftoversOfAnInterruptedAttemptAreClearedBeforeTheNewImport() {
        File(files.dir("retry"), "old-partial.bin").apply { parentFile.mkdirs() }.writeBytes(ByteArray(5))

        val outcome = importClip("retry", clipOf(), pipeline())

        assertTrue(outcome is ImportOutcome.Imported)
        assertFalse(File(files.dir("retry"), "old-partial.bin").exists())
    }

    @Test
    fun invalidIdIsRefusedBeforeAnyFileIsWritten() {
        assertThrows(IllegalArgumentException::class.java) {
            importClip("../escape", clipOf(), pipeline())
        }
        assertFalse(File(tmp.root, "escape").exists())
    }

    @Test
    fun projectIsInFlightOnlyWhileImporting() {
        val seenInFlight = mutableListOf<Boolean>()
        val clip = FakeClip(open = {
            seenInFlight += files.inFlight.contains("p16")
            data.inputStream()
        })

        importClip("p16", clip, pipeline())

        assertEquals(listOf(true), seenInFlight)
        assertFalse(files.inFlight.contains("p16"))
    }
}
