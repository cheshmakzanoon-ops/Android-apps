package com.agency.timeflip.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** One library row as read by the summary query. Column aliases match these property names. */
data class ProjectSummaryRow(
    val id: String,
    val name: String,
    val updatedAtEpochMs: Long,
    val exportCount: Int,
    val thumbnailPhotoId: String?,
)

/**
 * Never use REPLACE or @Upsert for projects. REPLACE deletes the parent row first, which cascades to
 * photos and exports. @Upsert swallows constraint failures, so the repository uses an explicit
 * insert-if-absent followed by an update instead.
 */
@Dao
interface ProjectDao {
    @Insert
    suspend fun insert(project: ProjectEntity)

    /** Returns -1 when a row with the same id already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(project: ProjectEntity): Long

    /** Returns the number of rows updated. */
    @Update
    suspend fun update(project: ProjectEntity): Int

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun find(id: String): ProjectEntity?

    /** Deleting the project cascades to its photos and exports. */
    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM projects")
    suspend fun deleteAll()

    @Query(
        """
        SELECT p.id AS id,
               p.name AS name,
               p.updatedAtEpochMs AS updatedAtEpochMs,
               (SELECT COUNT(*) FROM exports AS e WHERE e.projectId = p.id) AS exportCount,
               (SELECT ph.id FROM photos AS ph WHERE ph.projectId = p.id AND ph.slot = 'AFTER') AS thumbnailPhotoId
        FROM projects AS p
        ORDER BY p.updatedAtEpochMs DESC, p.id ASC
        """,
    )
    fun observeSummaries(): Flow<List<ProjectSummaryRow>>
}

@Dao
interface PhotoDao {
    /** Plain insert, so a duplicate (projectId, slot) or id fails loudly. */
    @Insert
    suspend fun insertAll(photos: List<PhotoEntity>)

    @Query("DELETE FROM photos WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: String)

    @Query("DELETE FROM photos")
    suspend fun deleteAll()

    @Query("SELECT * FROM photos WHERE projectId = :projectId")
    suspend fun forProject(projectId: String): List<PhotoEntity>

    @Query("SELECT * FROM photos")
    suspend fun all(): List<PhotoEntity>
}

@Dao
interface ExportDao {
    /**
     * REPLACE is safe here because exports have no children. A missing project still fails
     * with a foreign key error, because REPLACE only resolves unique and primary key conflicts.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(export: ExportEntity)

    @Query("SELECT * FROM exports WHERE projectId = :projectId ORDER BY createdAtEpochMs DESC, fileName ASC")
    suspend fun forProject(projectId: String): List<ExportEntity>

    @Query("SELECT fileName FROM exports WHERE projectId = :projectId")
    suspend fun fileNamesForProject(projectId: String): List<String>

    @Query("SELECT fileName FROM exports")
    suspend fun allFileNames(): List<String>

    @Query("DELETE FROM exports")
    suspend fun deleteAll()
}
