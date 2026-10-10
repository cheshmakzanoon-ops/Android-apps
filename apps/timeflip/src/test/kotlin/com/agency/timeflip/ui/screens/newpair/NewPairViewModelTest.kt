package com.agency.timeflip.ui.screens.newpair

import androidx.lifecycle.SavedStateHandle
import com.agency.timeflip.domain.error.ErrorCategory
import com.agency.timeflip.domain.error.TimeFlipError
import com.agency.timeflip.domain.model.AlignmentSource
import com.agency.timeflip.domain.model.AlignmentTransform
import com.agency.timeflip.domain.model.PhotoSlot
import com.agency.timeflip.domain.model.PhotoSourceRef
import com.agency.timeflip.domain.model.ProjectId
import com.agency.timeflip.domain.rules.ProjectRules
import com.agency.timeflip.domain.testing.FakePhotoStore
import com.agency.timeflip.domain.testing.InMemoryProjectRepository
import com.agency.timeflip.ui.MainDispatcherRule
import com.agency.timeflip.ui.common.ROUTE_ARG_PROJECT_ID
import com.agency.timeflip.ui.common.UiFailure
import com.agency.timeflip.ui.importPair
import com.agency.timeflip.ui.newTestProject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NewPairViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repo = InMemoryProjectRepository()
    private val photos = FakePhotoStore()

    private fun viewModel(editProjectId: String? = null): NewPairViewModel {
        val args = editProjectId?.let { mapOf<String, Any?>(ROUTE_ARG_PROJECT_ID to it) } ?: emptyMap()
        return NewPairViewModel(
            savedStateHandle = SavedStateHandle(args),
            projects = repo,
            photos = photos,
            now = { 1_000L },
            newId = { ProjectId("project-1") },
        )
    }

    @Test
    fun choosingBothPhotosCreatesTheProjectAndReportsItReadyForAlignment() = runTest {
        val vm = viewModel()

        vm.onPicked(PhotoSlot.BEFORE, PhotoSourceRef("picker://before"))
        assertNull("one photo is not enough to create a project", vm.state.value.readyProjectId)

        vm.onPicked(PhotoSlot.AFTER, PhotoSourceRef("picker://after"))

        assertEquals(ProjectId("project-1"), vm.state.value.readyProjectId)
        val saved = repo.getProject(ProjectId("project-1"))
        assertEquals(ProjectRules.DEFAULT_NAME, saved?.name)
        assertEquals(AlignmentSource.UNALIGNED, saved?.alignmentSource)
        assertEquals(AlignmentTransform.IDENTITY, saved?.alignment)
        assertEquals(PhotoSlot.BEFORE, saved?.pair?.before?.slot)
        assertEquals(PhotoSlot.AFTER, saved?.pair?.after?.slot)

        vm.consumeReady()
        assertNull(vm.state.value.readyProjectId)
    }

    @Test
    fun importErrorIsReportedWithItsCategoryAndRetryKeepsTheSameSource() = runTest {
        val vm = viewModel()

        vm.onPicked(PhotoSlot.BEFORE, PhotoSourceRef(""))

        val failure = vm.state.value.importFailure
        assertEquals(PhotoSlot.BEFORE, failure?.slot)
        assertEquals(UiFailure.Known(TimeFlipError(ErrorCategory.UNSUPPORTED_FORMAT)), failure?.failure)
        assertFalse(vm.state.value.canContinue)

        vm.retryImport()
        assertEquals(PhotoSourceRef(""), vm.state.value.importFailure?.source)
    }

    @Test
    fun cancelDeletesCopiesThatNoSavedProjectUses() = runTest {
        val vm = viewModel()
        vm.onPicked(PhotoSlot.BEFORE, PhotoSourceRef("picker://before"))
        val copyId = vm.state.value.before?.id
        assertTrue(copyId != null && photos.originalPath(copyId) != null)

        var done = false
        vm.cancel { done = true }

        assertTrue(done)
        assertNull(photos.originalPath(copyId!!))
        assertNull(repo.getProject(ProjectId("project-1")))
    }

    @Test
    fun editingSwapsThePhotosAndResetsTheAlignment() = runTest {
        val original = importPair(photos)
        repo.saveProject(newTestProject("project-1", original).copy(alignmentSource = AlignmentSource.MANUAL))
        val vm = viewModel(editProjectId = "project-1")

        vm.swap()
        vm.continueToAlignment()

        val saved = repo.getProject(ProjectId("project-1"))!!
        assertEquals(original.after.id, saved.pair.before.id)
        assertEquals(original.before.id, saved.pair.after.id)
        assertEquals(PhotoSlot.BEFORE, saved.pair.before.slot)
        assertEquals(AlignmentSource.UNALIGNED, saved.alignmentSource)
        assertEquals(ProjectId("project-1"), vm.state.value.readyProjectId)
    }

    @Test
    fun editingReplacesOnePhotoAndDeletesTheReplacedCopyAfterSaving() = runTest {
        val original = importPair(photos)
        repo.saveProject(newTestProject("project-1", original).copy(alignmentSource = AlignmentSource.MANUAL))
        val vm = viewModel(editProjectId = "project-1")
        assertFalse(vm.state.value.loading)

        vm.onPicked(PhotoSlot.BEFORE, PhotoSourceRef("picker://replacement"))
        vm.continueToAlignment()

        val saved = repo.getProject(ProjectId("project-1"))!!
        assertTrue(saved.pair.before.id != original.before.id)
        assertEquals(original.after.id, saved.pair.after.id)
        assertEquals(AlignmentSource.UNALIGNED, saved.alignmentSource)
        assertNull("the replaced original copy is removed after the save", photos.originalPath(original.before.id))
        assertTrue(photos.originalPath(original.after.id) != null)
    }

    @Test
    fun editingWithoutPhotoChangesKeepsTheAlignment() = runTest {
        val pair = importPair(photos)
        repo.saveProject(newTestProject("project-1", pair).copy(alignmentSource = AlignmentSource.MANUAL))
        val vm = viewModel(editProjectId = "project-1")

        vm.continueToAlignment()

        assertEquals(AlignmentSource.MANUAL, repo.getProject(ProjectId("project-1"))?.alignmentSource)
        assertEquals(ProjectId("project-1"), vm.state.value.readyProjectId)
    }

    @Test
    fun missingOriginalCopyIsReportedForEditing() = runTest {
        val pair = importPair(photos)
        repo.saveProject(newTestProject("project-1", pair))
        photos.delete(pair.after.id)

        val vm = viewModel(editProjectId = "project-1")

        assertEquals(setOf(PhotoSlot.AFTER), vm.state.value.missingSlots)
    }

    @Test
    fun unknownProjectIdShowsNotFoundInEditMode() = runTest {
        val vm = viewModel(editProjectId = "does-not-exist")

        assertEquals(
            UiFailure.Known(TimeFlipError(ErrorCategory.NOT_FOUND)),
            vm.state.value.loadFailure,
        )
        assertFalse(vm.state.value.canContinue)
    }
}
