package com.agency.timeflip.ui.screens.export

import androidx.lifecycle.SavedStateHandle
import com.agency.timeflip.domain.error.ErrorCategory
import com.agency.timeflip.domain.error.TimeFlipError
import com.agency.timeflip.domain.model.ExportProgress
import com.agency.timeflip.domain.model.ExportRequest
import com.agency.timeflip.domain.model.ProjectId
import com.agency.timeflip.domain.port.VideoExportEngine
import com.agency.timeflip.domain.testing.FakePhotoStore
import com.agency.timeflip.domain.testing.FakeVideoExportEngine
import com.agency.timeflip.domain.testing.InMemoryProjectRepository
import com.agency.timeflip.ui.MainDispatcherRule
import com.agency.timeflip.ui.common.ROUTE_ARG_PROJECT_ID
import com.agency.timeflip.ui.common.UiFailure
import com.agency.timeflip.ui.importPair
import com.agency.timeflip.ui.newTestProject
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ExportViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repo = InMemoryProjectRepository()
    private val photos = FakePhotoStore()

    /** Renders two frames and then waits, so the test decides when the export ends. */
    private class StalledExporter : VideoExportEngine {
        var cancelled = false
        var starts = 0
        var lastRequest: ExportRequest? = null

        override fun export(request: ExportRequest): Flow<ExportProgress> {
            starts++
            lastRequest = request
            val total = request.timeline.frameCount
            return flow {
                emit(ExportProgress.Rendering(framesDone = 1, framesTotal = total))
                emit(ExportProgress.Rendering(framesDone = 2, framesTotal = total))
                awaitCancellation()
            }.onCompletion { cause -> if (cause != null) cancelled = true }
        }
    }

    private suspend fun seededProject(id: String = "project-1"): ProjectId {
        repo.saveProject(newTestProject(id, importPair(photos)))
        return ProjectId(id)
    }

    private fun viewModel(projectId: ProjectId, exporter: VideoExportEngine) = ExportViewModel(
        savedStateHandle = SavedStateHandle(mapOf<String, Any?>(ROUTE_ARG_PROJECT_ID to projectId.value)),
        projects = repo,
        exporter = exporter,
    )

    @Test
    fun exportFinishesRecordsTheFileAndReportsItInTheHistory() = runTest {
        val id = seededProject()

        val vm = viewModel(id, FakeVideoExportEngine())

        val phase = vm.state.value.phase
        assertTrue("expected Finished but was $phase", phase is ExportPhase.Finished)
        val video = (phase as ExportPhase.Finished).video
        assertEquals(id, video.projectId)
        assertEquals(1, vm.state.value.previous.size)
        assertEquals(1, repo.listExports(id).size)
    }

    @Test
    fun progressIsReportedFrameByFrameWhileRendering() = runTest {
        val id = seededProject()
        val exporter = StalledExporter()

        val vm = viewModel(id, exporter)

        val phase = vm.state.value.phase as ExportPhase.Rendering
        assertEquals(2, phase.framesDone)
        assertEquals(exporter.lastRequest!!.timeline.frameCount, phase.framesTotal)
        assertTrue(phase.fraction > 0f && phase.fraction < 1f)
    }

    @Test
    fun cancelStopsTheCollectorAndRecordsNothing() = runTest {
        val id = seededProject()
        val exporter = StalledExporter()
        val vm = viewModel(id, exporter)

        vm.cancel()

        assertEquals(ExportPhase.Cancelled, vm.state.value.phase)
        assertTrue("the engine's collector must be cancelled", exporter.cancelled)
        assertTrue(repo.listExports(id).isEmpty())
    }

    @Test
    fun startAfterCancelRunsAgainWithTheSameInputs() = runTest {
        val id = seededProject()
        val exporter = StalledExporter()
        val vm = viewModel(id, exporter)
        vm.cancel()

        vm.retry()

        assertTrue(vm.state.value.phase is ExportPhase.Rendering)
        assertEquals(2, exporter.starts)
    }

    @Test
    fun engineFailureIsReportedWithItsCategory() = runTest {
        val id = seededProject()
        val failing = object : VideoExportEngine {
            override fun export(request: ExportRequest): Flow<ExportProgress> = flow {
                emit(ExportProgress.Failed(TimeFlipError(ErrorCategory.STORAGE_FULL)))
            }
        }

        val vm = viewModel(id, failing)

        assertEquals(
            ExportPhase.Failed(UiFailure.Known(TimeFlipError(ErrorCategory.STORAGE_FULL))),
            vm.state.value.phase,
        )
        assertTrue(repo.listExports(id).isEmpty())
    }
}
