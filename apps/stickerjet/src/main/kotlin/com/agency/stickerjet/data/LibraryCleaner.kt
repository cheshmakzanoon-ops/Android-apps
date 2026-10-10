package com.agency.stickerjet.data

import com.agency.stickerjet.domain.ProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Housekeeping for the project library. Every function runs on Dispatchers.IO, and none of them
 * touches the folder of a project whose import is still running.
 */
class LibraryCleaner(
    private val files: ProjectFiles,
    private val repository: ProjectRepository,
) {

    /** Empties the temporary folder. Call at launch, before any export can be running. */
    suspend fun clearTemporaryFiles() {
        withContext(Dispatchers.IO) {
            files.tempDir().listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    /** Deletes the thumbnail of every project. Projects, source clips and exports are kept. */
    suspend fun clearThumbnails() {
        withContext(Dispatchers.IO) {
            for (dir in files.projectDirectories()) {
                if (dir.name !in files.inFlight) files.thumbnailFile(dir.name).delete()
            }
        }
    }

    /**
     * Removes project folders whose document is missing or corrupt. Those are left behind by interrupted
     * imports and deletes. Folders with a newer-version document, or with a document that could not be
     * read, are kept. Returns the number of folders removed.
     */
    suspend fun removeOrphans(): Int = withContext(Dispatchers.IO) {
        var removed = 0
        for (dir in files.projectDirectories()) {
            files.writeLock.withLock {
                if (dir.name !in files.inFlight && isOrphan(dir.name) && dir.deleteRecursively()) {
                    removed++
                }
            }
        }
        removed
    }

    /** Deletes every project and every temporary file. Projects that are still importing are kept. */
    suspend fun deleteEverything() {
        for (project in repository.list()) {
            repository.delete(project.id)
        }
        withContext(Dispatchers.IO) {
            files.writeLock.withLock {
                files.root.listFiles()?.forEach { entry ->
                    if (entry.name !in files.inFlight) entry.deleteRecursively()
                }
            }
        }
        clearTemporaryFiles()
    }

    private fun isOrphan(id: String): Boolean =
        when (readDocument(files, ProjectEnvelope.createJson(), id)) {
            DocumentState.Missing, DocumentState.Corrupt -> true
            is DocumentState.Readable, is DocumentState.NewerSchema, DocumentState.IoError -> false
        }
}
