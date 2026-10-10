package com.agency.timeflip.data.db

import com.agency.timeflip.data.corruptRow
import com.agency.timeflip.domain.model.AlignmentSource
import com.agency.timeflip.domain.model.AlignmentTransform
import com.agency.timeflip.domain.model.Easing
import com.agency.timeflip.domain.model.ExportProfile
import com.agency.timeflip.domain.model.ExportedVideo
import com.agency.timeflip.domain.model.ImagePair
import com.agency.timeflip.domain.model.OutputPreset
import com.agency.timeflip.domain.model.PhotoId
import com.agency.timeflip.domain.model.PhotoSlot
import com.agency.timeflip.domain.model.PixelSize
import com.agency.timeflip.domain.model.ProjectId
import com.agency.timeflip.domain.model.ProjectSummary
import com.agency.timeflip.domain.model.SourcePhoto
import com.agency.timeflip.domain.model.TimeFlipProject
import com.agency.timeflip.domain.model.TransitionDefinition
import com.agency.timeflip.domain.model.TransitionKind
import com.agency.timeflip.domain.model.TransitionParameters

// Entity <-> domain mapping. Nothing here touches Android APIs, so it is tested on the JVM.
// Rows that violate domain rules are reported as INVALID_PROJECT, never as a raw exception.

internal fun TimeFlipProject.toProjectEntity(): ProjectEntity = ProjectEntity(
    id = id.value,
    name = name,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    scale = alignment.scale,
    rotationDegrees = alignment.rotationDegrees,
    offsetX = alignment.offsetX,
    offsetY = alignment.offsetY,
    alignmentSource = alignmentSource.name,
    alignmentConfidence = alignmentConfidence,
    transitionKind = transition.kind.name,
    transitionDurationMs = transition.parameters.durationMs,
    easing = transition.parameters.easing.name,
    reversed = transition.parameters.reversed,
    wipeAngleDegrees = transition.parameters.wipeAngleDegrees,
    preset = exportProfile.preset.name,
    fps = exportProfile.fps,
)

internal fun TimeFlipProject.toPhotoEntities(): List<PhotoEntity> =
    listOf(pair.before.toPhotoEntity(id), pair.after.toPhotoEntity(id))

internal fun SourcePhoto.toPhotoEntity(projectId: ProjectId): PhotoEntity = PhotoEntity(
    id = id.value,
    projectId = projectId.value,
    slot = slot.name,
    width = size.width,
    height = size.height,
    byteSize = byteSize,
    importedAtEpochMs = importedAtEpochMs,
)

internal fun PhotoEntity.toSourcePhoto(): SourcePhoto = domainRow {
    SourcePhoto(
        id = PhotoId(id),
        slot = parseEnum<PhotoSlot>(slot, "photos.slot"),
        size = PixelSize(width, height),
        byteSize = byteSize,
        importedAtEpochMs = importedAtEpochMs,
    )
}

/** Assembles a project from its row and exactly one BEFORE and one AFTER photo row. */
internal fun ProjectEntity.toDomain(photos: List<PhotoEntity>): TimeFlipProject {
    val beforeRow = photos.singleOrNull { it.slot == PhotoSlot.BEFORE.name }
        ?: throw corruptRow("project has no single BEFORE photo row")
    val afterRow = photos.singleOrNull { it.slot == PhotoSlot.AFTER.name }
        ?: throw corruptRow("project has no single AFTER photo row")
    return domainRow {
        TimeFlipProject(
            id = ProjectId(id),
            name = name,
            pair = ImagePair(before = beforeRow.toSourcePhoto(), after = afterRow.toSourcePhoto()),
            alignment = AlignmentTransform(
                scale = scale,
                rotationDegrees = rotationDegrees,
                offsetX = offsetX,
                offsetY = offsetY,
            ),
            alignmentSource = parseEnum<AlignmentSource>(alignmentSource, "projects.alignmentSource"),
            alignmentConfidence = alignmentConfidence,
            transition = TransitionDefinition(
                kind = parseEnum<TransitionKind>(transitionKind, "projects.transitionKind"),
                parameters = TransitionParameters(
                    durationMs = transitionDurationMs,
                    easing = parseEnum<Easing>(easing, "projects.easing"),
                    reversed = reversed,
                    wipeAngleDegrees = wipeAngleDegrees,
                ),
            ),
            exportProfile = ExportProfile(
                preset = parseEnum<OutputPreset>(preset, "projects.preset"),
                fps = fps,
            ),
            createdAtEpochMs = createdAtEpochMs,
            updatedAtEpochMs = updatedAtEpochMs,
        )
    }
}

internal fun ProjectSummaryRow.toDomain(): ProjectSummary = domainRow {
    ProjectSummary(
        id = ProjectId(id),
        name = name,
        updatedAtEpochMs = updatedAtEpochMs,
        exportCount = exportCount,
        thumbnailPhotoId = thumbnailPhotoId?.let { PhotoId(it) },
    )
}

internal fun ExportedVideo.toExportEntity(): ExportEntity = ExportEntity(
    fileName = fileName,
    projectId = projectId.value,
    sizeBytes = sizeBytes,
    durationMs = durationMs,
    width = width,
    height = height,
    fps = fps,
    createdAtEpochMs = createdAtEpochMs,
)

internal fun ExportEntity.toDomain(): ExportedVideo = domainRow {
    ExportedVideo(
        projectId = ProjectId(projectId),
        fileName = fileName,
        sizeBytes = sizeBytes,
        durationMs = durationMs,
        width = width,
        height = height,
        fps = fps,
        createdAtEpochMs = createdAtEpochMs,
    )
}

/** Resolves a stored enum name. Unknown names are a database inconsistency, not a default value. */
internal inline fun <reified E : Enum<E>> parseEnum(raw: String, column: String): E =
    enumValues<E>().firstOrNull { it.name == raw }
        ?: throw corruptRow("unknown value in column $column")

/** Turns domain-invariant failures in stored values into INVALID_PROJECT. */
internal inline fun <T> domainRow(block: () -> T): T =
    try {
        block()
    } catch (error: IllegalArgumentException) {
        throw corruptRow("stored value rejected by domain rules", error)
    }
