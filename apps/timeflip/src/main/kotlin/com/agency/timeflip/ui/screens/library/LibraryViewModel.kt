package com.agency.timeflip.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agency.timeflip.domain.model.AlignmentSource
import com.agency.timeflip.domain.model.ProjectId
import com.agency.timeflip.domain.model.ProjectSummary
import com.agency.timeflip.domain.port.ProjectRepository
import com.agency.timeflip.domain.rules.ProjectRules
import com.agency.timeflip.ui.common.UiFailure
import com.agency.timeflip.ui.common.toUiFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface LibraryContent {
    data object Loading : LibraryContent

    data object Empty : LibraryContent

    data class Ready(val rows: List<ProjectSummary>) : LibraryContent

    data class Failed(val failure: UiFailure) : LibraryContent
}

sealed interface LibraryDialog {
    data object None : LibraryDialog

    data class Rename(val id: ProjectId, val draftName: String) : LibraryDialog

    data class ConfirmDelete(val id: ProjectId, val name: String) : LibraryDialog
}

/** One-shot messages. The screen maps them to text and calls [LibraryViewModel.consumeMessage]. */
enum class LibraryMessage { RENAMED, DELETED, DUPLICATED, ACTION_FAILED, PROJECT_MISSING }

/** Where a tapped project leads: unaligned projects go to alignment review first. */
enum class OpenTarget { ALIGN_REVIEW, EDITOR }

data class LibraryUiState(
    val content: LibraryContent = LibraryContent.Loading,
    val dialog: LibraryDialog = LibraryDialog.None,
    val working: Boolean = false,
    val message: LibraryMessage? = null,
)

class LibraryViewModel(
    private val projects: ProjectRepository,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> ProjectId = { ProjectId(UUID.randomUUID().toString()) },
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
        observeLibrary()
    }

    /** Subscribes again after a failed read. */
    fun retry() {
        _state.update { it.copy(content = LibraryContent.Loading) }
        observeLibrary()
    }

    private fun observeLibrary() {
        viewModelScope.launch {
            projects.observeProjects()
                .map { rows -> rows.toContent() }
                .catch { cause -> emit(LibraryContent.Failed(cause.toUiFailure())) }
                .collect { content -> _state.update { it.copy(content = content) } }
        }
    }

    private fun List<ProjectSummary>.toContent(): LibraryContent =
        if (isEmpty()) LibraryContent.Empty else LibraryContent.Ready(this)

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    fun openProject(id: ProjectId, onResolved: (OpenTarget) -> Unit) {
        viewModelScope.launch {
            try {
                val project = projects.getProject(id)
                if (project == null) {
                    _state.update { it.copy(message = LibraryMessage.PROJECT_MISSING) }
                } else {
                    val target = if (project.alignmentSource == AlignmentSource.UNALIGNED) {
                        OpenTarget.ALIGN_REVIEW
                    } else {
                        OpenTarget.EDITOR
                    }
                    onResolved(target)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = LibraryMessage.ACTION_FAILED) }
            }
        }
    }

    fun startRename(row: ProjectSummary) {
        _state.update { it.copy(dialog = LibraryDialog.Rename(row.id, row.name)) }
    }

    fun changeRenameDraft(text: String) {
        val dialog = _state.value.dialog as? LibraryDialog.Rename ?: return
        _state.update { it.copy(dialog = dialog.copy(draftName = text.take(ProjectRules.MAX_NAME_LENGTH))) }
    }

    fun confirmRename() {
        val dialog = _state.value.dialog as? LibraryDialog.Rename ?: return
        if (dialog.draftName.isBlank()) return
        runOperation {
            val current = projects.getProject(dialog.id) ?: return@runOperation LibraryMessage.PROJECT_MISSING
            projects.saveProject(ProjectRules.rename(current, dialog.draftName, now()))
            LibraryMessage.RENAMED
        }
    }

    fun startDelete(row: ProjectSummary) {
        _state.update { it.copy(dialog = LibraryDialog.ConfirmDelete(row.id, row.name)) }
    }

    fun confirmDelete() {
        val dialog = _state.value.dialog as? LibraryDialog.ConfirmDelete ?: return
        runOperation {
            projects.deleteProject(dialog.id)
            LibraryMessage.DELETED
        }
    }

    fun duplicate(id: ProjectId) {
        runOperation {
            projects.duplicateProject(id, newId(), now())
            LibraryMessage.DUPLICATED
        }
    }

    fun dismissDialog() {
        _state.update { it.copy(dialog = LibraryDialog.None) }
    }

    private fun runOperation(block: suspend () -> LibraryMessage) {
        if (_state.value.working) return
        _state.update { it.copy(working = true) }
        viewModelScope.launch {
            val message = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LibraryMessage.ACTION_FAILED
            }
            _state.update { it.copy(working = false, dialog = LibraryDialog.None, message = message) }
        }
    }
}
