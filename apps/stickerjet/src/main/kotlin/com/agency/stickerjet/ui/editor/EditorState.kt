package com.agency.stickerjet.ui.editor

import android.graphics.Bitmap
import com.agency.stickerjet.domain.ArgbFrame
import com.agency.stickerjet.domain.ExportRecord
import com.agency.stickerjet.domain.StickerProject
import com.agency.stickerjet.ui.ports.ExportFailureKind
import java.io.File

/** The four editor tabs, in display order. */
enum class EditorTab { TRIM, SUBJECT, STYLE, PREVIEW }

/**
 * A looping frame sequence. [T] is [ArgbFrame] for the trim loop and android Bitmap for the rendered
 * preview; the ViewModel never converts them, so its state stays free of Compose and Android types.
 */
sealed interface LoopState<out T> {
    data object Idle : LoopState<Nothing>
    data object Loading : LoopState<Nothing>
    data class Ready<T>(
        val frames: List<T>,
        val durationsMs: List<Int>,
        val subjectFrames: Int,
        val totalFrames: Int,
    ) : LoopState<T>
    data object Failed : LoopState<Nothing>
}

/** Person detection on the still frame, shown on the Subject tab. */
enum class SubjectDetection { IDLE, RUNNING, FOUND, NO_PERSON, FAILED }

/** Export lifecycle. A failure keeps the draft; Done carries the file to share. */
sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val progress: Float) : ExportState
    data class Done(
        val file: File,
        val record: ExportRecord,
        val subjectFrames: Int,
        val totalFrames: Int,
    ) : ExportState
    data class Failed(val reason: ExportFailureKind) : ExportState
}

/**
 * Everything the editor draws. [project] is the draft, which may hold an unsaved drag; the undo
 * history always holds the last committed state.
 */
data class EditorUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val loadFailed: Boolean = false,
    val project: StickerProject? = null,
    val tab: EditorTab = EditorTab.TRIM,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val sourceMissing: Boolean = false,
    val saveFailed: Boolean = false,
    val still: ArgbFrame? = null,
    val stillFailed: Boolean = false,
    val matte: ArgbFrame? = null,
    val detection: SubjectDetection = SubjectDetection.IDLE,
    val trimLoop: LoopState<ArgbFrame> = LoopState.Idle,
    val previewLoop: LoopState<Bitmap> = LoopState.Idle,
    val export: ExportState = ExportState.Idle,
)

/** Limits for the placement controls. Values outside these are clamped before they reach the domain. */
object PlacementLimits {
    const val MIN_SCALE: Float = 0.5f
    const val MAX_SCALE: Float = 3f
    const val MAX_OFFSET: Float = 1f
}

/** Frame sizes used inside the editor. Export always uses the full canvas; these are for on-screen work. */
object EditorFrameSizes {
    /** Longest side of the still frame used by the Subject and Style tabs. */
    const val STILL_MAX_SIDE_PX: Int = 384

    /** Longest side of each frame in the trim loop. */
    const val TRIM_LOOP_MAX_SIDE_PX: Int = 256

    /** Canvas size of the rendered preview. */
    const val PREVIEW_CANVAS_PX: Int = 256
}
