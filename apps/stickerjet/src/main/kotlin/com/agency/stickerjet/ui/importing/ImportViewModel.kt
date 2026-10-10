package com.agency.stickerjet.ui.importing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agency.stickerjet.domain.ImportRejection
import com.agency.stickerjet.ui.ports.ClipImportPort
import com.agency.stickerjet.ui.ports.ImportFailureKind
import com.agency.stickerjet.ui.ports.ImportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the import flow is. [Imported] tells the screen to open the editor for that project. */
sealed interface ImportPhase {
    data object Idle : ImportPhase
    data object Importing : ImportPhase
    data class Rejected(val reason: ImportRejection) : ImportPhase
    data class Failed(val cause: ImportFailureKind) : ImportPhase
    data object Unsupported : ImportPhase
    data class Imported(val projectId: String) : ImportPhase
}

/** The import screen state. [lastSource] is kept so a failed import can be retried without the picker. */
data class ImportUiState(
    val phase: ImportPhase = ImportPhase.Idle,
    val lastSource: String? = null,
)

/**
 * Drives the import flow. [newProjectId] is injected so tests get predictable ids. A cancelled
 * import returns to Idle; a rejected clip never leaves files behind (handled by the port).
 */
class ImportViewModel(
    private val importer: ClipImportPort,
    private val newProjectId: () -> String,
) : ViewModel() {

    private val _state = MutableStateFlow(ImportUiState())

    /** Current import state. */
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    private var job: Job? = null

    /** Called with the picked content URI, or null when the picker was dismissed. */
    fun onPicked(contentUri: String?) {
        if (contentUri == null) return
        start(contentUri)
    }

    /** Repeats the last import. */
    fun retry() {
        val source = _state.value.lastSource ?: return
        start(source)
    }

    /** The system picker is not available on this device. */
    fun onUnsupported() {
        _state.update { it.copy(phase = ImportPhase.Unsupported) }
    }

    /** Stops an import in progress and returns to Idle. */
    fun cancel() {
        job?.cancel()
        job = null
        _state.update { it.copy(phase = ImportPhase.Idle) }
    }

    private fun start(contentUri: String) {
        job?.cancel()
        job = viewModelScope.launch { importClip(contentUri) }
    }

    internal suspend fun importClip(contentUri: String) {
        _state.update { it.copy(phase = ImportPhase.Importing, lastSource = contentUri) }
        val projectId = newProjectId()
        val result = try {
            importer.import(contentUri, projectId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ImportResult.Failed(ImportFailureKind.IO_ERROR)
        }
        _state.update { it.copy(phase = result.toPhase()) }
    }

    private fun ImportResult.toPhase(): ImportPhase = when (this) {
        is ImportResult.Imported -> ImportPhase.Imported(project.id)
        is ImportResult.Rejected -> ImportPhase.Rejected(reason)
        is ImportResult.Failed -> ImportPhase.Failed(cause)
    }
}
