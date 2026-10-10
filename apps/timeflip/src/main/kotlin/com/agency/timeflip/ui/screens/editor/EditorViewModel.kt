package com.agency.timeflip.ui.screens.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agency.timeflip.domain.error.ErrorCategory
import com.agency.timeflip.domain.error.TimeFlipError
import com.agency.timeflip.domain.error.TimeFlipException
import com.agency.timeflip.domain.model.Easing
import com.agency.timeflip.domain.model.OutputPreset
import com.agency.timeflip.domain.model.ProjectId
import com.agency.timeflip.domain.model.TimeFlipProject
import com.agency.timeflip.domain.model.TimelinePlan
import com.agency.timeflip.domain.model.TransitionDefinition
import com.agency.timeflip.domain.model.TransitionKind
import com.agency.timeflip.domain.model.TransitionParameters
import com.agency.timeflip.domain.port.PhotoStore
import com.agency.timeflip.domain.port.ProjectRepository
import com.agency.timeflip.domain.rules.ProjectRules
import com.agency.timeflip.ui.common.ROUTE_ARG_PROJECT_ID
import com.agency.timeflip.ui.common.UiFailure
import com.agency.timeflip.ui.common.timeline
import com.agency.timeflip.ui.common.toUiFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class EditorUiState(
    val loading: Boolean = true,
    val loadFailure: UiFailure? = null,
    val project: TimeFlipProject? = null,
    val positionMs: Int = 0,
    val playing: Boolean = false,
    val saveFailure: UiFailure? = null,
) {
    val timeline: TimelinePlan? get() = project?.timeline()

    val totalMs: Int get() = timeline?.totalDurationMs ?: 0
}

/**
 * Transition, timing and output settings with a playback clock for the live preview.
 *
 * The clock is advanced by the screen through [onFrameTick]; the ViewModel holds no timers, so it
 * is deterministic in tests. Every settings change is written to the repository, and the transition
 * is also mirrored into [SavedStateHandle] for restoration after process death.
 */
