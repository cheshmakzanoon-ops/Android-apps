package com.agency.timeflip.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.agency.timeflip.domain.error.ErrorCategory
import com.agency.timeflip.domain.error.TimeFlipException
import com.agency.timeflip.domain.model.PhotoId
import com.agency.timeflip.domain.model.PhotoSlot
import com.agency.timeflip.domain.model.PhotoSourceRef
import com.agency.timeflip.domain.model.PixelSize
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Exercises the import path through Robolectric's content resolver. A registered stream stands in for a
 * picker grant. An unregistered URI stands in for a missing or revoked source.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrivatePhotoStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var rootDir: File
    private lateinit var store: PrivatePhotoStore

    @Before
    fun setUp() {
        rootDir = tmp.newFolder("timeflip")
        store = PrivatePhotoStore(TestFixtures.context(), rootDir, availableBytes = { Long.MAX_VALUE })
    }

    @Test
    fun importCopiesOrientsAndServesThumbnailsAndDeletesEverything() = runBlocking {
        val bytes = jpegBytes(width = 40, height = 20, orientation = ExifInterface.ORIENTATION_ROTATE_90)
        val ref = register("content://test.provider/photo-1", bytes)

        val imported = store.importPhoto(ref, PhotoSlot.BEFORE, nowEpochMs = 77L)

        assertEquals(PixelSize(20, 40), imported.size)
        assertEquals(77L, imported.importedAtEpochMs)
        assertEquals(PhotoSlot.BEFORE, imported.slot)
        assertNotNull(store.originalPath(imported.id))
        assertEquals(
            listOf("${imported.id.value}.jpg"),
            File(rootDir, "photos").listFiles()!!.map { it.name },
        )

        val thumbnail = checkNotNull(store.thumbnailPath(imported.id, maxEdge = 10))
        val thumbBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(thumbnail, thumbBounds)
        assertTrue(maxOf(thumbBounds.outWidth, thumbBounds.outHeight) <= 10)

        store.delete(imported.id)
        assertNull(store.originalPath(imported.id))
        assertFalse(File(thumbnail).exists())
    }

    @Test
    fun textFileIsRejectedAsUnsupportedFormatAndLeavesNoTemporaryFiles() = runBlocking {
        val ref = register("content://test.provider/notes", "just some text".toByteArray())

        val error = assertThrows(TimeFlipException::class.java) {
            runBlocking { store.importPhoto(ref, PhotoSlot.AFTER, nowEpochMs = 1L) }
        }

        assertEquals(ErrorCategory.UNSUPPORTED_FORMAT, error.error.category)
        val leftovers = File(rootDir, "photos").listFiles().orEmpty()
        assertTrue("temporary files left behind: ${leftovers.map { it.name }}", leftovers.isEmpty())
    }

    @Test
    fun unavailableSourceIsReportedAsSourceMissing() = runBlocking {
        val ref = PhotoSourceRef("content://test.provider/revoked-or-deleted")

        val error = assertThrows(TimeFlipException::class.java) {
            runBlocking { store.importPhoto(ref, PhotoSlot.BEFORE, nowEpochMs = 1L) }
        }

        assertEquals(ErrorCategory.SOURCE_MISSING, error.error.category)
    }

    @Test
    fun lowStorageStopsImportBeforeAnyCopyIsWritten() = runBlocking {
        val lowStorage = PrivatePhotoStore(TestFixtures.context(), rootDir, minFreeBytes = 50L, availableBytes = { 10L })
        val ref = register("content://test.provider/photo-2", jpegBytes(width = 40, height = 20, orientation = 1))

        val error = assertThrows(TimeFlipException::class.java) {
            runBlocking { lowStorage.importPhoto(ref, PhotoSlot.AFTER, nowEpochMs = 1L) }
        }

        assertEquals(ErrorCategory.STORAGE_FULL, error.error.category)
        assertTrue(File(rootDir, "photos").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun unknownPhotoIdHasNoPathAndDeleteIsSafe() = runBlocking {
        assertNull(store.originalPath(PhotoId("does-not-exist")))
        assertNull(store.originalPath(PhotoId("../escape")))
        store.delete(PhotoId("does-not-exist"))
    }

    /** Registers [bytes] behind [uri] in Robolectric's content resolver and returns the matching reference. */
    private fun register(uri: String, bytes: ByteArray): PhotoSourceRef {
        val parsed = Uri.parse(uri)
        Shadows.shadowOf(TestFixtures.context().contentResolver).registerInputStream(parsed, ByteArrayInputStream(bytes))
        return PhotoSourceRef(uri)
    }

    private fun jpegBytes(width: Int, height: Int, orientation: Int): ByteArray {
        val file = File(tmp.root, "source-${width}x$height-$orientation.jpg")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        val exif = ExifInterface(file)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        exif.saveAttributes()
        return file.readBytes()
    }
}
