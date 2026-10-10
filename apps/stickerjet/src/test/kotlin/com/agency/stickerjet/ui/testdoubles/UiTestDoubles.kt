package com.agency.stickerjet.ui.testdoubles

import com.agency.stickerjet.domain.AlphaMask
import com.agency.stickerjet.domain.ExportRecord
import com.agency.stickerjet.domain.ImportRejection
import com.agency.stickerjet.domain.MaskStroke
import com.agency.stickerjet.domain.ProjectRepository
import com.agency.stickerjet.domain.SourceVideo
import com.agency.stickerjet.domain.StickerProject
import com.agency.stickerjet.domain.StickerProjects
import com.agency.stickerjet.ui.ports.ClipImportPort
import com.agency.stickerjet.ui.ports.ExportFailureKind
import com.agency.stickerjet.ui.ports.ExportPort
import com.agency.stickerjet.ui.ports.ExportResult
import com.agency.stickerjet.ui.ports.ImportResult
import com.agency.stickerjet.ui.ports.LibraryMaintenancePort
import com.agency.stickerjet.ui.ports.MaskRefinementPort
import com.agency.stickerjet.ui.ports.PreviewPort
import com.agency.stickerjet.ui.ports.ProjectFilesPort
import com.agency.stickerjet.ui.ports.RenderedPreview
import com.agency.stickerjet.ui.ports.StoragePort
import java.io.File

/** Source-file layout under a temporary root. Files are created by tests when they should exist. */
class TestFiles(private val root: File) : ProjectFilesPort {
    override fun sourceFile(projectId: String): File = File(File(root, projectId), "source.mp4")
    override fun thumbnailFile(projectId: String): File = File(File(root, projectId), "thumbnail.jpg")
    override fun exportsDir(projectId: String): File = File(File(root, projectId), "exports")
}

/** Storage check with a fixed answer. */
class FixedStorage(var hasRoom: Boolean = true) : StoragePort {
    override fun hasRoomFor(bytes: Long): Boolean = hasRoom
}

/** Mask refinement that ignores strokes: an absent base is fully opaque, as in the domain. */
object OpaqueMasks : MaskRefinementPort {
    override fun refine(base: AlphaMask?, width: Int, height: Int, strokes: List<MaskStroke>): AlphaMask =
        base ?: AlphaMask(width, height, ByteArray(width * height) { 0xFF.toByte() })
}

/** Preview port that always fails, so tests can check the failure state without Android bitmaps. */
class FailingPreview : PreviewPort {
    override suspend fun render(project: StickerProject, canvasPx: Int): RenderedPreview =
        throw IllegalStateException("preview disabled in unit tests")
}

/**
 * Exporter that answers with a fixed result. On success it appends an export record to the repository,
 * the way the data layer does, so tests can check that the draft is reloaded with the record.
 */
class ScriptedExporter(
    private val repository: ProjectRepository,
    private val result: (StickerProject) -> ExportResult,
) : ExportPort {
    var calls: Int = 0
        private set

    override suspend fun export(project: StickerProject, onProgress: (Float) -> Unit): ExportResult {
        calls++
        onProgress(0.5f)
        val outcome = result(project)
        if (outcome is ExportResult.Success) {
            repository.save(project.copy(exports = project.exports + outcome.record))
        }
        return outcome
    }

    companion object {
        fun success(repository: ProjectRepository, file: File): ScriptedExporter = ScriptedExporter(repository) { p ->
            ExportResult.Success(
                file = file,
                record = ExportRecord(
                    fileName = file.name,
                    createdAtMs = 1_000L,
                    sizeBytes = 2048L,
                    frameCount = 24,
                    durationMs = p.trim.durationMs,
                ),
                subjectFrames = 0,
                totalFrames = 24,
            )
        }

        fun failure(repository: ProjectRepository, reason: ExportFailureKind): ScriptedExporter =
            ScriptedExporter(repository) { ExportResult.Failed(reason) }
    }
}

/** Importer that returns queued results in order. */
class QueuedImporter(vararg results: ImportResult) : ClipImportPort {
    private val queue = ArrayDeque(results.toList())
    val sources = mutableListOf<String>()

    override suspend fun import(contentUri: String, projectId: String): ImportResult {
        sources += contentUri
        return queue.removeFirst()
    }
}

/** Maintenance port that records calls and can be told to fail. */
class FakeMaintenance(var fail: Boolean = false) : LibraryMaintenancePort {
    var cleared = 0
    var deletedAll = 0

    override suspend fun clearTemporaryFiles() {
        if (fail) throw IllegalStateException("disk error")
        cleared++
    }

    override suspend fun deleteEverything() {
        if (fail) throw IllegalStateException("disk error")
        deletedAll++
    }
}

/** A project for tests: a 10 s source with the default two-second window. */
fun testProject(id: String, title: String = "Clip", updatedAtMs: Long = 1_000L): StickerProject =
    StickerProjects.create(
        id = id,
        source = SourceVideo(
            fileName = "source.mp4",
            durationMs = 10_000L,
            width = 480,
            height = 360,
            rotationDegrees = 0,
            sizeBytes = 4_096L,
        ),
        displayName = "$title.mp4",
        nowMs = updatedAtMs,
    )

/** Keeps the rejection reason visible in test names without depending on resources. */
fun ImportRejection.label(): String = name
