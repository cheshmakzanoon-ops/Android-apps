package com.agency.timeflip.domain.testing

import com.agency.timeflip.domain.error.ErrorCategory
import com.agency.timeflip.domain.error.TimeFlipError
import com.agency.timeflip.domain.error.TimeFlipException
import com.agency.timeflip.domain.image.GrayRaster
import com.agency.timeflip.domain.model.AlignmentEstimate
import com.agency.timeflip.domain.model.AlignmentStatus
import com.agency.timeflip.domain.model.AlignmentTransform
import com.agency.timeflip.domain.model.ExportProgress
import com.agency.timeflip.domain.model.ExportRequest
import com.agency.timeflip.domain.model.ExportedVideo
import com.agency.timeflip.domain.model.PhotoId
import com.agency.timeflip.domain.model.PhotoSlot
import com.agency.timeflip.domain.model.PhotoSourceRef
import com.agency.timeflip.domain.model.PixelSize
import com.agency.timeflip.domain.model.ProjectId
import com.agency.timeflip.domain.model.ProjectSummary
import com.agency.timeflip.domain.model.SourcePhoto
import com.agency.timeflip.domain.model.TimeFlipProject
import com.agency.timeflip.domain.port.AlignmentEngine
import com.agency.timeflip.domain.port.PhotoStore
import com.agency.timeflip.domain.port.ProjectRepository
import com.agency.timeflip.domain.port.VideoExportEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** Deterministic in-memory repository for previews, ViewModel tests and the UI. */
class InMemoryProjectRepository : ProjectRepository {
    private val projects = MutableStateFlow<Map<ProjectId, TimeFlipProject>>(emptyMap())
    private val exports = MutableStateFlow<List<ExportedVideo>>(emptyList())

    override fun observeProjects(): Flow<List<ProjectSummary>> =
        projects.asStateFlow().map { map ->
            map.values
                .sortedByDescending { it.updatedAtEpochMs }
                .map { project ->
                    ProjectSummary(
                        id = project.id,
                        name = project.name,
                        updatedAtEpochMs = project.updatedAtEpochMs,
                        exportCount = exports.value.count { it.projectId == project.id },
                        thumbnailPhotoId = project.pair.after.id,
                    )
                }
        }

    override suspend fun getProject(id: ProjectId): TimeFlipProject? = projects.value[id]

    override suspend fun saveProject(project: TimeFlipProject) {
        projects.value = projects.value + (project.id to project)
    }

    override suspend fun deleteProject(id: ProjectId) {
        projects.value = projects.value - id
        exports.value = exports.value.filterNot { it.projectId == id }
    }

    override suspend fun duplicateProject(id: ProjectId, newId: ProjectId, nowEpochMs: Long): ProjectId {
        val source = projects.value[id] ?: throw TimeFlipException(TimeFlipError(ErrorCategory.NOT_FOUND))
        projects.value = projects.value + (newId to source.copy(id = newId, name = "${source.name} copy", updatedAtEpochMs = nowEpochMs, createdAtEpochMs = nowEpochMs))
        return newId
    }

    override suspend fun recordExport(video: ExportedVideo) {
        exports.value = listOf(video) + exports.value
    }

    override suspend fun listExports(id: ProjectId): List<ExportedVideo> =
        exports.value.filter { it.projectId == id }

    override suspend fun deleteAllData() {
        projects.value = emptyMap()
        exports.value = emptyList()
    }
}

/** Returns a fixed size for every photograph, so UI and tests need no image files. */
class FakePhotoStore(private val size: PixelSize = PixelSize(1200, 1600)) : PhotoStore {
    private var counter = 0
    private val paths = mutableMapOf<PhotoId, String>()

    override suspend fun importPhoto(source: PhotoSourceRef, slot: PhotoSlot, nowEpochMs: Long): SourcePhoto {
        if (source.value.isBlank()) throw TimeFlipException(TimeFlipError(ErrorCategory.UNSUPPORTED_FORMAT))
        val id = PhotoId("fake-photo-${++counter}")
        paths[id] = "/fake/${id.value}.jpg"
        return SourcePhoto(id = id, slot = slot, size = size, byteSize = 1024L, importedAtEpochMs = nowEpochMs)
    }

    override suspend fun originalPath(id: PhotoId): String? = paths[id]

    override suspend fun thumbnailPath(id: PhotoId, maxEdge: Int): String? = paths[id]

    override suspend fun delete(id: PhotoId) {
        paths.remove(id)
    }
}

/** Returns a fixed estimate; use [status] to exercise the manual-fallback path. */
class FakeAlignmentEngine(
    private val transform: AlignmentTransform = AlignmentTransform(offsetX = 0.02, offsetY = -0.01),
    private val confidence: Double = 0.85,
) : AlignmentEngine {
    override suspend fun estimate(before: GrayRaster, after: GrayRaster, frame: PixelSize): AlignmentEstimate {
        val status = if (confidence >= AlignmentEstimate.ACCEPT_CONFIDENCE) AlignmentStatus.ACCEPTED else AlignmentStatus.NEEDS_MANUAL
        return AlignmentEstimate(transform, confidence, inlierCount = 40, status = status)
    }
}

/** Emits progress for every frame and then Finished with a synthetic file record. */
class FakeVideoExportEngine(private val fileNamePrefix: String = "fake-export") : VideoExportEngine {
    override fun export(request: ExportRequest): Flow<ExportProgress> = flow {
        val total = request.timeline.frameCount
        for (frame in 0 until total) {
            emit(ExportProgress.Rendering(framesDone = frame + 1, framesTotal = total))
        }
        emit(
            ExportProgress.Finished(
                ExportedVideo(
                    projectId = request.projectId,
                    fileName = "$fileNamePrefix-${request.projectId.value}.mp4",
                    sizeBytes = total * 1000L,
                    durationMs = request.timeline.totalDurationMs,
                    width = request.profile.preset.width,
                    height = request.profile.preset.height,
                    fps = request.profile.fps,
                    createdAtEpochMs = 0L,
                ),
            ),
        )
    }
}
