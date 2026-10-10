package com.agency.timeflip.domain.port

import com.agency.timeflip.domain.model.ExportedVideo
import com.agency.timeflip.domain.model.ProjectId
import com.agency.timeflip.domain.model.ProjectSummary
import com.agency.timeflip.domain.model.TimeFlipProject
import kotlinx.coroutines.flow.Flow

/**
 * Durable project library. Implementations must survive process death, keep edits that were
 * saved before the call returned, and throw [com.agency.timeflip.domain.error.TimeFlipException]
 * for known failures.
 */
interface ProjectRepository {
    /** Library rows, most recently updated first. Emits again after every change. */
    fun observeProjects(): Flow<List<ProjectSummary>>

    /** Returns null when the project does not exist. */
    suspend fun getProject(id: ProjectId): TimeFlipProject?

    /** Inserts or replaces the project. [TimeFlipProject.updatedAtEpochMs] is stored as given. */
    suspend fun saveProject(project: TimeFlipProject)

    /** Removes the project, its photo copies, thumbnails and exported files. Unknown ids are ignored. */
    suspend fun deleteProject(id: ProjectId)

    /** Copies the project and its photos under a new id and returns that id. */
    suspend fun duplicateProject(id: ProjectId, newId: ProjectId, nowEpochMs: Long): ProjectId

    suspend fun recordExport(video: ExportedVideo)

    /** Exports for one project, newest first. */
    suspend fun listExports(id: ProjectId): List<ExportedVideo>

    /** Removes every project, photo copy, thumbnail and export. Used by "Delete all data". */
    suspend fun deleteAllData()
}
