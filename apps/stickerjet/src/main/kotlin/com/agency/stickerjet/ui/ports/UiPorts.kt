package com.agency.stickerjet.ui.ports

import android.graphics.Bitmap
import com.agency.stickerjet.domain.AlphaMask
import com.agency.stickerjet.domain.ExportRecord
import com.agency.stickerjet.domain.FrameSource
import com.agency.stickerjet.domain.ImportRejection
import com.agency.stickerjet.domain.MaskStroke
import com.agency.stickerjet.domain.ProjectRepository
import com.agency.stickerjet.domain.StickerProject
import com.agency.stickerjet.domain.SubjectSegmenter
import java.io.File
import kotlinx.coroutines.flow.Flow

/**
 * Everything the screens need from the rest of the app, expressed in ui-owned types. The composition
 * root (app package, SJ-PM) builds one instance from AppContainer through `uiPorts()`. Screens and
 * ViewModels never see data or engine classes, and tests construct only the ports they use.
 */
class UiPorts(
    val projects: ProjectRepository,
    val frames: FrameSource,
    val segmenter: SubjectSegmenter,
    val files: ProjectFilesPort,
    val importer: ClipImportPort,
    val exporter: ExportPort,
    val preview: PreviewPort,
    val maintenance: LibraryMaintenancePort,
    val storage: StoragePort,
    val onboarding: OnboardingPort,
    val masks: MaskRefinementPort,
)

/** Paths inside a project's private directory. Files may be missing; callers check with exists(). */
interface ProjectFilesPort {
    fun sourceFile(projectId: String): File
    fun thumbnailFile(projectId: String): File
    fun exportsDir(projectId: String): File
}

/**
 * Copies a picked clip into app storage and judges it. [contentUri] is the string form of the
 * content URI from the system picker, so no Android Uri type crosses into the ui layer.
 */
interface ClipImportPort {
    suspend fun import(contentUri: String, projectId: String): ImportResult
}

/** Outcome of an import, in ui terms. */
sealed interface ImportResult {
    data class Imported(val project: StickerProject) : ImportResult
    data class Rejected(val reason: ImportRejection) : ImportResult
    data class Failed(val cause: ImportFailureKind) : ImportResult
}

/** Causes of an import that did not complete and did not reject the clip. */
enum class ImportFailureKind { ACCESS_REVOKED, LOW_STORAGE, IO_ERROR }

/** Renders and writes the animated sticker. [onProgress] reports 0..1. Cancelling removes the temporary file. */
interface ExportPort {
    suspend fun export(project: StickerProject, onProgress: (Float) -> Unit): ExportResult
}

/** Outcome of an export. A failure never deletes the draft. */
sealed interface ExportResult {
    data class Success(
        val file: File,
        val record: ExportRecord,
        val subjectFrames: Int,
        val totalFrames: Int,
    ) : ExportResult

    data class Failed(val reason: ExportFailureKind) : ExportResult
}

/** Causes of an export failure. Each one has a message and a way forward. */
enum class ExportFailureKind { SOURCE_MISSING, TOO_LARGE, LOW_STORAGE, SEGMENTATION_FAILED, INTERNAL }

/** Renders the looping animation for the editor's preview. Throws when rendering fails. */
interface PreviewPort {
    suspend fun render(project: StickerProject, canvasPx: Int): RenderedPreview
}

/** Frames of the preview loop, with the per-frame durations in milliseconds. */
class RenderedPreview(
    val frames: List<Bitmap>,
    val durationsMs: List<Int>,
    val subjectFrames: Int,
    val totalFrames: Int,
)

/** Cleanup actions in Settings. */
interface LibraryMaintenancePort {
    suspend fun clearTemporaryFiles()
    suspend fun deleteEverything()
}

/** Free-space check used before writing an export. */
interface StoragePort {
    fun hasRoomFor(bytes: Long): Boolean
}

/** First-run flag. [completed] emits true once the welcome screen has been finished. */
interface OnboardingPort {
    val completed: Flow<Boolean>
    suspend fun markCompleted()
}

/**
 * Applies ordered manual strokes to a base mask and smooths the edge. Implemented by the pure
 * `MaskRefiner.refine` in core, so a function reference satisfies it.
 */
fun interface MaskRefinementPort {
    fun refine(base: AlphaMask?, width: Int, height: Int, strokes: List<MaskStroke>): AlphaMask
}
