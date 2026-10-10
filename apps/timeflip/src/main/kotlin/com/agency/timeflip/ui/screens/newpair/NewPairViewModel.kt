package com.agency.timeflip.ui.screens.newpair

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agency.timeflip.domain.error.ErrorCategory
import com.agency.timeflip.domain.error.TimeFlipError
import com.agency.timeflip.domain.error.TimeFlipException
import com.agency.timeflip.domain.model.ImagePair
import com.agency.timeflip.domain.model.PhotoId
import com.agency.timeflip.domain.model.PhotoSlot
import com.agency.timeflip.domain.model.PhotoSourceRef
import com.agency.timeflip.domain.model.ProjectId
import com.agency.timeflip.domain.model.SourcePhoto
import com.agency.timeflip.domain.model.TimeFlipProject
import com.agency.timeflip.domain.port.PhotoStore
import com.agency.timeflip.domain.port.ProjectRepository
import com.agency.timeflip.domain.rules.ProjectRules
import com.agency.timeflip.ui.common.ROUTE_ARG_PROJECT_ID
import com.agency.timeflip.ui.common.UiFailure
import com.agency.timeflip.ui.common.toUiFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ImportFailure(
    val slot: PhotoSlot,
    val source: PhotoSourceRef,
    val failure: UiFailure,
)

data class NewPairUiState(
    val editing: Boolean = false,
    val loading: Boolean = false,
    val loadFailure: UiFailure? = null,
    val missingSlots: Set<PhotoSlot> = emptySet(),
    val before: SourcePhoto? = null,
    val after: SourcePhoto? = null,
    val importing: PhotoSlot? = null,
    val importFailure: ImportFailure? = null,
    val saving: Boolean = false,
    val saveFailure: UiFailure? = null,
    /** Set once the project is saved. The screen opens alignment, then calls consumeReady(). */
    val readyProjectId: ProjectId? = null,
) {
    val canContinue: Boolean
        get() = before != null && after != null && importing == null && !saving && !loading && loadFailure == null
}

/**
 * Chooses the BEFORE and AFTER photos and creates (or updates) the project.
 *
 * A new transformation is created as soon as both photos are imported, so the wizard goes from
 * choosing the photos straight to alignment. An existing project changes only through [continueToAlignment].
 *
 * Photo copies are made by [PhotoStore.importPhoto]. Copies made in this session are tracked, and
 * any that no saved project uses are deleted when they are replaced, when the screen is cancelled,
 * or when the project is saved.
 */
