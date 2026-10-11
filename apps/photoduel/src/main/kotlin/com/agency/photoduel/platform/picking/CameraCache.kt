package com.agency.photoduel.platform.picking

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * The only place a camera photo ever touches storage: `cacheDir/camera/capture-<uuid>.jpg`, shared with
 * the system camera app through the non-exported FileProvider (res/xml/file_paths.xml).
 *
 * The file is deleted when processing finishes ([deleteIfOwned], called by
 * [CameraCleanupPhotoProcessing]), when a capture is cancelled, and by an age based sweep for
 * anything left behind by a crash or process death.
 */
class CameraCache(
    private val context: Context,
    private val authority: String,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** A file the camera app may write, and the content URI that grants it access. */
    class Target(val file: File, val uri: Uri)

    private val directory: File get() = File(context.cacheDir, DIRECTORY)

    /** True when some app can handle ACTION_IMAGE_CAPTURE. Needs the `<queries>` entry on API 30+. */
    fun isCameraAppAvailable(packageManager: PackageManager): Boolean =
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(packageManager) != null

    /** Creates an empty target file, or returns null when the cache cannot be written. */
    fun createTarget(): Target? {
        return try {
            purgeStale()
            val dir = directory
            if (!dir.isDirectory && !dir.mkdirs()) return null
            val file = File(dir, "$FILE_PREFIX${UUID.randomUUID()}$FILE_SUFFIX")
            if (!file.createNewFile()) return null
            Target(file, FileProvider.getUriForFile(context, authority, file))
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    fun discard(target: Target) {
        try {
            target.file.delete()
        } catch (e: SecurityException) {
            // Nothing else to do; the age sweep removes it later.
        }
    }

    /**
     * Deletes the capture behind [uri] if, and only if, this cache created it. Any other URI (picker
     * results, other apps' content) is left untouched. Returns true when a file was deleted.
     */
    fun deleteIfOwned(uri: Uri): Boolean {
        if (uri.scheme != "content" || uri.authority != authority) return false
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != URI_NAME) return false
        val name = segments[1]
        if (!isCaptureFileName(name)) return false
        return try {
            File(directory, name).delete()
        } catch (e: SecurityException) {
            false
        }
    }

    /** Removes capture files older than [maxAgeMillis]. Safe to call from any thread. */
    fun purgeStale(maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS) {
        try {
            val cutoff = nowMillis() - maxAgeMillis
            directory.listFiles()?.forEach { file ->
                if (isCaptureFileName(file.name) && file.lastModified() < cutoff) file.delete()
            }
        } catch (e: SecurityException) {
            // Best effort.
        }
    }

    companion object {
        /** Directory under cacheDir. Must match `<cache-path path="camera/">` in file_paths.xml. */
        const val DIRECTORY = "camera"

        /** The `name` attribute of that cache-path, which is the first segment of every content URI. */
        const val URI_NAME = "camera"

        private const val FILE_PREFIX = "capture-"
        private const val FILE_SUFFIX = ".jpg"
        private const val DEFAULT_MAX_AGE_MILLIS = 60L * 60L * 1000L
        private val NAME_PATTERN = Regex("capture-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg")

        internal fun isCaptureFileName(name: String): Boolean = NAME_PATTERN.matches(name)
    }
}
