package com.agency.stickerjet.data

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

/**
 * Paths of every file the data layer owns.
 *
 * Project documents and media live under [root], which the app passes as `filesDir/projects` so that
 * no other app can read them. Temporary files live under [tempRoot]. The app should pass a folder
 * inside `cacheDir` there. The default is a sibling of [root] in app-private storage, which is only
 * meant for callers without a cache folder. [LibraryCleaner.clearTemporaryFiles] empties it at launch.
 *
 * Project ids become directory names, so they are limited to `[A-Za-z0-9_-]` with at most 64
 * characters. Any other id is rejected with [IllegalArgumentException].
 *
 * Create one instance and share it with the repository, the import service and the library cleaner.
 * The write lock and the in-flight set belong to the instance.
 */
class ProjectFiles(
    val root: File,
    val tempRoot: File = File(root.absoluteFile.parentFile ?: root, DEFAULT_TEMP_FOLDER),
) {

    /** Serializes document writes and deletions for every user of this instance. */
    internal val writeLock = Mutex()

    /** Ids whose import is still running. Maintenance never removes their directories. */
    internal val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Directory that holds everything owned by project [projectId]. */
    fun dir(projectId: String): File = File(root, checkedId(projectId))

    /** The copied source clip, `source.mp4`. */
    fun sourceFile(projectId: String): File = File(dir(projectId), SOURCE_NAME)

    /** The first-frame preview, `thumbnail.jpg`. Optional, so it may not exist. */
    fun thumbnailFile(projectId: String): File = File(dir(projectId), THUMBNAIL_NAME)

    /** Folder for the exported animations of the project. It is created by the exporter. */
    fun exportsDir(projectId: String): File = File(dir(projectId), EXPORTS_DIR_NAME)

    /** The versioned project document, `project.json`. */
    fun documentFile(projectId: String): File = File(dir(projectId), DOCUMENT_NAME)

    /** Scratch folder inside the cache. Created on first use. */
    fun tempDir(): File {
        tempRoot.mkdirs()
        return tempRoot
    }

    /** Project directories under [root] whose names are valid ids. Empty when [root] does not exist. */
    internal fun projectDirectories(): List<File> =
        root.listFiles { entry -> entry.isDirectory && isValidId(entry.name) }?.toList().orEmpty()

    companion object {
        const val SOURCE_NAME: String = "source.mp4"
        const val THUMBNAIL_NAME: String = "thumbnail.jpg"
        const val EXPORTS_DIR_NAME: String = "exports"
        const val DOCUMENT_NAME: String = "project.json"

        private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

        /** True for ids that are safe to use as directory names. */
        fun isValidId(projectId: String): Boolean = ID_PATTERN.matches(projectId)

        private fun checkedId(projectId: String): String {
            require(isValidId(projectId)) { "Invalid project id" }
            return projectId
        }
    }
}