class NewPairViewModel(
    savedStateHandle: SavedStateHandle,
    private val projects: ProjectRepository,
    private val photos: PhotoStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> ProjectId = { ProjectId(UUID.randomUUID().toString()) },
) : ViewModel() {

    /** Present when the screen edits the photos of an existing project (route argument). */
    private val editingId: ProjectId? = savedStateHandle.get<String>(ROUTE_ARG_PROJECT_ID)
        ?.takeIf { it.isNotBlank() }
        ?.let { ProjectId(it) }

    private val _state = MutableStateFlow(
        NewPairUiState(editing = editingId != null, loading = editingId != null),
    )
    val state: StateFlow<NewPairUiState> = _state.asStateFlow()

    /** Copies made in this session that no saved project uses yet. */
    private val pendingImports = mutableSetOf<PhotoId>()

    /** Photos used by the saved project when this screen opened (edit mode). */
    private var originalIds: Set<PhotoId> = emptySet()
    private var loadedProject: TimeFlipProject? = null
    private var importJob: Job? = null

    init {
        val id = editingId
        if (id != null) loadExisting(id)
    }

    fun onPicked(slot: PhotoSlot, source: PhotoSourceRef) {
        startImport(slot, source)
    }

    fun retryImport() {
        val failure = _state.value.importFailure ?: return
        startImport(failure.slot, failure.source)
    }

    fun dismissImportFailure() {
        _state.update { it.copy(importFailure = null) }
    }

    /** Removes a photo. Only new projects can clear a slot; edit mode must keep both photos. */
    fun remove(slot: PhotoSlot) {
        val s = _state.value
        if (s.editing || s.importing != null || s.saving) return
        val previous = photoIn(s, slot) ?: return
        _state.update { it.withPhoto(slot, null) }
        viewModelScope.launch { retire(previous) }
    }

    fun swap() {
        val s = _state.value
        if (s.importing != null || s.saving) return
        _state.update {
            it.copy(
                before = s.after?.copy(slot = PhotoSlot.BEFORE),
                after = s.before?.copy(slot = PhotoSlot.AFTER),
            )
        }
    }

    /**
     * Creates the project, or applies the photo changes to the existing one. Sets
     * [NewPairUiState.readyProjectId] on success. Alignment is reset only when a photo really changed.
     */
    fun continueToAlignment() {
        val s = _state.value
        val before = s.before ?: return
        val after = s.after ?: return
        if (!s.canContinue || s.saving) return
        _state.update { it.copy(saving = true, saveFailure = null) }
        viewModelScope.launch {
            try {
                val pair = ImagePair(before, after)
                val projectId = if (editingId == null) createProject(pair) else updateProject(pair)
                _state.update { it.copy(saving = false, readyProjectId = projectId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, saveFailure = e.toUiFailure()) }
            }
        }
    }

    /** Called by the screen after it has navigated to alignment, so the navigation runs once. */
    fun consumeReady() {
        _state.update { it.copy(readyProjectId = null) }
    }

    /** Leaves the screen and deletes unsaved copies made in this session. */
    fun cancel(onDone: () -> Unit) {
        importJob?.cancel()
        viewModelScope.launch {
            val unused = pendingImports.toList()
            pendingImports.clear()
            unused.forEach { deleteQuietly(it) }
            onDone()
        }
    }

    private fun startImport(slot: PhotoSlot, source: PhotoSourceRef) {
        if (_state.value.importing != null) return
        _state.update { it.copy(importing = slot, importFailure = null) }
        importJob = viewModelScope.launch {
            try {
                val imported = photos.importPhoto(source, slot, now())
                pendingImports += imported.id
                val previous = photoIn(_state.value, slot)
                _state.update { it.withPhoto(slot, imported).copy(importing = null) }
                retire(previous)
                val s = _state.value
                if (!s.editing && s.before != null && s.after != null) {
                    continueToAlignment()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(importing = null, importFailure = ImportFailure(slot, source, e.toUiFailure()))
                }
            }
        }
    }

    private fun loadExisting(id: ProjectId) {
        viewModelScope.launch {
            try {
                val project = projects.getProject(id)
                    ?: throw TimeFlipException(TimeFlipError(ErrorCategory.NOT_FOUND))
                loadedProject = project
                originalIds = setOf(project.pair.before.id, project.pair.after.id)
                val beforeMissing = photos.originalPath(project.pair.before.id) == null
                val afterMissing = photos.originalPath(project.pair.after.id) == null
                val missing = buildSet {
                    if (beforeMissing) add(PhotoSlot.BEFORE)
                    if (afterMissing) add(PhotoSlot.AFTER)
                }
                _state.update {
                    it.copy(
                        loading = false,
                        before = project.pair.before,
                        after = project.pair.after,
                        missingSlots = missing,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadFailure = e.toUiFailure()) }
            }
        }
    }

    private suspend fun createProject(pair: ImagePair): ProjectId {
        val project = ProjectRules.newProject(
            id = newId(),
            rawName = "",
            pair = pair,
            nowEpochMs = now(),
        )
        projects.saveProject(project)
        deleteUnused(keep = setOf(pair.before.id, pair.after.id))
        return project.id
    }

    private suspend fun updateProject(pair: ImagePair): ProjectId {
        val original = loadedProject ?: throw TimeFlipException(TimeFlipError(ErrorCategory.NOT_FOUND))
        val time = now()
        var updated = original
        if (pair.before.id == original.pair.after.id && pair.after.id == original.pair.before.id) {
            updated = ProjectRules.swapPhotos(updated, time)
        }
        if (pair.before.id != updated.pair.before.id) {
            updated = ProjectRules.replacePhoto(updated, pair.before, time)
        }
        if (pair.after.id != updated.pair.after.id) {
            updated = ProjectRules.replacePhoto(updated, pair.after, time)
        }
        if (updated !== original) projects.saveProject(updated)
        loadedProject = updated
        deleteUnused(keep = setOf(pair.before.id, pair.after.id))
        return original.id
    }

    /** After a successful save: delete every copy that the saved project no longer references. */
    private suspend fun deleteUnused(keep: Set<PhotoId>) {
        val unused = (pendingImports + originalIds) - keep
        pendingImports.clear()
        originalIds = keep
        unused.forEach { deleteQuietly(it) }
    }

    /** Deletes a replaced copy that no saved project uses. Original copies wait until a save. */
    private suspend fun retire(photo: SourcePhoto?) {
        if (photo == null || photo.id !in pendingImports) return
        pendingImports -= photo.id
        deleteQuietly(photo.id)
    }

    private suspend fun deleteQuietly(id: PhotoId) {
        try {
            photos.delete(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort. A copy that survives is removed by the data layer's orphan sweep.
        }
    }

    private fun photoIn(state: NewPairUiState, slot: PhotoSlot): SourcePhoto? =
        if (slot == PhotoSlot.BEFORE) state.before else state.after

    private fun NewPairUiState.withPhoto(slot: PhotoSlot, photo: SourcePhoto?): NewPairUiState =
        if (slot == PhotoSlot.BEFORE) copy(before = photo) else copy(after = photo)
}
