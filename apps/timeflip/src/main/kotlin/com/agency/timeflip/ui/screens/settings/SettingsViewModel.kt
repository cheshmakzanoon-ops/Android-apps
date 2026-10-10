package com.agency.timeflip.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agency.timeflip.domain.port.ProjectRepository
import com.agency.timeflip.ui.common.UiFailure
import com.agency.timeflip.ui.common.toUiFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val confirmDelete: Boolean = false,
    val working: Boolean = false,
    val deleted: Boolean = false,
    val failure: UiFailure? = null,
)

class SettingsViewModel(private val projects: ProjectRepository) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun requestDeleteAll() {
        _state.update { it.copy(confirmDelete = true) }
    }

    fun dismissDeleteAll() {
        _state.update { it.copy(confirmDelete = false) }
    }

    /** Removes every project, photo copy, thumbnail and export through the repository. */
    fun deleteAll() {
        if (_state.value.working) return
        _state.update { it.copy(working = true, confirmDelete = false, failure = null) }
        viewModelScope.launch {
            try {
                withContext(NonCancellable) { projects.deleteAllData() }
                _state.update { it.copy(working = false, deleted = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(working = false, deleted = false, failure = e.toUiFailure()) }
            }
        }
    }
}
