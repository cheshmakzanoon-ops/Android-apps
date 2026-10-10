package com.agency.timeflip.data

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.agency.timeflip.data.db.ProjectSummaryRow
import com.agency.timeflip.data.db.TimeFlipDatabase
import com.agency.timeflip.data.db.toDomain
import com.agency.timeflip.data.db.toExportEntity
import com.agency.timeflip.data.db.toPhotoEntities
import com.agency.timeflip.data.db.toProjectEntity
import com.agency.timeflip.domain.error.ErrorCategory
import com.agency.timeflip.domain.error.TimeFlipError
import com.agency.timeflip.domain.error.TimeFlipException
import com.agency.timeflip.domain.model.ExportedVideo
import com.agency.timeflip.domain.model.ImagePair
import com.agency.timeflip.domain.model.PhotoId
import com.agency.timeflip.domain.model.ProjectId
import com.agency.timeflip.domain.model.ProjectSummary
import com.agency.timeflip.domain.model.SourcePhoto
import com.agency.timeflip.domain.model.TimeFlipProject
import com.agency.timeflip.domain.port.ProjectRepository
import com.agency.timeflip.domain.rules.ProjectRules
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Room-backed project library.
 *
 * [rootDir] must be dedicated to TimeFlip data (the app uses filesDir/timeflip). [cacheDir] is the
 * application cache directory. Pass it so thumbnails are removed with their photos and by
 * [deleteAllData]. Without it, thumbnails are not removed by this repository.
 */
class RoomProjectRepository(
    private val db: TimeFlipDatabase,
    private val rootDir: File,
    private val cacheDir: File? = null,
) : ProjectRepository {

    private val photosDir = StorageLayout.photosDir(rootDir)
    private val thumbnailsDir: File? = cacheDir?.let { StorageLayout.thumbnailsDir(it) }

    override fun observeProjects(): Flow<List<ProjectSummary>> =
        db.projectDao().observeSummaries().map { rows: List<ProjectSummaryRow> -> rows.map { it.toDomain() } }

    override suspend fun getProject(id: ProjectId): TimeFlipProject? = storage {
        loadProject(id)
    }

    override suspend fun saveProject(project: TimeFlipProject) {
        storage {
            db.withTransaction {
                val projectRow = project.toProjectEntity()
                val projects = db.projectDao()
                // Insert-if-absent then update. REPLACE would delete the parent row and cascade to its photos and exports.
                if (projects.insertIfAbsent(projectRow) == -1L && projects.update(projectRow) != 1) {
                    throw corruptRow("project row changed during save")
                }
                // Photo rows are replaced wholesale. Swapping slots or replacing a photo would otherwise
                // collide on the unique (projectId, slot) index. Photo rows have no dependants, so this is safe.
                db.photoDao().deleteForProject(project.id.value)
                db.photoDao().insertAll(project.toPhotoEntities())
            }
        }
    }

    override suspend fun deleteProject(id: ProjectId) {
        val removed: RemovedFiles = storage {
            db.withTransaction {
                val photoIds = db.photoDao().forProject(id.value).map { it.id }
                val exportNames = db.exportDao().fileNamesForProject(id.value)
                // Deleting the project cascades to its photo and export rows.
                db.projectDao().delete(id.value)
                RemovedFiles(photoIds = photoIds, exportNames = exportNames)
            }
        }
        // Only reached when the transaction committed. A failed delete keeps every file.
        withContext(Dispatchers.IO) { removeFiles(removed) }
    }

    override suspend fun duplicateProject(id: ProjectId, newId: ProjectId, nowEpochMs: Long): ProjectId {
        val createdFiles = mutableListOf<File>()
        try {
            storage {
                val source = loadProject(id) ?: throw TimeFlipException(TimeFlipError(ErrorCategory.NOT_FOUND))
                val copy = source.copy(
                    id = newId,
                    name = ProjectRules.normalizeName("${source.name} copy"),
                    pair = ImagePair(
                        before = copyPhotoFile(source.pair.before, createdFiles),
                        after = copyPhotoFile(source.pair.after, createdFiles),
                    ),
                    createdAtEpochMs = nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                )
                db.withTransaction {
                    db.projectDao().insert(copy.toProjectEntity())
                    db.photoDao().insertAll(copy.toPhotoEntities())
                }
            }
        } catch (error: Throwable) {
            // The rows did not commit, so the new copies are unreferenced. Remove them now, not at the next start.
            withContext(Dispatchers.IO) { createdFiles.forEach { it.delete() } }
            throw error
        }
        return newId
    }

    override suspend fun recordExport(video: ExportedVideo) {
        storage {
            db.exportDao().insert(video.toExportEntity())
        }
    }

    override suspend fun listExports(id: ProjectId): List<ExportedVideo> = storage {
        db.exportDao().forProject(id.value).map { it.toDomain() }
    }

    override suspend fun deleteAllData() {
        storage {
            db.withTransaction {
                db.exportDao().deleteAll()
                db.photoDao().deleteAll()
                db.projectDao().deleteAll()
            }
        }
        withContext(Dispatchers.IO) {
            rootDir.deleteRecursively()
            thumbnailsDir?.deleteRecursively()
        }
        compactDatabase()
    }

    private suspend fun loadProject(id: ProjectId): TimeFlipProject? {
        val row = db.projectDao().find(id.value) ?: return null
        return row.toDomain(db.photoDao().forProject(id.value))
    }

    /** Copies one photo file under a fresh id. A photo whose copy is already missing is copied as a row only. */
    private fun copyPhotoFile(photo: SourcePhoto, createdFiles: MutableList<File>): SourcePhoto {
        val newId = PhotoId(UUID.randomUUID().toString())
        val source = StorageLayout.photoFile(rootDir, photo.id)?.takeIf { it.isFile }
        if (source != null) {
            val destination = File(photosDir, newId.value + StorageLayout.PHOTO_EXTENSION)
            val temp = File(photosDir, destination.name + StorageLayout.TEMP_SUFFIX)
            try {
                source.copyTo(temp)
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally {
                temp.delete()
            }
            createdFiles += destination
        }
        return photo.copy(id = newId)
    }

    private fun removeFiles(removed: RemovedFiles) {
        for (value in removed.photoIds) {
            if (StorageLayout.isValidPhotoId(value)) {
                StorageLayout.deletePhotoArtifacts(rootDir, thumbnailsDir, PhotoId(value))
            }
        }
        for (name in removed.exportNames) {
            StorageLayout.exportFile(rootDir, name)?.delete()
        }
    }

    /**
     * Rewrites the database file so deleted rows are not left in free pages, and checkpoints the WAL.
     * Best effort: the rows are already committed as deleted, so a failure here must not look like a failed delete.
     */
    private suspend fun compactDatabase() {
        withContext(Dispatchers.IO) {
            try {
                val sqlite = db.openHelper.writableDatabase
                sqlite.execSQL("VACUUM")
                sqlite.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor -> cursor.moveToFirst() }
            } catch (error: SQLiteException) {
                // Intentionally ignored. See the KDoc above.
            }
        }
    }

    /** Runs a database call on IO and maps platform failures to the categories in [TimeFlipError]. */
    private suspend fun <T> storage(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (error: SQLiteConstraintException) {
            throw corruptRow("database constraint rejected the change", error)
        } catch (error: SQLiteException) {
            if (error.isDiskFull()) throw storageFull("database storage is full", error)
            throw error
        } catch (error: IOException) {
            if (error.isDiskFull()) throw storageFull("device storage is full", error)
            throw error
        }
    }

    private class RemovedFiles(val photoIds: List<String>, val exportNames: List<String>)
}
