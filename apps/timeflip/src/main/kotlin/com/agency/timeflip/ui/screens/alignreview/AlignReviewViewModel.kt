package com.agency.timeflip.ui.screens.alignreview

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agency.timeflip.domain.error.ErrorCategory
import com.agency.timeflip.domain.error.TimeFlipError
import com.agency.timeflip.domain.error.TimeFlipException
import com.agency.timeflip.domain.model.AlignmentEstimate
import com.agency.timeflip.domain.model.AlignmentSource
import com.agency.timeflip.domain.model.AlignmentStatus
import com.agency.timeflip.domain.model.ProjectId
import com.agency.timeflip.domain.model.TimeFlipProject
import com.agency.timeflip.domain.port.PhotoStore
import com.agency.timeflip.domain.port.ProjectRepository
import com.agency.timeflip.domain.rules.ProjectRules
import com.agency.timeflip.ui.common.ROUTE_ARG_PROJECT_ID
import com.agency.timeflip.ui.common.UiFailure
import com.agency.timeflip.ui.common.toUiFailure
import com.agency.timeflip.ui.ports.AlignmentRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface AlignPhase {
    data object Loading : AlignPhase

    data object NotRun : AlignPhase

    data object Running : AlignPhase

    data class Estimated(val estimate: AlignmentEstimate) : AlignPhase

    data object Cancelled : AlignPhase

    data class Failed(val failure: UiFailure) : AlignPhase
}

data class AlignReviewUiState(
    val phase: AlignPhase = AlignPhase.Loading,
    val savedSource: AlignmentSource = AlignmentSource.UNALIGNED,
    val busy: Boolean = false,
    val actionFailure: UiFailure? = null,
)

/**
 * Runs the automatic aligner for the project's photos and lets the user accept, adjust or reset.
 * Nothing is saved until the user chooses an action. Running again cancels the previous run.
 */
class AlignReviewViewModel(
    savedStateHandle: SavedStateHandle,
    private val projects: ProjectRepository,
    private val photos: PhotoStore,
    private val aligner: AlignmentRunner,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val projectId = ProjectId(checkNotNull(savedStateHandle.get<String>(ROUTE_ARG_PROJECT_ID)))

    private val _state = MutableStateFlow(AlignReviewUiState())
    val state: StateFlow<AlignReviewUiState> = _state.asStateFlow()

    private var project: TimeFlipProject? = null
    private var job: Job? = null

    init {
        load()
    }

    /** Retries whichever step failed: loading the project, or the alignment run. */
    fun retry() {
        if (project == null) load() else start()
    }

    /** Starts (or restarts) the automatic alignment. */
    fun start() {
        val current = project ?: return
        job?.cancel()
        _state.update { it.copy(phase = AlignPhase.Running) }
        job = viewModelScope.launch {
            try {
                if (photosMissing(current)) {
                    throw TimeFlipException(TimeFlipError(ErrorCategory.SOURCE_MISSING))
                }
                val estimate = aligner.align(current.pair, current.exportProfile.preset.frame)
                _state.update { it.copy(phase = AlignPhase.Estimated(estimate)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = AlignPhase.Failed(e.toUiFailure())) }
            }
        }
    }

    fun cancel() {
        if (_state.value.phase !is AlignPhase.Running) return
        job?.cancel()
        job = null
        _state.update { it.copy(phase = AlignPhase.Cancelled) }
    }

    /**
     * Saves an ACCEPTED automatic estimate as the project alignment, then calls [onDone]. A
     * NEEDS_MANUAL or FAILED estimate is never applied automatically.
     */
    fun acceptEstimate(onDone: () -> Unit) {
        val estimate = (_state.value.phase as? AlignPhase.Estimated)?.estimate ?: return
        if (estimate.status != AlignmentStatus.ACCEPTED) return
        saveThen(onDone) { current -> ProjectRules.applyEstimate(current, estimate, now()) }
    }

    /**
     * Continues to manual alignment. A usable estimate, even a low-confidence one, becomes the
     * starting point that the user checks. A FAILED estimate is not used.
     */
    fun adjustManually(onDone: () -> Unit) {
        val estimate = (_state.value.phase as? AlignPhase.Estimated)?.estimate
        val usable = estimate?.takeIf { it.status != AlignmentStatus.FAILED }
        if (usable == null) {
            onDone()
            return
        }
        saveThen(onDone) { current -> ProjectRules.applyEstimate(current, usable, now()) }
    }

    /**
     * Opens the editor without applying any estimate. The saved alignment stays as it was, so an
     * unreliable estimate is never presented as the placement.
     */
    fun openEditorWithoutEstimate(onDone: () -> Unit) {
        onDone()
    }

    /** Clears the saved alignment back to the unaligned cover fit and returns to the not-run state. */
    fun resetAlignment() {
        job?.cancel()
        job = null
        saveThen(onDone = { _state.update { it.copy(phase = AlignPhase.NotRun) } }) { current ->
            ProjectRules.resetAlignment(current, now())
        }
    }

    private fun load() {
        _state.update { it.copy(phase = AlignPhase.Loading) }
        viewModelScope.launch {
            try {
                val loaded = projects.getProject(projectId)
                    ?: throw TimeFlipException(TimeFlipError(ErrorCategory.NOT_FOUND))
                project = loaded
                _state.update { it.copy(savedSource = loaded.alignmentSource) }
                start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = AlignPhase.Failed(e.toUiFailure())) }
            }
        }
    }

    private fun saveThen(onDone: () -> Unit, change: (TimeFlipProject) -> TimeFlipProject) {
        val current = project ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, actionFailure = null) }
        viewModelScope.launch {
            try {
                val updated = change(current)
                projects.saveProject(updated)
                project = updated
                _state.update { it.copy(busy = false, savedSource = updated.alignmentSource) }
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, actionFailure = e.toUiFailure()) }
            }
        }
    }

    private suspend fun photosMissing(project: TimeFlipProject): Boolean =
        photos.originalPath(project.pair.before.id) == null || photos.originalPath(project.pair.after.id) == null
}
