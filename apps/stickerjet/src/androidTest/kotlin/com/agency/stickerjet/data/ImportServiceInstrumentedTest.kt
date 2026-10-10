package com.agency.stickerjet.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agency.stickerjet.domain.ImportFailure
import com.agency.stickerjet.domain.ImportOutcome
import com.agency.stickerjet.domain.ImportRejection
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ImportService through the real ContentResolver, on a device or emulator. It covers a non-video file,
 * a missing file, and a URI whose provider is gone. The positive path with a real H.264 clip is
 * covered by the StickerJourneyTest journey. Nothing here runs on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class ImportServiceInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val runRoot = File(context.filesDir, "data-test/${UUID.randomUUID()}")
    private val files = ProjectFiles(File(runRoot, "projects"), File(runRoot, "temp"))
    private val importService = ImportService(context.contentResolver, files) { System.currentTimeMillis() }

    @After
    fun tearDown() {
        runRoot.deleteRecursively()
    }

    private fun fixture(name: String, bytes: ByteArray): Uri {
        val file = File(runRoot, "fixtures/$name").apply { parentFile?.mkdirs(); writeBytes(bytes) }
        return Uri.fromFile(file)
    }

    @Test
    fun nonVideoFileIsRejectedAndLeavesNoProjectFolder() = runBlocking {
        val uri = fixture("not-a-video.mp4", "This is not a video.".toByteArray())

        val outcome = importService.import(uri, "text-clip")

        val reason = (outcome as? ImportOutcome.Rejected)?.reason
        assertTrue("expected a rejection, got $outcome", reason == ImportRejection.NOT_A_VIDEO || reason == ImportRejection.UNREADABLE)
        assertFalse("rejected import must leave no project folder", files.dir("text-clip").exists())
    }

    @Test
    fun missingFileFailsAsAccessRevokedWithoutProjectFiles() = runBlocking {
        val uri = Uri.fromFile(File(runRoot, "fixtures/does-not-exist.mp4"))

        val outcome = importService.import(uri, "missing-clip")

        assertEquals(ImportOutcome.Failed(ImportFailure.ACCESS_REVOKED), outcome)
        assertFalse(files.dir("missing-clip").exists())
    }

    @Test
    fun uriWhoseProviderIsGoneFailsAsAccessRevokedWithoutProjectFiles() = runBlocking {
        val uri = Uri.parse("content://${context.packageName}.no-such-provider/clip/1")

        val outcome = importService.import(uri, "gone-clip")

        assertEquals(ImportOutcome.Failed(ImportFailure.ACCESS_REVOKED), outcome)
        assertFalse(files.dir("gone-clip").exists())
    }

    @Test
    fun failedImportsAddNothingToTheLibrary() = runBlocking {
        importService.import(Uri.parse("content://${context.packageName}.no-such-provider/clip/2"), "none-1")
        importService.import(fixture("text.mp4", ByteArray(16)), "none-2")

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        val repository = FileProjectRepository(files, json) { System.currentTimeMillis() }
        assertTrue(repository.list().isEmpty())
    }
}
