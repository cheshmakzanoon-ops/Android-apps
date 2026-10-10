package com.agency.timeflip.data

import com.agency.timeflip.domain.model.PhotoId
import java.io.File

/**
 * Locations and file-name rules for app-private media. Every path the data layer touches is built
 * here, so deletion, recovery and temp-file rules live in one place.
 *
 * Layout under the root directory (filesDir/timeflip in the app):
 *   photos/<photoId>.jpg          oriented copies of imported photographs
 *   exports/<fileName>            finished MP4 files
 * Under the cache directory:
 *   thumbs/<photoId>-<maxEdge>.jpg  regenerable thumbnails
 */
internal object StorageLayout {
    const val PHOTOS_DIR = "photos"
    const val EXPORTS_DIR = "exports"
    const val THUMBNAILS_DIR = "thumbs"
    const val PHOTO_EXTENSION = ".jpg"

    /** Any file with this suffix is an unfinished write and is removed by recovery. */
    const val TEMP_SUFFIX = ".tmp"

    /** Partial MP4 written by the export engine. Recovery treats it as unfinished. */
    const val PARTIAL_EXPORT_SUFFIX = ".part"

    fun isUnfinished(fileName: String): Boolean =
        fileName.endsWith(TEMP_SUFFIX) || fileName.endsWith(PARTIAL_EXPORT_SUFFIX)

    /**
     * Temp files and unreferenced copies younger than this may belong to an operation that is still
     * running, so recovery keeps them.
     */
    const val IN_FLIGHT_GRACE_MS = 10L * 60 * 1000

    private val photoIdPattern = Regex("[A-Za-z0-9_-]{1,64}")
    private val exportNamePattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val thumbnailNamePattern = Regex("([A-Za-z0-9_-]{1,64})-(\\d{1,5})\\.jpg")

    fun photosDir(rootDir: File): File = File(rootDir, PHOTOS_DIR)

    fun exportsDir(rootDir: File): File = File(rootDir, EXPORTS_DIR)

    fun thumbnailsDir(cacheDir: File): File = File(cacheDir, THUMBNAILS_DIR)

    /** Ids come from UUIDs, but rows are read back from disk, so they are checked before use in a path. */
    fun isValidPhotoId(value: String): Boolean = photoIdPattern.matches(value)

    /** Copy of a photograph, or null when the id cannot be a file name. */
    fun photoFile(rootDir: File, id: PhotoId): File? =
        if (isValidPhotoId(id.value)) File(photosDir(rootDir), id.value + PHOTO_EXTENSION) else null

    /** Exported file, or null when the stored name could escape the exports directory. */
    fun exportFile(rootDir: File, fileName: String): File? =
        if (exportNamePattern.matches(fileName)) File(exportsDir(rootDir), fileName) else null

    /** Photo id a thumbnail file was generated from, or null when the name is not a thumbnail name. */
    fun thumbnailSourceId(fileName: String): String? =
        thumbnailNamePattern.matchEntire(fileName)?.groupValues?.get(1)

    fun thumbnailFile(thumbnailsDir: File, id: PhotoId, maxEdge: Int): File =
        File(thumbnailsDir, "${id.value}-$maxEdge.jpg")

    /**
     * Removes the copy and every cached thumbnail of [id]. Matches the exact id, so "a" never
     * touches the thumbnails of "a-b". Missing files are ignored.
     */
    fun deletePhotoArtifacts(rootDir: File, thumbnailsDir: File?, id: PhotoId) {
        photoFile(rootDir, id)?.delete()
        if (thumbnailsDir == null) return
        thumbnailsDir.listFiles().orEmpty()
            .filter { it.isFile && thumbnailSourceId(it.name) == id.value }
            .forEach { it.delete() }
    }
}
