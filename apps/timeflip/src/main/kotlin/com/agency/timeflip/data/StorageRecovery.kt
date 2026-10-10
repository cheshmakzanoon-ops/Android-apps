package com.agency.timeflip.data

import com.agency.timeflip.data.db.TimeFlipDatabase
import com.agency.timeflip.data.db.parseEnum
import com.agency.timeflip.domain.model.PhotoId
import com.agency.timeflip.domain.model.PhotoSlot
import com.agency.timeflip.domain.model.ProjectId
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A photo row whose private copy is gone. The UI shows SOURCE_MISSING for it. The project row is kept. */
data class MissingPhoto(
    val projectId: ProjectId,
    val photoId: PhotoId,
    val slot: PhotoSlot,
)

data class RecoveryReport(
    /** Unfinished write files (`*.tmp`) removed from photos and exports. */
    val tempFilesRemoved: Int,
    /** Finished photo or export files that no row references, removed after the grace period. */
    val unreferencedFilesRemoved: Int,
    /** Thumbnails older than [StorageRecovery.THUMBNAIL_MAX_AGE_MS], or whose source copy is gone. */
    val staleThumbnailsRemoved: Int,
    val missingPhotos: List<MissingPhoto>,
)

/**
 * Startup cleanup for interrupted imports, exports and thumbnails. Run it once per process, before any
 * import or export starts. It is safe to repeat:
 * - It never deletes project, photo or export rows.
 * - Files younger than [StorageLayout.IN_FLIGHT_GRACE_MS] are kept, so an operation that is still running is not disturbed.
 * - Only files that match TimeFlip's own names are touched. Other files are left alone.
 */
class StorageRecovery(
    private val rootDir: File,
    private val cacheDir: File,
    private val db: TimeFlipDatabase,
) {

    suspend fun recover(): RecoveryReport = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val photoRows = db.photoDao().all()
        val referencedPhotoIds = photoRows.mapTo(HashSet()) { it.id }
        val referencedExports = db.exportDao().allFileNames().toHashSet()

        val photosDir = StorageLayout.photosDir(rootDir)
        val exportsDir = StorageLayout.exportsDir(rootDir)
        val thumbnailsDir = StorageLayout.thumbnailsDir(cacheDir)

        var tempRemoved = 0
        var unreferencedRemoved = 0
        var staleThumbnails = 0

        for (file in photosDir.listFiles().orEmpty()) {
            if (!file.isFile || !isOlderThan(file, now, StorageLayout.IN_FLIGHT_GRACE_MS)) continue
            val name = file.name
            if (name.endsWith(StorageLayout.TEMP_SUFFIX)) {
                if (file.delete()) tempRemoved++
            } else if (name.endsWith(StorageLayout.PHOTO_EXTENSION)) {
                val id = name.removeSuffix(StorageLayout.PHOTO_EXTENSION)
                if (StorageLayout.isValidPhotoId(id) && id !in referencedPhotoIds && file.delete()) unreferencedRemoved++
            }
        }

        for (file in exportsDir.listFiles().orEmpty()) {
            if (!file.isFile || !isOlderThan(file, now, StorageLayout.IN_FLIGHT_GRACE_MS)) continue
            if (file.name.endsWith(StorageLayout.TEMP_SUFFIX)) {
                if (file.delete()) tempRemoved++
            } else if (file.name !in referencedExports) {
                if (file.delete()) unreferencedRemoved++
            }
        }

        for (file in thumbnailsDir.listFiles().orEmpty()) {
            if (!file.isFile) continue
            val sourceId = StorageLayout.thumbnailSourceId(file.name)
            val expired = when {
                file.name.endsWith(StorageLayout.TEMP_SUFFIX) -> isOlderThan(file, now, StorageLayout.IN_FLIGHT_GRACE_MS)
                sourceId == null -> false
                else -> isOlderThan(file, now, THUMBNAIL_MAX_AGE_MS) || !copyExists(photosDir, sourceId)
            }
            if (expired && file.delete()) staleThumbnails++
        }

        val missing = photoRows
            .filter { !copyExists(photosDir, it.id) }
            .map { row ->
                MissingPhoto(
                    projectId = ProjectId(row.projectId),
                    photoId = PhotoId(row.id),
                    slot = parseEnum<PhotoSlot>(row.slot, "photos.slot"),
                )
            }

        RecoveryReport(
            tempFilesRemoved = tempRemoved,
            unreferencedFilesRemoved = unreferencedRemoved,
            staleThumbnailsRemoved = staleThumbnails,
            missingPhotos = missing,
        )
    }

    private fun copyExists(photosDir: File, photoId: String): Boolean =
        StorageLayout.isValidPhotoId(photoId) && File(photosDir, photoId + StorageLayout.PHOTO_EXTENSION).isFile

    private fun isOlderThan(file: File, nowMs: Long, ageMs: Long): Boolean = nowMs - file.lastModified() > ageMs

    companion object {
        /** Cached thumbnails are regenerated on demand, so an old one is safe to drop. */
        const val THUMBNAIL_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
