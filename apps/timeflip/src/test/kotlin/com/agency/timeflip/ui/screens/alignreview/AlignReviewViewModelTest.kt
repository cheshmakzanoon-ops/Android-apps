package com.agency.timeflip.ui.screens.alignreview

import androidx.lifecycle.SavedStateHandle
import com.agency.timeflip.domain.error.ErrorCategory
import com.agency.timeflip.domain.error.TimeFlipError
import com.agency.timeflip.domain.image.GrayRaster
import com.agency.timeflip.domain.model.AlignmentSource
import com.agency.timeflip.domain.model.AlignmentStatus
import com.agency.timeflip.domain.model.ProjectId
import com.agency.timeflip.domain.testing.FakeAlignmentEngine
import com.agency.timeflip.domain.testing.FakePhotoStore
import com.agency.timeflip.domain.testing.InMemoryProjectRepository
import com.agency.timeflip.ui.MainDispatcherRule
import com.agency.timeflip.ui.common.ROUTE_ARG_PROJECT_ID
import com.agency.timeflip.ui.common.UiFailure
import com.agency.timeflip.ui.importPair
import com.agency.timeflip.ui.newTestProject
import com.agency.timeflip.ui.ports.AlignmentRunner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AlignReviewViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repo = InMemoryProjectRepository()
    private val photos = FakePhotoStore()

    /** Adapts the domain fake to the UI-side runner, the same way the composition root adapts AutoAligner. */
    private fun runnerFrom(engine: FakeAlignmentEngine) = AlignmentRunner { _, frame ->
        val raster = GrayRaster(1, 1, floatArrayOf(0f))
        engine.estimate(raster, raster, frame)
    }

    private fun viewModel(engine: FakeAlignmentEngine) = AlignReviewViewModel(
        savedStateHandle = SavedStateHandle(mapOf<String, Any?>(ROUTE_ARG_PROJECT_ID to "project-1")),
        projects = repo,
        photos = photos,
        aligner = runnerFrom(engine),
        now = { 3_000L },
    )

    @Test
    fun acceptedEstimateIsSavedOnlyWhenTheUserChoosesIt() = runTest {
        repo.saveProject(newTestProject("project-1", importPair(photos)))
        val vm = viewModel(FakeAlignmentEngine(confidence = 0.85))

        assertEquals(AlignmentStatus.ACCEPTED, (vm.state.value.phase as AlignPhase.Estimated).estimate.status)
        assertEquals(AlignmentSource.UNALIGNED, repo.getProject(ProjectId("project-1"))?.alignmentSource)

        var continued = false
        vm.acceptEstimate { continued = true }

        assertTrue(continued)
        val saved = repo.getProject(ProjectId("project-1"))!!
        assertEquals(AlignmentSource.AUTOMATIC, saved.alignmentSource)
        assertEquals(0.85, saved.alignmentConfidence!!, 1e-9)
    }

    @Test
    fun lowConfidenceEstimateIsNeverAppliedAutomatically() = runTest {
        repo.saveProject(newTestProject("project-1", importPair(photos)))
        val vm = viewModel(FakeAlignmentEngine(confidence = 0.3))

        assertEquals(AlignmentStatus.NEEDS_MANUAL, (vm.state.value.phase as AlignPhase.Estimated).estimate.status)

        var continued = false
        vm.acceptEstimate { continued = true }
        assertFalse(continued)
        assertEquals(AlignmentSource.UNALIGNED, repo.getProject(ProjectId("project-1"))?.alignmentSource)

        var opened = false
        vm.openEditorWithoutEstimate { opened = true }
        assertTrue(opened)
        assertEquals(AlignmentSource.UNALIGNED, repo.getProject(ProjectId("project-1"))?.alignmentSource)
    }

    @Test
    fun adjustingManuallyStartsFromTheLowConfidenceEstimate() = runTest {
        repo.saveProject(newTestProject("project-1", importPair(photos)))
        val vm = viewModel(FakeAlignmentEngine(confidence = 0.3))

        var adjusted = false
        vm.adjustManually { adjusted = true }

        assertTrue(adjusted)
        assertEquals(AlignmentSource.AUTOMATIC, repo.getProject(ProjectId("project-1"))?.alignmentSource)
    }

    @Test
    fun missingPhotoCopyFailsWithSourceMissingInsteadOfAligning() = runTest {
        repo.saveProject(newTestProject("project-1", importPair(photos)))
        val copyId = repo.getProject(ProjectId("project-1"))!!.pair.after.id
        photos.delete(copyId)

        val vm = viewModel(FakeAlignmentEngine())

        assertEquals(
            AlignPhase.Failed(UiFailure.Known(TimeFlipError(ErrorCategory.SOURCE_MISSING))),
            vm.state.value.phase,
        )
    }

    @Test
    fun resetClearsTheSavedAlignmentAndReturnsToNotRun() = runTest {
        repo.saveProject(newTestProject("project-1", importPair(photos)))
        val vm = viewModel(FakeAlignmentEngine())
        vm.acceptEstimate { }

        vm.resetAlignment()

        assertEquals(AlignPhase.NotRun, vm.state.value.phase)
        assertEquals(AlignmentSource.UNALIGNED, repo.getProject(ProjectId("project-1"))?.alignmentSource)
    }
}
