package com.agency.timeflip.data

import android.content.Context
import android.net.Uri
import android.os.StatFs
import com.agency.timeflip.domain.error.ErrorCategory
import com.agency.timeflip.domain.error.TimeFlipError
import com.agency.timeflip.domain.error.TimeFlipException
import com.agency.timeflip.domain.model.PhotoId
import com.agency.timeflip.domain.model.PhotoSlot
import com.agency.timeflip.domain.model.PhotoSourceRef
import com.agency.timeflip.domain.model.SourcePhoto
import com.agency.timeflip.domain.port.PhotoStore
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Copies picker photographs into app-private storage and keeps them there. The picker URI is read once,
 * during [importPhoto], and is never stored, so a revoked grant cannot break a saved project.
 *
 * [rootDir] must be dedicated to TimeFlip data (the app uses filesDir/timeflip). Thumbnails go to
 * cacheDir/thumbs. The default [availableBytes] reads StatFs. Tests inject their own.
 */
class PrivatePhotoStore(
    context: Context,
    private val rootDir: File,
    private val minFreeBytes: Long = 50L * 1024 * 1024,
    internal val availableBytes: (File) -> Long = { StatFs(it.absolutePath).availableBytes },
) : PhotoStore {

    private val photosDir = StorageLayout.photosDir(rootDir)
    private val thumbnailsDir = StorageLayout.thumbnailsDir(context.applicationContext.cacheDir)
    private val contentResolver = context.applicationContext.contentResolver

    init {
        require(minFreeBytes >= 0) { "minFreeBytes must not be negative" }
    }

    override suspend fun importPhoto(source: PhotoSourceRef, slot: PhotoSlot, nowEpochMs: Long): SourcePhoto =
        withContext(Dispatchers.IO) {
            ensureDirectory(photosDir)
            ensureFreeSpace()
            val staging = File(photosDir, "import-${UUID.randomUUID()}${StorageLayout.TEMP_SUFFIX}")
            try {
                copySource(source, staging)
                val id = PhotoId(UUID.randomUUID().toString())
                val output = File(photosDir, id.value + StorageLayout.PHOTO_EXTENSION)
                val size = ImageNormalizer.normalize(staging, output)
                SourcePhoto(
                    id = id,
                    slot = slot,
                    size = size,
                    byteSize = output.length(),
                    importedAtEpochMs = nowEpochMs,
                )
            } finally {
                staging.delete()
            }
        }

    override suspend fun originalPath(id: PhotoId): String? = withContext(Dispatchers.IO) {
        StorageLayout.photoFile(rootDir, id)?.takeIf { it.isFile }?.absolutePath
    }

    override suspend fun thumbnailPath(id: PhotoId, maxEdge: Int): String? {
        require(maxEdge > 0) { "maxEdge must be positive" }
        return withContext(Dispatchers.IO) {
            val source = StorageLayout.photoFile(rootDir, id)?.takeIf { it.isFile } ?: return@withContext null
            val thumbnail = StorageLayout.thumbnailFile(thumbnailsDir, id, maxEdge)
            if (!thumbnail.isFile || thumbnail.length() == 0L) {
                ensureDirectory(thumbnailsDir)
                ImageNormalizer.writeThumbnail(source, thumbnail, maxEdge)
            }
            thumbnail.absolutePath
        }
    }

    override suspend fun delete(id: PhotoId) {
        withContext(Dispatchers.IO) {
            StorageLayout.deletePhotoArtifacts(rootDir, thumbnailsDir, id)
        }
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("cannot create a private storage directory")
        }
    }

    private fun ensureFreeSpace() {
        ensureDirectory(rootDir)
        if (availableBytes(rootDir) < minFreeBytes) {
            throw TimeFlipException(TimeFlipError(ErrorCategory.STORAGE_FULL, "free space is below the import threshold"))
        }
    }

    /** One bounded pass from the picker stream to [destination]. Stops early when the size limit is exceeded. */
    private fun copySource(source: PhotoSourceRef, destination: File) {
        val stream = openSource(source)
        stream.use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var total = 0L
                while (true) {
                    val read = try {
                        input.read(buffer)
                    } catch (error: SecurityException) {
                        throw TimeFlipException(TimeFlipError(ErrorCategory.PERMISSION_DENIED, "grant revoked during import"), error)
                    } catch (error: IOException) {
                        throw TimeFlipException(TimeFlipError(ErrorCategory.SOURCE_MISSING, "source became unreadable"), error)
                    }
                    if (read < 0) break
                    total += read
                    if (total > MAX_SOURCE_BYTES) {
                        throw TimeFlipException(TimeFlipError(ErrorCategory.UNSUPPORTED_FORMAT, "source exceeds the size limit"))
                    }
                    try {
                        output.write(buffer, 0, read)
                    } catch (error: IOException) {
                        if (error.isDiskFull()) throw storageFull("disk full while copying", error)
                        throw error
                    }
                }
                output.flush()
            }
        }
    }

    private fun openSource(source: PhotoSourceRef): InputStream {
        if (source.value.isBlank()) {
            throw TimeFlipException(TimeFlipError(ErrorCategory.SOURCE_MISSING, "empty photo reference"))
        }
        val stream = try {
            contentResolver.openInputStream(Uri.parse(source.value))
        } catch (error: SecurityException) {
            throw TimeFlipException(TimeFlipError(ErrorCategory.PERMISSION_DENIED, "no grant for the selected photo"), error)
        } catch (error: FileNotFoundException) {
            throw TimeFlipException(TimeFlipError(ErrorCategory.SOURCE_MISSING, "selected photo no longer exists"), error)
        } catch (error: IllegalArgumentException) {
            throw TimeFlipException(TimeFlipError(ErrorCategory.SOURCE_MISSING, "photo reference is not readable"), error)
        }
        return stream ?: throw TimeFlipException(TimeFlipError(ErrorCategory.SOURCE_MISSING, "provider returned no stream"))
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024

        /** Larger sources are rejected. A 64 MiB encoded photo is far above typical phone output. */
        const val MAX_SOURCE_BYTES = 64L * 1024 * 1024
    }
}
