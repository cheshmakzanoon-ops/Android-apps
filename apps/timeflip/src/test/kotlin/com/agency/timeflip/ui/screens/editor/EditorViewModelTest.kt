package com.agency.timeflip.ui.screens.editor

import androidx.lifecycle.SavedStateHandle
import com.agency.timeflip.domain.model.Easing
import com.agency.timeflip.domain.model.OutputPreset
import com.agency.timeflip.domain.model.ProjectId
import com.agency.timeflip.domain.model.TransitionKind
import com.agency.timeflip.domain.testing.FakePhotoStore
import com.agency.timeflip.domain.testing.InMemoryProjectRepository
import com.agency.timeflip.ui.MainDispatcherRule
import com.agency.timeflip.ui.common.ROUTE_ARG_PROJECT_ID
import com.agency.timeflip.ui.common.progressAt
import com.agency.timeflip.ui.importPair
import com.agency.timeflip.ui.newTestProject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class EditorViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repo = InMemoryProjectRepository()
    private val photos = FakePhotoStore()

    private fun handleFor(projectId: String, saved: Map<String, Any?> = emptyMap()) =
        SavedStateHandle(mapOf<String, Any?>(ROUTE_ARG_PROJECT_ID to projectId) + saved)

    @Test
    fun transitionChangesAreWrittenToTheRepositoryAndTheSavedState() = runTest {
        repo.saveProject(newTestProject("project-1", importPair(photos)))
        val handle = handleFor("project-1")
        val vm = EditorViewModel(handle, repo, photos, now = { 4_000L })

        vm.selectKind(TransitionKind.VERTICAL_REVEAL)
        vm.selectEasing(Easing.LINEAR)
        vm.setReversed(true)
        vm.previewDurationMs(2_000)
        vm.commitDuration()

        val saved = repo.getProject(ProjectId("project-1"))!!.transition
        assertEquals(TransitionKind.VERTICAL_REVEAL, saved.kind)
        assertEquals(2_000, saved.parameters.durationMs)
        assertEquals(Easing.LINEAR, saved.parameters.easing)
        assertTrue(saved.parameters.reversed)
        assertEquals("VERTICAL_REVEAL", handle.get<String>("editor.transition.kind"))
    }

    @Test
    fun transitionSavedBeforeProcessDeathIsRestoredOnReopen() = runTest {
        repo.saveProject(newTestProject("project-1", importPair(photos)))
        val firstHandle = handleFor("project-1")
        EditorViewModel(firstHandle, repo, photos).selectKind(TransitionKind.DIRECTIONAL_WIPE)
        // Simulate a repository write that did not complete before the process died.
        repo.saveProject(newTestProject("project-1", importPair(photos)))

        val reopened = EditorViewModel(firstHandle, repo, photos)

        assertEquals(TransitionKind.DIRECTIONAL_WIPE, reopened.state.value.project?.transition?.kind)
    }

    @Test
    fun invalidSavedTransitionIsIgnoredInsteadOfCrashing() = runTest {
        repo.saveProject(newTestProject("project-1", importPair(photos)))
        val handle = handleFor(
            "project-1",
            mapOf(
                "editor.transition.kind" to "NOT_A_KIND",
                "editor.transition.durationMs" to 1200,
                "editor.transition.easing" to "LINEAR",
                "editor.transition.reversed" to false,
                "editor.transition.wipeAngle" to 0.0,
            ),
        )

        val vm = EditorViewModel(handle, repo, photos)

        assertFalse(vm.state.value.loading)
        assertEquals(null, vm.state.value.loadFailure)
    }

    @Test
    fun playbackStopsAtTheEndOfTheTimelineAndScrubbingPauses() = runTest {
        repo.saveProject(newTestProject("project-1", importPair(photos)))
        val vm = EditorViewModel(handleFor("project-1"), repo, photos)
        val total = vm.state.value.totalMs

        vm.togglePlayback()
        assertTrue(vm.state.value.playing)
        vm.onFrameTick(10_000)
        assertEquals("a single tick is capped at MAX_TICK_MS", EditorViewModel.MAX_TICK_MS.toInt(), vm.state.value.positionMs)
        repeat(total / EditorViewModel.MAX_TICK_MS.toInt() + 1) { vm.onFrameTick(EditorViewModel.MAX_TICK_MS) }
        assertFalse(vm.state.value.playing)
        assertEquals(total, vm.state.value.positionMs)

        vm.replay()
        assertEquals(0, vm.state.value.positionMs)
        assertTrue(vm.state.value.playing)

        vm.scrubTo(1_000)
        assertFalse(vm.state.value.playing)
        assertEquals(1_000, vm.state.value.positionMs)
    }

    @Test
    fun presetChangeIsSavedWithTheProjectProfile() = runTest {
        repo.saveProject(newTestProject("project-1", importPair(photos)))
        val vm = EditorViewModel(handleFor("project-1"), repo, photos)

        vm.selectPreset(OutputPreset.SQUARE_1080)

        assertEquals(OutputPreset.SQUARE_1080, repo.getProject(ProjectId("project-1"))?.exportProfile?.preset)
    }

    @Test
    fun previewProgressUsesTheSameTimelineAsExport() = runTest {
        repo.saveProject(newTestProject("project-1", importPair(photos)))
        val vm = EditorViewModel(handleFor("project-1"), repo, photos)
        val timeline = vm.state.value.timeline!!

        // The hold on BEFORE shows no transition progress; the end of the timeline shows the full result.
        assertEquals(0.0, timeline.progressAt(0), 1e-9)
        assertEquals(0.0, timeline.progressAt(timeline.holdBeforeMs), 1e-9)
        assertEquals(1.0, timeline.progressAt(timeline.totalDurationMs), 1e-9)
    }
}
