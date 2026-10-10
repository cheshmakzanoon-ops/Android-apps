package com.agency.stickerjet.ui.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agency.stickerjet.domain.BackgroundMode
import com.agency.stickerjet.domain.CaptionPosition
import com.agency.stickerjet.domain.FrameSource
import com.agency.stickerjet.domain.NormalizedPoint
import com.agency.stickerjet.domain.ProjectRepository
import com.agency.stickerjet.domain.StrokeMode
import com.agency.stickerjet.domain.SubjectSegmenter
import com.agency.stickerjet.domain.withCaption
import com.agency.stickerjet.ui.navigation.Routes
import com.agency.stickerjet.ui.ports.ExportPort
import com.agency.stickerjet.ui.ports.MaskRefinementPort
import com.agency.stickerjet.ui.ports.PreviewPort
import com.agency.stickerjet.ui.ports.ProjectFilesPort
import com.agency.stickerjet.ui.ports.StoragePort
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Editor screen state holder for one project. The project id comes from [SavedStateHandle]. The
 * behaviour lives in [EditorSession]; this class only launches work on [viewModelScope] and exposes
 * the state. Drags update the draft; a finished gesture commits it as one undo step.
 */
class EditorViewModel(
    savedState: SavedStateHandle,
    projects: ProjectRepository,
    files: ProjectFilesPort,
    frames: FrameSource,
    segmenter: SubjectSegmenter,
    masks: MaskRefinementPort,
    exporter: ExportPort,
    preview: PreviewPort,
    storage: StoragePort,
    clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    /** The state machine. Internal so JVM tests can drive it without a main dispatcher. */
    internal val session = EditorSession(
        projectId = requireNotNull(savedState.get<String>(Routes.PROJECT_ID_ARG)) {
            "The editor needs a project id"
        },
        projects = projects,
        files = files,
        frames = frames,
        segmenter = segmenter,
        masks = masks,
        exporter = exporter,
        preview = preview,
        storage = storage,
        clock = clock,
    )

    /** Everything the editor draws. */
    val state: StateFlow<EditorUiState> = session.state

    private var exportJob: Job? = null

    /** Loads the project. Called once when the screen first appears. */
    fun start() {
        viewModelScope.launch { session.load() }
    }

    /** Switches tab and prepares whatever that tab needs. */
    fun selectTab(tab: EditorTab) {
        session.state.update { it.copy(tab = tab) }
        viewModelScope.launch { session.enterTab(tab) }
    }

    /** Moves the start handle during a drag. Not committed until [onTrimFinished]. */
    fun onTrimStartDrag(ms: Float) {
        if (ms.isFinite()) session.draftTrimStart(ms.toLong())
    }

    /** Moves the end handle during a drag. Not committed until [onTrimFinished]. */
    fun onTrimEndDrag(ms: Float) {
        if (ms.isFinite()) session.draftTrimEnd(ms.toLong())
    }

    /** Ends a trim gesture: commits the draft and refreshes the loop. */
    fun onTrimFinished() {
        viewModelScope.launch { session.commitDraft() }
    }

    /** Sets placement during a drag. Not committed until [onPlacementFinished]. */
    fun onPlacementDrag(scale: Float, offsetX: Float, offsetY: Float) {
        session.draftPlacement(scale, offsetX, offsetY)
    }

    /** Ends a placement gesture: commits the draft. */
    fun onPlacementFinished() {
        viewModelScope.launch { session.commitDraft() }
    }

    /** Quarter-turn rotation, committed as one step. */
    fun rotate() {
        viewModelScope.launch { session.rotateQuarterTurn() }
    }

    /** Returns scale, position and rotation to their defaults. */
    fun resetPlacement() {
        viewModelScope.launch { session.resetPlacement() }
    }

    /** Turns background removal on or off. */
    fun setBackground(mode: BackgroundMode) {
        viewModelScope.launch { session.setBackgroundMode(mode) }
    }

    /** Runs person detection, after first opening the tab or after a failure. */
    fun detectSubject() {
        viewModelScope.launch { session.detectSubjectNow() }
    }

    /** Adds one brush stroke from a finished drag. */
    fun addStroke(mode: StrokeMode, points: List<NormalizedPoint>, radius: Float) {
        viewModelScope.launch { session.addStrokeNow(mode, points, radius) }
    }

    /** Removes every manual correction. */
    fun resetMask() {
        viewModelScope.launch { session.resetMaskNow() }
    }

    /** Commits the caption text when its field loses focus or Done is pressed. */
    fun commitCaption(text: String) {
        viewModelScope.launch { session.commitCaptionText(text) }
    }

    /** Changes the caption position. */
    fun setCaptionPosition(position: CaptionPosition) {
        viewModelScope.launch {
            session.commitIfPresent { p -> p.withCaption(p.caption.copy(position = position), session.now) }
        }
    }

    /** Changes the caption colour. [argb] is 0xAARRGGBB; out-of-range values are ignored. */
    fun setCaptionColor(argb: Long) {
        if (argb !in 0L..MAX_ARGB) return
        viewModelScope.launch {
            session.commitIfPresent { p -> p.withCaption(p.caption.copy(argb = argb), session.now) }
        }
    }

    /** Steps back one edit. */
    fun undo() {
        viewModelScope.launch { session.step { it.undo() } }
    }

    /** Steps forward one edit. */
    fun redo() {
        viewModelScope.launch { session.step { it.redo() } }
    }

    /** Renders the loop again, for example after a failed preview. */
    fun refreshPreview() {
        session.previewStale = true
        viewModelScope.launch { session.enterTab(EditorTab.PREVIEW) }
    }

    /** Starts an export. The draft is kept whatever the outcome. */
    fun startExport() {
        exportJob?.cancel()
        exportJob = viewModelScope.launch { session.exportSticker() }
    }

    /** Cancels a running export and removes its temporary file. */
    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        session.state.update { it.copy(export = ExportState.Idle) }
    }

    /** Closes an export result or failure, so the user can keep editing. */
    fun dismissExport() {
        session.state.update { it.copy(export = ExportState.Idle) }
    }

    private companion object {
        const val MAX_ARGB: Long = 0xFFFF_FFFFL
    }
}
