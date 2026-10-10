package com.agency.stickerjet.app

import android.net.Uri
import com.agency.stickerjet.data.ImportService
import com.agency.stickerjet.data.LibraryCleaner
import com.agency.stickerjet.data.OnboardingStore
import com.agency.stickerjet.data.ProjectFiles
import com.agency.stickerjet.data.StorageGuard
import com.agency.stickerjet.domain.ImportFailure
import com.agency.stickerjet.domain.ImportOutcome
import com.agency.stickerjet.domain.StickerProject
import com.agency.stickerjet.engine.ExportFailure
import com.agency.stickerjet.engine.ExportOutcome
import com.agency.stickerjet.engine.StickerExporter
import com.agency.stickerjet.engine.StickerPreviewRenderer
import com.agency.stickerjet.ui.ports.ClipImportPort
import com.agency.stickerjet.ui.ports.ExportFailureKind
import com.agency.stickerjet.ui.ports.ExportPort
import com.agency.stickerjet.ui.ports.ExportResult
import com.agency.stickerjet.ui.ports.ImportFailureKind
import com.agency.stickerjet.ui.ports.ImportResult
import com.agency.stickerjet.ui.ports.LibraryMaintenancePort
import com.agency.stickerjet.ui.ports.OnboardingPort
import com.agency.stickerjet.ui.ports.PreviewPort
import com.agency.stickerjet.ui.ports.ProjectFilesPort
import com.agency.stickerjet.ui.ports.RenderedPreview
import com.agency.stickerjet.ui.ports.StoragePort
import java.io.File
import kotlinx.coroutines.flow.Flow

// Adapters from the data and engine layers to the ui-owned port types. Each mapping is exhaustive,
// so a new failure reason fails the build until the UI has a message for it.

internal class ProjectFilesAdapter(private val files: ProjectFiles) : ProjectFilesPort {
    override fun sourceFile(projectId: String): File = files.sourceFile(projectId)
    override fun thumbnailFile(projectId: String): File = files.thumbnailFile(projectId)
    override fun exportsDir(projectId: String): File = files.exportsDir(projectId)
}

internal class ClipImportAdapter(private val importService: ImportService) : ClipImportPort {
    override suspend fun import(contentUri: String, projectId: String): ImportResult =
        when (val outcome = importService.import(Uri.parse(contentUri), projectId)) {
            is ImportOutcome.Imported -> ImportResult.Imported(outcome.project)
            is ImportOutcome.Rejected -> ImportResult.Rejected(outcome.reason)
            is ImportOutcome.Failed -> ImportResult.Failed(outcome.cause.toUi())
        }

    private fun ImportFailure.toUi(): ImportFailureKind = when (this) {
        ImportFailure.ACCESS_REVOKED -> ImportFailureKind.ACCESS_REVOKED
        ImportFailure.LOW_STORAGE -> ImportFailureKind.LOW_STORAGE
        ImportFailure.IO_ERROR -> ImportFailureKind.IO_ERROR
    }
}

internal class ExportAdapter(private val exporter: StickerExporter) : ExportPort {
    override suspend fun export(project: StickerProject, onProgress: (Float) -> Unit): ExportResult =
        when (val outcome = exporter.export(project, onProgress)) {
            is ExportOutcome.Success -> ExportResult.Success(
                file = outcome.file,
                record = outcome.record,
                subjectFrames = outcome.subjectFrames,
                totalFrames = outcome.totalFrames,
            )
            is ExportOutcome.Failed -> ExportResult.Failed(outcome.reason.toUi())
        }

    private fun ExportFailure.toUi(): ExportFailureKind = when (this) {
        ExportFailure.SOURCE_MISSING -> ExportFailureKind.SOURCE_MISSING
        ExportFailure.TOO_LARGE -> ExportFailureKind.TOO_LARGE
        ExportFailure.LOW_STORAGE -> ExportFailureKind.LOW_STORAGE
        ExportFailure.SEGMENTATION_FAILED -> ExportFailureKind.SEGMENTATION_FAILED
        ExportFailure.INTERNAL -> ExportFailureKind.INTERNAL
    }
}

internal class PreviewAdapter(private val renderer: StickerPreviewRenderer) : PreviewPort {
    override suspend fun render(project: StickerProject, canvasPx: Int): RenderedPreview {
        val result = renderer.render(project, canvasPx)
        return RenderedPreview(
            frames = result.frames,
            durationsMs = result.durationsMs.toList(),
            subjectFrames = result.subjectFrames,
            totalFrames = result.totalFrames,
        )
    }
}

internal class MaintenanceAdapter(private val cleaner: LibraryCleaner) : LibraryMaintenancePort {
    override suspend fun clearTemporaryFiles() = cleaner.clearTemporaryFiles()
    override suspend fun deleteEverything() = cleaner.deleteEverything()
}

internal class StorageAdapter(private val guard: StorageGuard) : StoragePort {
    override fun hasRoomFor(bytes: Long): Boolean = guard.hasRoomFor(bytes)
}

internal class OnboardingAdapter(private val store: OnboardingStore) : OnboardingPort {
    override val completed: Flow<Boolean> get() = store.completed
    override suspend fun markCompleted() = store.markCompleted()
}
