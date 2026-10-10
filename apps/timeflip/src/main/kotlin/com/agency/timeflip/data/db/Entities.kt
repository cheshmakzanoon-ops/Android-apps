package com.agency.timeflip.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per saved transformation. Enum-typed columns hold the Kotlin enum name.
 * Alignment and transition values are REAL, so the stored doubles round-trip exactly.
 */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val scale: Double,
    val rotationDegrees: Double,
    val offsetX: Double,
    val offsetY: Double,
    val alignmentSource: String,
    val alignmentConfidence: Double?,
    val transitionKind: String,
    val transitionDurationMs: Int,
    val easing: String,
    val reversed: Boolean,
    val wipeAngleDegrees: Double,
    val preset: String,
    val fps: Int,
)

/**
 * A private photo copy. Each project has exactly one BEFORE and one AFTER row, enforced by the
 * unique (projectId, slot) index. That index starts with projectId, so it also serves the foreign
 * key lookups and any query by project; a separate projectId index would be redundant.
 */
@Entity(
    tableName = "photos",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["projectId", "slot"], unique = true)],
)
data class PhotoEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val slot: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val importedAtEpochMs: Long,
)

/** A finished MP4 recorded for a project. [fileName] is the name inside the private exports directory. */
@Entity(
    tableName = "exports",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["projectId"])],
)
data class ExportEntity(
    @PrimaryKey val fileName: String,
    val projectId: String,
    val sizeBytes: Long,
    val durationMs: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val createdAtEpochMs: Long,
)
