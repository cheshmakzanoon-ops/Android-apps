package com.agency.timeflip.ui.screens.manualalign

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agency.timeflip.domain.error.ErrorCategory
import com.agency.timeflip.domain.error.TimeFlipError
import com.agency.timeflip.domain.error.TimeFlipException
import com.agency.timeflip.domain.geometry.PhotoPlacement
import com.agency.timeflip.domain.history.EditHistory
import com.agency.timeflip.domain.model.AlignmentTransform
import com.agency.timeflip.domain.model.ImagePair
import com.agency.timeflip.domain.model.PixelSize
import com.agency.timeflip.domain.model.ProjectId
import com.agency.timeflip.domain.model.TimeFlipProject
import com.agency.timeflip.domain.port.ProjectRepository
import com.agency.timeflip.domain.rules.ProjectRules
import com.agency.timeflip.ui.common.ROUTE_ARG_PROJECT_ID
import com.agency.timeflip.ui.common.UiFailure
import com.agency.timeflip.ui.common.previewFrame
import com.agency.timeflip.ui.common.toUiFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SaveState { SAVED, PENDING, FAILED }

data class ManualAlignUiState(
    val loading: Boolean = true,
    val loadFailure: UiFailure? = null,
    val pair: ImagePair? = null,
    val frame: PixelSize = PixelSize(720, 1280),
    val transform: AlignmentTransform = AlignmentTransform.IDENTITY,
    val coverage: Double = 1.0,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val overlayOpacity: Float = 0.5f,
    val dividerFraction: Float = 0.5f,
    val saveState: SaveState = SaveState.SAVED,
) {
    /** True when the AFTER photo does not cover the whole output frame. */
    val hasEmptyEdges: Boolean get() = coverage < FULL_COVERAGE
}

/**
 * Manual placement of the AFTER photo.
 *
 * Gestures and sliders update [ManualAlignUiState.transform] on every change. Undo history gets one
 * step per completed gesture or slider release. Saving is debounced; [finish] flushes it.
 * The working transform is mirrored into [SavedStateHandle] so it survives process death.
 */
