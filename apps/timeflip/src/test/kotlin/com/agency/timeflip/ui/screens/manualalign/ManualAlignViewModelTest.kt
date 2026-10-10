package com.agency.timeflip.ui.screens.manualalign

import androidx.lifecycle.SavedStateHandle
import com.agency.timeflip.domain.geometry.PhotoPlacement
import com.agency.timeflip.domain.model.AlignmentSource
import com.agency.timeflip.domain.model.AlignmentTransform
import com.agency.timeflip.domain.model.PixelSize
import com.agency.timeflip.domain.model.ProjectId
import com.agency.timeflip.domain.testing.FakePhotoStore
import com.agency.timeflip.domain.testing.InMemoryProjectRepository
import com.agency.timeflip.ui.MainDispatcherRule
import com.agency.timeflip.ui.common.ROUTE_ARG_PROJECT_ID
import com.agency.timeflip.ui.importPair
import com.agency.timeflip.ui.newTestProject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ManualAlignViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repo = InMemoryProjectRepository()
    private val photos = FakePhotoStore()

    private suspend fun viewModel(): ManualAlignViewModel {
        repo.saveProject(newTestProject("project-1", importPair(photos)))
        return ManualAlignViewModel(
            savedStateHandle = SavedStateHandle(mapOf<String, Any?>(ROUTE_ARG_PROJECT_ID to "project-1")),
            projects = repo,
            now = { 2_000L },
            saveDebounceMs = 0L,
        )
    }

    @Test
    fun undoAndRedoStepThroughCommittedGesturesAndSlidersInOrder() = runTest {
        val vm = viewModel()

        vm.onGestureDelta(zoom = 1.5, rotationDeltaDegrees = 0.0, panFractionX = 0.0, panFractionY = 0.0)
        vm.onGestureEnd()
        vm.onRotationChange(10.0)
        vm.onSliderFinished()
        assertEquals(1.5, vm.state.value.transform.scale, 1e-9)
        assertEquals(10.0, vm.state.value.transform.rotationDegrees, 1e-9)
        assertTrue(vm.state.value.canUndo)
        assertFalse(vm.state.value.canRedo)

        vm.undo()
        assertEquals(1.5, vm.state.value.transform.scale, 1e-9)
        assertEquals(0.0, vm.state.value.transform.rotationDegrees, 1e-9)
        assertTrue(vm.state.value.canRedo)

        vm.undo()
        assertEquals(AlignmentTransform.IDENTITY, vm.state.value.transform)
        assertFalse(vm.state.value.canUndo)

        vm.redo()
        assertEquals(1.5, vm.state.value.transform.scale, 1e-9)
        vm.redo()
        assertEquals(10.0, vm.state.value.transform.rotationDegrees, 1e-9)
        assertFalse(vm.state.value.canRedo)
    }

    @Test
    fun aNewCommitAfterUndoDiscardsTheRedoBranch() = runTest {
        val vm = viewModel()
        vm.onScaleChange(2.0)
        vm.onSliderFinished()
        vm.undo()
        assertTrue(vm.state.value.canRedo)

        vm.onScaleChange(3.0)
        vm.onSliderFinished()

        assertFalse(vm.state.value.canRedo)
        assertEquals(3.0, vm.state.value.transform.scale, 1e-9)
    }

    @Test
    fun gesturesAreClampedToTheLegalRange() = runTest {
        val vm = viewModel()

        vm.onGestureDelta(zoom = 100.0, rotationDeltaDegrees = 500.0, panFractionX = 5.0, panFractionY = -5.0)

        val t = vm.state.value.transform
        assertEquals(AlignmentTransform.MAX_SCALE, t.scale, 1e-9)
        assertEquals(AlignmentTransform.MAX_ROTATION_DEGREES, t.rotationDegrees, 1e-9)
        assertEquals(AlignmentTransform.MAX_OFFSET, t.offsetX, 1e-9)
        assertEquals(-AlignmentTransform.MAX_OFFSET, t.offsetY, 1e-9)
    }

    @Test
    fun finishPersistsTheManualAlignmentToTheRepository() = runTest {
        val vm = viewModel()
        vm.onScaleChange(1.25)
        vm.onSliderFinished()

        var done = false
        vm.finish { done = true }

        assertTrue(done)
        val saved = repo.getProject(ProjectId("project-1"))!!
        assertEquals(1.25, saved.alignment.scale, 1e-9)
        assertEquals(AlignmentSource.MANUAL, saved.alignmentSource)
    }

    @Test
    fun workingTransformIsRestoredFromSavedStateAfterProcessDeath() = runTest {
        repo.saveProject(newTestProject("project-1", importPair(photos)))
        val handle = SavedStateHandle(mapOf<String, Any?>(ROUTE_ARG_PROJECT_ID to "project-1"))
        val first = ManualAlignViewModel(handle, repo, now = { 2_000L }, saveDebounceMs = 10_000L)
        first.onRotationChange(7.0)

        val restored = ManualAlignViewModel(handle, repo, now = { 2_000L }, saveDebounceMs = 10_000L)

        assertEquals(7.0, restored.state.value.transform.rotationDegrees, 1e-9)
    }

    @Test
    fun coverageWarningAppearsWhenTheAfterPhotoLeavesEmptyEdges() = runTest {
        val vm = viewModel()
        assertFalse(vm.state.value.hasEmptyEdges)

        vm.onOverlayOpacityChange(0.25f)
        vm.onGestureDelta(zoom = 1.0, rotationDeltaDegrees = 0.0, panFractionX = 0.4, panFractionY = 0.0)

        val state = vm.state.value
        assertEquals(0.25f, state.overlayOpacity, 1e-6f)
        assertTrue(state.hasEmptyEdges)
        assertTrue(state.coverage < 1.0)
        val frame = PixelSize(720, 1280)
        assertEquals(
            PhotoPlacement.coverage(PixelSize(1200, 1600), frame, state.transform),
            state.coverage,
            1e-9,
        )
    }
}
