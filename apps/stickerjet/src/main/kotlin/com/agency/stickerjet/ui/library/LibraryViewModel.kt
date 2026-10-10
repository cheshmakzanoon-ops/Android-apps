package com.agency.stickerjet.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agency.stickerjet.domain.ExportRecord
import com.agency.stickerjet.domain.ProjectRepository
import com.agency.stickerjet.domain.StickerProject
import com.agency.stickerjet.ui.ports.ProjectFilesPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One draft shown as a library tile. [sourceMissing] is true when the original video file is gone. */
data class LibraryItem(
    val project: StickerProject,
    val thumbnail: File,
    val sourceMissing: Boolean,
)

/** One export shown in the recent-exports row. [fileExists] is false when the shared file was removed. */
data class RecentExport(
    val projectId: String,
    val projectTitle: String,
    val record: ExportRecord,
    val file: File,
    val fileExists: Boolean,
)

/** One-shot messages from the library. The screen chooses the words from string resources. */
enum class LibraryNotice { DELETED, DELETE_FAILED }

/** Everything the library screen draws. */
data class LibraryUiState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val items: List<LibraryItem> = emptyList(),
    val recentExports: List<RecentExport> = emptyList(),
    val pendingDelete: LibraryItem? = null,
    val notice: LibraryNotice? = null,
)

/**
 * Library state: drafts, recent exports, and delete with confirmation. Public functions launch work
 * on [viewModelScope]; the internal suspend functions hold the logic and are what the JVM tests call.
 */
class LibraryViewModel(
    private val projects: ProjectRepository,
    private val files: ProjectFilesPort,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())

    /** Current library state. */
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    /** Reloads drafts and exports. Called on first show and each time the screen resumes. */
    fun refresh() {
        viewModelScope.launch { reload() }
    }

    /** Opens the delete confirmation for [item]. Nothing is deleted yet. */
    fun requestDelete(item: LibraryItem) {
        _state.update { it.copy(pendingDelete = item, notice = null) }
    }

    /** Closes the confirmation without deleting. */
    fun cancelDelete() {
        _state.update { it.copy(pendingDelete = null) }
    }

    /** Deletes the pending draft and every file it owns. */
    fun confirmDelete() {
        val target = _state.value.pendingDelete ?: return
        viewModelScope.launch { delete(target.project.id) }
    }

    /** Clears a shown notice after the screen has displayed it. */
    fun clearNotice() {
        _state.update { it.copy(notice = null) }
    }

    internal suspend fun reload() {
        _state.update { it.copy(loading = true, loadFailed = false) }
        try {
            val drafts = projects.list()
            val (items, exports) = withContext(Dispatchers.IO) { buildRows(drafts) }
            _state.update { it.copy(loading = false, items = items, recentExports = exports) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, loadFailed = true) }
        }
    }

    internal suspend fun delete(projectId: String) {
        try {
            projects.delete(projectId)
            _state.update { it.copy(pendingDelete = null, notice = LibraryNotice.DELETED) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(pendingDelete = null, notice = LibraryNotice.DELETE_FAILED) }
        }
        reload()
    }

    private fun buildRows(drafts: List<StickerProject>): Pair<List<LibraryItem>, List<RecentExport>> {
        val sorted = drafts.sortedByDescending { it.updatedAtMs }
        val items = sorted.map { project ->
            LibraryItem(
                project = project,
                thumbnail = files.thumbnailFile(project.id),
                sourceMissing = !files.sourceFile(project.id).isFile,
            )
        }
        val exports = sorted
            .flatMap { project ->
                project.exports.map { record ->
                    val file = File(files.exportsDir(project.id), record.fileName)
                    RecentExport(
                        projectId = project.id,
                        projectTitle = project.title,
                        record = record,
                        file = file,
                        fileExists = file.isFile,
                    )
                }
            }
            .sortedByDescending { it.record.createdAtMs }
            .take(RECENT_EXPORT_LIMIT)
        return items to exports
    }

    companion object {
        /** How many exports the recent row shows. The full list is not kept in memory. */
        const val RECENT_EXPORT_LIMIT: Int = 6
    }
}
