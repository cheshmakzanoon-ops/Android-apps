package com.agency.stickerjet.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agency.stickerjet.ui.ports.LibraryMaintenancePort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Progress of one settings action. */
enum class ActionState { IDLE, WORKING, DONE, FAILED }

/** Each cleanup action has its own state, so one result never hides another. */
data class SettingsUiState(
    val cleanup: ActionState = ActionState.IDLE,
    val delete: ActionState = ActionState.IDLE,
)

/** Settings actions: clear temporary files, and delete all data. Deletion is confirmed by the screen first. */
class SettingsViewModel(
    private val maintenance: LibraryMaintenancePort,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())

    /** Current state of the settings actions. */
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /** Removes temporary copies from failed or cancelled work. Drafts and exports stay. */
    fun clearTemporaryFiles() {
        viewModelScope.launch { clearTemporary() }
    }

    /** Deletes every draft, original video, thumbnail and export. The screen must confirm first. */
    fun deleteEverything() {
        viewModelScope.launch { deleteAll() }
    }

    internal suspend fun clearTemporary() {
        _state.update { it.copy(cleanup = ActionState.WORKING) }
        val ok = attempt { maintenance.clearTemporaryFiles() }
        _state.update { it.copy(cleanup = if (ok) ActionState.DONE else ActionState.FAILED) }
    }

    internal suspend fun deleteAll() {
        _state.update { it.copy(delete = ActionState.WORKING) }
        val ok = attempt { maintenance.deleteEverything() }
        _state.update { it.copy(delete = if (ok) ActionState.DONE else ActionState.FAILED) }
    }

    private suspend fun attempt(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
}
