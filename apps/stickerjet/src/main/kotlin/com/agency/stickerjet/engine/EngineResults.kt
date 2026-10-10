package com.agency.stickerjet.engine

import android.graphics.Bitmap
import com.agency.stickerjet.domain.ExportRecord
import java.io.File
import java.io.IOException

/** Result of [StickerExporter.export]. A failed export leaves the project draft untouched. */
sealed interface ExportOutcome {
    /** [file] is the published WebP in the project's exports directory; [record] was appended to the project. */
    data class Success(
        val file: File,
        val record: ExportRecord,
        val subjectFrames: Int,
        val totalFrames: Int,
    ) : ExportOutcome

    data class Failed(val reason: ExportFailure) : ExportOutcome
}

/** Why an export failed. Each reason maps to a message and a retry path in the UI. */
enum class ExportFailure { SOURCE_MISSING, TOO_LARGE, LOW_STORAGE, SEGMENTATION_FAILED, INTERNAL }

/**
 * Frames of the editor preview. The caller owns [frames] and must call `recycle()` on each bitmap when the
 * preview is replaced. [durationsMs] has one entry per frame, as in the export.
 */
data class PreviewResult(
    val frames: List<Bitmap>,
    val durationsMs: IntArray,
    val subjectFrames: Int,
    val totalFrames: Int,
)

/** The source clip for [projectId] is not on disk. Mapped to [ExportFailure.SOURCE_MISSING]. */
internal class SourceMissingException(val projectId: String) :
    IOException("Source clip for project $projectId is missing")

/** The segmenter threw. Mapped to [ExportFailure.SEGMENTATION_FAILED]; a null result is not a failure. */
internal class SegmentationFailedException(cause: Throwable) :
    RuntimeException("Subject segmentation failed", cause)