class EditorViewModel(
    savedStateHandle: SavedStateHandle,
    private val projects: ProjectRepository,
    private val photos: PhotoStore,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val handle: SavedStateHandle = savedStateHandle
    private val projectId = ProjectId(checkNotNull(handle.get<String>(ROUTE_ARG_PROJECT_ID)))

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val saveMutex = Mutex()

    init {
        load()
    }

    /** Re-reads the project, for example after the alignment was changed on another screen. */
    fun reload() {
        viewModelScope.launch {
            try {
                val fresh = projects.getProject(projectId)
                    ?: throw TimeFlipException(TimeFlipError(ErrorCategory.NOT_FOUND))
                _state.update { it.copy(loading = false, loadFailure = null, project = fresh) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadFailure = e.toUiFailure()) }
            }
        }
    }

    fun selectKind(kind: TransitionKind) = updateTransition { it.copy(kind = kind) }

    fun selectEasing(easing: Easing) = updateTransition { it.copy(parameters = it.parameters.copy(easing = easing)) }

    fun setReversed(reversed: Boolean) = updateTransition { it.copy(parameters = it.parameters.copy(reversed = reversed)) }

    /** Updates the duration while the slider moves. The change is written by [commitDuration]. */
    fun previewDurationMs(durationMs: Int) {
        val current = _state.value.project ?: return
        setProject(ProjectRules.setTransitionDuration(current, durationMs, now()))
    }

    fun commitDuration() {
        persistLatest()
    }

    fun selectPreset(preset: OutputPreset) {
        val current = _state.value.project ?: return
        applyAndSave(ProjectRules.setExportProfile(current, current.exportProfile.copy(preset = preset), now()))
    }

    fun togglePlayback() {
        val s = _state.value
        if (s.project == null) return
        if (s.playing) {
            _state.update { it.copy(playing = false) }
            return
        }
        val restart = s.positionMs >= s.totalMs
        _state.update { it.copy(playing = true, positionMs = if (restart) 0 else it.positionMs) }
    }

    fun replay() {
        if (_state.value.project == null) return
        _state.update { it.copy(positionMs = 0, playing = true) }
    }

    /** Pauses playback and moves to [positionMs]. */
    fun scrubTo(positionMs: Int) {
        _state.update { it.copy(playing = false, positionMs = positionMs.coerceIn(0, it.totalMs)) }
    }

    /** Advances the clock by the frame time reported by the screen. Large gaps are capped. */
    fun onFrameTick(deltaMs: Long) {
        val s = _state.value
        if (!s.playing) return
        val next = s.positionMs + deltaMs.coerceIn(0L, MAX_TICK_MS).toInt()
        if (next >= s.totalMs) {
            _state.update { it.copy(positionMs = it.totalMs, playing = false) }
        } else {
            _state.update { it.copy(positionMs = next) }
        }
    }

    private fun updateTransition(change: (TransitionDefinition) -> TransitionDefinition) {
        val current = _state.value.project ?: return
        applyAndSave(ProjectRules.setTransition(current, change(current.transition), now()))
    }

    private fun applyAndSave(updated: TimeFlipProject) {
        setProject(updated)
        persistLatest()
    }

    private fun setProject(updated: TimeFlipProject) {
        saveTransition(updated.transition)
        _state.update {
            it.copy(
                project = updated,
                positionMs = it.positionMs.coerceIn(0, updated.timeline().totalDurationMs),
                saveFailure = null,
            )
        }
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val loaded = projects.getProject(projectId)
                    ?: throw TimeFlipException(TimeFlipError(ErrorCategory.NOT_FOUND))
                if (photos.originalPath(loaded.pair.before.id) == null ||
                    photos.originalPath(loaded.pair.after.id) == null
                ) {
                    throw TimeFlipException(TimeFlipError(ErrorCategory.SOURCE_MISSING))
                }
                val restored = restoredTransition()
                val project = if (restored != null && restored != loaded.transition) {
                    ProjectRules.setTransition(loaded, restored, now())
                } else {
                    loaded
                }
                _state.update { it.copy(loading = false, project = project) }
                if (project !== loaded) persistLatest()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadFailure = e.toUiFailure()) }
            }
        }
    }

    /** Writes the newest project value. Writes are serialised so the last change always wins. */
    private fun persistLatest() {
        viewModelScope.launch {
            saveMutex.withLock {
                val latest = _state.value.project ?: return@withLock
                try {
                    withContext(NonCancellable) { projects.saveProject(latest) }
                    _state.update { it.copy(saveFailure = null) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(saveFailure = e.toUiFailure()) }
                }
            }
        }
    }

    private fun saveTransition(transition: TransitionDefinition) {
        handle[KEY_KIND] = transition.kind.name
        handle[KEY_DURATION_MS] = transition.parameters.durationMs
        handle[KEY_EASING] = transition.parameters.easing.name
        handle[KEY_REVERSED] = transition.parameters.reversed
        handle[KEY_WIPE_ANGLE] = transition.parameters.wipeAngleDegrees
    }

    /** Returns the transition saved before process death, or null when nothing valid was saved. */
    private fun restoredTransition(): TransitionDefinition? {
        val kindName = handle.get<String>(KEY_KIND) ?: return null
        val durationMs = handle.get<Int>(KEY_DURATION_MS) ?: return null
        val easingName = handle.get<String>(KEY_EASING) ?: return null
        val reversed = handle.get<Boolean>(KEY_REVERSED) ?: return null
        val wipeAngle = handle.get<Double>(KEY_WIPE_ANGLE) ?: return null
        return try {
            TransitionDefinition(
                kind = TransitionKind.valueOf(kindName),
                parameters = TransitionParameters(
                    durationMs = durationMs,
                    easing = Easing.valueOf(easingName),
                    reversed = reversed,
                    wipeAngleDegrees = wipeAngle,
                ),
            )
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    companion object {
        /** Largest clock step accepted in one tick, so a stalled frame cannot skip the whole timeline. */
        const val MAX_TICK_MS = 100L

        private const val KEY_KIND = "editor.transition.kind"
        private const val KEY_DURATION_MS = "editor.transition.durationMs"
        private const val KEY_EASING = "editor.transition.easing"
        private const val KEY_REVERSED = "editor.transition.reversed"
        private const val KEY_WIPE_ANGLE = "editor.transition.wipeAngle"
    }
}