class ManualAlignViewModel(
    savedStateHandle: SavedStateHandle,
    private val projects: ProjectRepository,
    private val now: () -> Long = System::currentTimeMillis,
    private val saveDebounceMs: Long = SAVE_DEBOUNCE_MS,
) : ViewModel() {

    private val handle: SavedStateHandle = savedStateHandle
    private val projectId = ProjectId(checkNotNull(handle.get<String>(ROUTE_ARG_PROJECT_ID)))

    private val _state = MutableStateFlow(ManualAlignUiState())
    val state: StateFlow<ManualAlignUiState> = _state.asStateFlow()

    private var project: TimeFlipProject? = null
    private var history: EditHistory<AlignmentTransform>? = null
    private var saveJob: Job? = null
    private var dirty = false
    private val saveMutex = Mutex()

    init {
        load()
    }

    /** Scale, rotation or position change from a pinch, twist or drag. Amounts are relative. */
    fun onGestureDelta(zoom: Double, rotationDeltaDegrees: Double, panFractionX: Double, panFractionY: Double) {
        val current = _state.value.transform
        val safeZoom = if (zoom.isFinite() && zoom > 0.0) zoom else 1.0
        preview(
            AlignmentTransform.clamped(
                scale = current.scale * safeZoom,
                rotationDegrees = current.rotationDegrees + rotationDeltaDegrees,
                offsetX = current.offsetX + panFractionX,
                offsetY = current.offsetY + panFractionY,
            ),
        )
    }

    /** Ends a gesture: records one undo step and schedules a save. */
    fun onGestureEnd() {
        commit()
    }

    fun onScaleChange(scale: Double) {
        val current = _state.value.transform
        preview(AlignmentTransform.clamped(scale, current.rotationDegrees, current.offsetX, current.offsetY))
    }

    fun onRotationChange(rotationDegrees: Double) {
        val current = _state.value.transform
        preview(AlignmentTransform.clamped(current.scale, rotationDegrees, current.offsetX, current.offsetY))
    }

    /** Called when a numeric slider is released. */
    fun onSliderFinished() {
        commit()
    }

    fun onOverlayOpacityChange(opacity: Float) {
        _state.update { it.copy(overlayOpacity = opacity.coerceIn(0f, 1f)) }
    }

    fun onDividerChange(fraction: Float) {
        _state.update { it.copy(dividerFraction = fraction.coerceIn(0f, 1f)) }
    }

    fun reset() {
        preview(AlignmentTransform.IDENTITY)
        commit()
    }

    fun fit() {
        val current = project ?: return
        val transform = _state.value.transform
        val fitted = AlignmentFit.fitToCover(transform, current.pair.after.size, _state.value.frame)
        if (fitted != transform) {
            preview(fitted)
            commit()
        }
    }

    fun undo() {
        val h = history ?: return
        commit()
        val previous = h.undo() ?: return
        restore(previous, h)
    }

    fun redo() {
        val h = history ?: return
        val next = h.redo() ?: return
        restore(next, h)
    }

    /** Saves the alignment and calls [onDone] only when the save succeeded. */
    fun finish(onDone: () -> Unit) {
        saveJob?.cancel()
        viewModelScope.launch {
            if (persistNow()) onDone()
        }
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val loaded = projects.getProject(projectId)
                    ?: throw TimeFlipException(TimeFlipError(ErrorCategory.NOT_FOUND))
                project = loaded
                val start = loaded.alignment
                val draft = savedTransform() ?: start
                val h = EditHistory(start)
                if (draft != start) h.commit(draft)
                history = h
                dirty = draft != start
                _state.update {
                    it.copy(
                        loading = false,
                        pair = loaded.pair,
                        frame = loaded.exportProfile.preset.previewFrame(),
                        transform = draft,
                        coverage = coverageFor(loaded, draft),
                        canUndo = h.canUndo,
                        canRedo = h.canRedo,
                        saveState = if (dirty) SaveState.PENDING else SaveState.SAVED,
                    )
                }
                if (dirty) scheduleSave()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadFailure = e.toUiFailure()) }
            }
        }
    }

    private fun preview(next: AlignmentTransform) {
        val current = project ?: return
        dirty = true
        _state.update {
            it.copy(transform = next, coverage = coverageFor(current, next), saveState = SaveState.PENDING)
        }
        saveDraft(next)
        scheduleSave()
    }

    private fun commit() {
        val h = history ?: return
        h.commit(_state.value.transform)
        _state.update { it.copy(canUndo = h.canUndo, canRedo = h.canRedo) }
        scheduleSave()
    }

    private fun restore(transform: AlignmentTransform, h: EditHistory<AlignmentTransform>) {
        val current = project ?: return
        dirty = true
        _state.update {
            it.copy(
                transform = transform,
                coverage = coverageFor(current, transform),
                canUndo = h.canUndo,
                canRedo = h.canRedo,
                saveState = SaveState.PENDING,
            )
        }
        saveDraft(transform)
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(saveDebounceMs)
            persistNow()
        }
    }

    /** Writes the latest transform. Returns false when the write failed. */
    private suspend fun persistNow(): Boolean = saveMutex.withLock {
        val current = project ?: return@withLock false
        if (!dirty) return@withLock true
        val updated = ProjectRules.applyManualAlignment(current, _state.value.transform, now())
        try {
            withContext(NonCancellable) { projects.saveProject(updated) }
            project = updated
            dirty = false
            _state.update { it.copy(saveState = SaveState.SAVED) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(saveState = SaveState.FAILED) }
            false
        }
    }

    private fun coverageFor(project: TimeFlipProject, transform: AlignmentTransform): Double =
        PhotoPlacement.coverage(
            source = project.pair.after.size,
            frame = project.exportProfile.preset.previewFrame(),
            alignment = transform,
        )

    private fun savedTransform(): AlignmentTransform? {
        val scale = handle.get<Double>(KEY_SCALE) ?: return null
        val rotation = handle.get<Double>(KEY_ROTATION) ?: return null
        val offsetX = handle.get<Double>(KEY_OFFSET_X) ?: return null
        val offsetY = handle.get<Double>(KEY_OFFSET_Y) ?: return null
        return AlignmentTransform.clamped(scale, rotation, offsetX, offsetY)
    }

    private fun saveDraft(transform: AlignmentTransform) {
        handle[KEY_SCALE] = transform.scale
        handle[KEY_ROTATION] = transform.rotationDegrees
        handle[KEY_OFFSET_X] = transform.offsetX
        handle[KEY_OFFSET_Y] = transform.offsetY
    }

    companion object {
        const val SAVE_DEBOUNCE_MS = 400L

        private const val KEY_SCALE = "alignment.scale"
        private const val KEY_ROTATION = "alignment.rotation"
        private const val KEY_OFFSET_X = "alignment.offsetX"
        private const val KEY_OFFSET_Y = "alignment.offsetY"
    }
}
