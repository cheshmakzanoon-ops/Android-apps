package com.agency.stickerjet.ui.editor

import android.graphics.Bitmap
import com.agency.stickerjet.domain.ArgbFrame
import com.agency.stickerjet.domain.EditHistory
import com.agency.stickerjet.domain.ExportPlan
import com.agency.stickerjet.domain.StickerLimits
import com.agency.stickerjet.domain.StickerProject
import com.agency.stickerjet.ui.ports.ExportFailureKind
import com.agency.stickerjet.ui.ports.ExportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update

/** Decodes the trim window as a looping frame sequence, using the same timestamps as export. */
internal suspend fun EditorSession.refreshTrimLoop() {
    val current = project ?: return
    if (state.value.sourceMissing) return
    state.update { it.copy(trimLoop = LoopState.Loading) }
    val result: LoopState<ArgbFrame> = try {
        val timestamps = ExportPlan.frameTimestampsMs(current.trim)
        val durations = ExportPlan.frameDurationsMs(current.trim).toList()
        val decoded = frames.frames(current.source.fileName, timestamps, EditorFrameSizes.TRIM_LOOP_MAX_SIDE_PX).toList()
        LoopState.Ready(decoded, durations, subjectFrames = 0, totalFrames = decoded.size)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LoopState.Failed
    }
    state.update { it.copy(trimLoop = result) }
}

/** Renders the animation again when the project changed since the last render. */
internal suspend fun EditorSession.ensurePreview() {
    val current = project ?: return
    if (!previewStale && state.value.previewLoop is LoopState.Ready) return
    previewStale = false
    state.update { it.copy(previewLoop = LoopState.Loading) }
    val result: LoopState<Bitmap> = try {
        val rendered = preview.render(current, EditorFrameSizes.PREVIEW_CANVAS_PX)
        LoopState.Ready(
            frames = rendered.frames,
            durationsMs = rendered.durationsMs,
            subjectFrames = rendered.subjectFrames,
            totalFrames = rendered.totalFrames,
        )
    } catch (e: CancellationException) {
        previewStale = true
        throw e
    } catch (e: Exception) {
        previewStale = true
        LoopState.Failed
    }
    state.update { it.copy(previewLoop = result) }
}

/**
 * Exports the draft. Missing source and low storage are checked first, so the exporter is not started
 * when it cannot succeed. Cancelling removes the temporary file and returns to Idle. Every failure keeps
 * the draft.
 */
internal suspend fun EditorSession.exportSticker() {
    val current = project ?: return
    if (state.value.sourceMissing) {
        state.update { it.copy(export = ExportState.Failed(ExportFailureKind.SOURCE_MISSING)) }
        return
    }
    if (!storage.hasRoomFor(StickerLimits.MAX_EXPORT_BYTES)) {
        state.update { it.copy(export = ExportState.Failed(ExportFailureKind.LOW_STORAGE)) }
        return
    }
    state.update { it.copy(export = ExportState.Running(0f)) }
    try {
        val result = exporter.export(current) { fraction ->
            state.update { it.copy(export = ExportState.Running(fraction.coerceIn(0f, 1f))) }
        }
        when (result) {
            is ExportResult.Success -> onExported(current, result)
            is ExportResult.Failed -> state.update { it.copy(export = ExportState.Failed(result.reason)) }
        }
    } catch (e: CancellationException) {
        state.update { it.copy(export = ExportState.Idle) }
        throw e
    } catch (e: Exception) {
        state.update { it.copy(export = ExportState.Failed(ExportFailureKind.INTERNAL)) }
    }
}

/** Reloads the project, because the exporter wrote the new export record. Undo restarts from the reloaded state. */
private suspend fun EditorSession.onExported(current: StickerProject, result: ExportResult.Success) {
    val reloaded = projects.load(projectId) ?: current
    history = EditHistory(reloaded)
    state.update {
        it.copy(
            project = reloaded,
            canUndo = false,
            canRedo = false,
            export = ExportState.Done(result.file, result.record, result.subjectFrames, result.totalFrames),
        )
    }
}
