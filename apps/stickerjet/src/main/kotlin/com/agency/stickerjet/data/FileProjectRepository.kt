package com.agency.stickerjet.data

import com.agency.stickerjet.domain.ProjectRepository
import com.agency.stickerjet.domain.StickerProject
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Result of [FileProjectRepository.scan]. The library screen does not use it: it lists projects through
 * [ProjectRepository.list] and shows no skipped count. It is public so the count the data spec requires
 * is observable by diagnostics and tests.
 */
data class ProjectScan(
    /** Readable projects, most recently updated first. */
    val projects: List<StickerProject>,
    /**
     * Folders that were not listed: corrupt or unreadable documents, documents from a newer app
     * version, and folders with no document at all.
     */
    val skipped: Int,
)

/**
 * [ProjectRepository] that stores each project as `<root>/<id>/project.json`, inside the versioned
 * envelope described by [ProjectEnvelope].
 *
 * Writes are atomic, so a crash leaves either the previous document or the new one. Deleting a project
 * removes its document first. An interrupted delete therefore leaves a folder with no document, and
 * [LibraryCleaner.removeOrphans] reclaims it later. Damaged documents are skipped by [list] and [load]
 * and are counted by [scan]. Reads never rewrite a document.
 *
 * [json] must ignore unknown keys. [ProjectEnvelope.createJson] is the configuration the app uses.
 *
 * [clock] is accepted to match the composition root. The repository writes no timestamps of its own,
 * because the domain sets `createdAtMs` and `updatedAtMs`, so the clock is not used yet.
 */
class FileProjectRepository(
    private val files: ProjectFiles,
    private val json: Json,
    @Suppress("unused") private val clock: () -> Long,
) : ProjectRepository {

    /**
     * Every readable project, sorted by [StickerProject.updatedAtMs] descending, so the most recently
     * edited project comes first. Ties keep folder-name order. Damaged projects are left out.
     */
    override suspend fun list(): List<StickerProject> = scan().projects

    /** The project with [id], or null when it is missing, damaged, or the id is not valid. */
    override suspend fun load(id: String): StickerProject? = withContext(Dispatchers.IO) {
        if (!ProjectFiles.isValidId(id)) return@withContext null
        (readDocument(files, json, id) as? DocumentState.Readable)?.project
    }

    /**
     * Reads every project folder and counts the ones that are skipped. Sorted like [list]. The UI does
     * not call this; [list] gives it the same projects without the count.
     */
    suspend fun scan(): ProjectScan = withContext(Dispatchers.IO) {
        val projects = ArrayList<StickerProject>()
        var skipped = 0
        for (dir in files.projectDirectories().sortedBy { it.name }) {
            if (dir.name in files.inFlight) continue
            val state = readDocument(files, json, dir.name)
            if (state is DocumentState.Readable) {
                projects += state.project
            } else {
                skipped++
            }
        }
        ProjectScan(projects.sortedByDescending { it.updatedAtMs }, skipped)
    }

    /** Writes the envelope for [project] atomically. Throws [IOException] when the write fails. */
    override suspend fun save(project: StickerProject) {
        val text = ProjectEnvelope.encode(json, project)
        withContext(Dispatchers.IO) {
            files.writeLock.withLock {
                writeTextAtomically(files.documentFile(project.id), text)
            }
        }
    }

    /**
     * Removes the document, then the whole project folder: exports, thumbnail and source clip. Unknown
     * ids are a no-op. Throws [IOException] when a file cannot be removed, which the caller can retry.
     */
    override suspend fun delete(id: String) {
        withContext(Dispatchers.IO) {
            files.writeLock.withLock {
                val document = files.documentFile(id)
                if (document.exists() && !document.delete()) {
                    throw IOException("Cannot remove the project document")
                }
                val dir = files.dir(id)
                if (dir.exists() && !dir.deleteRecursively()) {
                    throw IOException("Cannot remove the project files")
                }
            }
        }
    }
}
