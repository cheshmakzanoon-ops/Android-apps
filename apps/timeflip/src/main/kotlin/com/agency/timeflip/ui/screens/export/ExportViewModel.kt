package com.agency.timeflip.ui.screens.export

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agency.timeflip.domain.error.ErrorCategory
import com.agency.timeflip.domain.error.TimeFlipError
import com.agency.timeflip.domain.error.TimeFlipException
import com.agency.timeflip.domain.model.ExportProgress
import com.agency.timeflip.domain.model.ExportedVideo
import com.agency.timeflip.domain.model.ProjectId
import com.agency.timeflip.domain.model.TimeFlipProject
import com.agency.timeflip.domain.port.ProjectRepository
import com.agency.timeflip.domain.port.VideoExportEngine
import com.agency.timeflip.ui.common.ROUTE_ARG_PROJECT_ID
import com.agency.timeflip.ui.common.UiFailure
import com.agency.timeflip.ui.common.toExportRequest
import com.agency.timeflip.ui.common.toUiFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ExportPhase {
    /** No export is running and none has finished in this screen. */
    data object Idle : ExportPhase

    data class Rendering(val framesDone: Int, val framesTotal: Int) : ExportPhase {
        val fraction: Float get() = if (framesTotal > 0) framesDone.toFloat() / framesTotal else 0f
    }

    /** The engine reported Finished; the file is being recorded. Cancelling is no longer allowed. */
    data object Recording : ExportPhase

    data class Finished(val video: ExportedVideo) : ExportPhase

    data class Failed(val failure: UiFailure) : ExportPhase

    data object Cancelled : ExportPhase
}

data class ExportUiState(
    val loading: Boolean = true,
    val loadFailure: UiFailure? = null,
    val project: TimeFlipProject? = null,
    val phase: ExportPhase = ExportPhase.Idle,
    val previous: List<ExportedVideo> = emptyList(),
)

/**
 * Runs one export for the project and records a finished file. The export starts once per screen
 * entry; after process death the screen shows Idle, and the user starts it again.
 */
class ExportViewModel(
    savedStateHandle: SavedStateHandle,
    private val projects: ProjectRepository,
    private val exporter: VideoExportEngine,
) : ViewModel() {

    private val handle: SavedStateHandle = savedStateHandle
    private val projectId = ProjectId(checkNotNull(handle.get<String>(ROUTE_ARG_PROJECT_ID)))

    private val _state = MutableStateFlow(ExportUiState())
    val state: StateFlow<ExportUiState> = _state.asStateFlow()

    private var project: TimeFlipProject? = null
    private var job: Job? = null

    init {
        load()
    }

    /** Retries whichever step failed: loading the project, or the export. */
    fun retry() {
        if (project == null) load() else start()
    }

    /** Starts an export unless one is already running. The inputs are unchanged, so retry is safe. */
    fun start() {
        if (job?.isActive == true) return
        val current = project ?: return
        val request = current.toExportRequest()
        handle[KEY_STARTED] = true
        _state.update {
            it.copy(phase = ExportPhase.Rendering(framesDone = 0, framesTotal = request.timeline.frameCount))
        }
        job = viewModelScope.launch {
            try {
                exporter.export(request).collect { progress -> onProgress(progress) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = ExportPhase.Failed(e.toUiFailure())) }
            }
        }
    }

    /** Cancels a running export. The engine deletes its partial file. Ignored once recording starts. */
    fun cancel() {
        val running = job ?: return
        if (_state.value.phase !is ExportPhase.Rendering) return
        running.cancel()
        job = null
        _state.update { it.copy(phase = ExportPhase.Cancelled) }
    }

    private suspend fun onProgress(progress: ExportProgress) {
        when (progress) {
            is ExportProgress.Rendering -> _state.update {
                it.copy(phase = ExportPhase.Rendering(progress.framesDone, progress.framesTotal))
            }
            is ExportProgress.Finished -> recordFinished(progress.video)
            is ExportProgress.Failed -> _state.update {
                it.copy(phase = ExportPhase.Failed(UiFailure.Known(progress.error)))
            }
            ExportProgress.Cancelled -> _state.update { it.copy(phase = ExportPhase.Cancelled) }
        }
    }

    private suspend fun recordFinished(video: ExportedVideo) {
        _state.update { it.copy(phase = ExportPhase.Recording) }
        try {
            withContext(NonCancellable) { projects.recordExport(video) }
            val previous = projects.listExports(projectId)
            _state.update { it.copy(phase = ExportPhase.Finished(video), previous = previous) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(phase = ExportPhase.Failed(e.toUiFailure())) }
        }
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val loaded = projects.getProject(projectId)
                    ?: throw TimeFlipException(TimeFlipError(ErrorCategory.NOT_FOUND))
                project = loaded
                val previous = projects.listExports(projectId)
                _state.update { it.copy(loading = false, loadFailure = null, project = loaded, previous = previous) }
                if (handle.get<Boolean>(KEY_STARTED) != true) start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadFailure = e.toUiFailure()) }
            }
        }
    }

    companion object {
        private const val KEY_STARTED = "export.started"
    }
}
