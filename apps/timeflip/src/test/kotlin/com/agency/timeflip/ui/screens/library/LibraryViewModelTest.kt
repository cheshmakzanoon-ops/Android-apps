package com.agency.timeflip.ui.screens.library

import com.agency.timeflip.domain.model.ProjectId
import com.agency.timeflip.domain.testing.InMemoryProjectRepository
import com.agency.timeflip.ui.MainDispatcherRule
import com.agency.timeflip.domain.testing.FakePhotoStore
import com.agency.timeflip.ui.importPair
import com.agency.timeflip.ui.newTestProject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibraryViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repo = InMemoryProjectRepository()
    private val photos = FakePhotoStore()

    @Test
    fun emptyLibraryShowsTheEmptyStateNotAList() {
        val vm = LibraryViewModel(repo, now = { 5_000L })

        assertEquals(LibraryContent.Empty, vm.state.value.content)
    }

    @Test
    fun savedProjectsAppearAsRowsWithoutRestarting() = runTest {
        val vm = LibraryViewModel(repo, now = { 5_000L })

        repo.saveProject(newTestProject("project-1", importPair(photos)))

        val content = vm.state.value.content
        assertTrue(content is LibraryContent.Ready)
        assertEquals(listOf(ProjectId("project-1")), (content as LibraryContent.Ready).rows.map { it.id })
    }

    @Test
    fun renameUpdatesTheProjectAndReportsTheResult() = runTest {
        val vm = LibraryViewModel(repo, now = { 5_000L })
        repo.saveProject(newTestProject("project-1", importPair(photos)))
        val row = (vm.state.value.content as LibraryContent.Ready).rows.single()

        vm.startRename(row)
        vm.changeRenameDraft("Kitchen renovation")
        vm.confirmRename()

        assertEquals("Kitchen renovation", repo.getProject(ProjectId("project-1"))?.name)
        assertEquals(LibraryMessage.RENAMED, vm.state.value.message)
        assertEquals(LibraryDialog.None, vm.state.value.dialog)
    }

    @Test
    fun blankRenameIsNotSaved() = runTest {
        val vm = LibraryViewModel(repo, now = { 5_000L })
        repo.saveProject(newTestProject("project-1", importPair(photos)))
        val row = (vm.state.value.content as LibraryContent.Ready).rows.single()

        vm.startRename(row)
        vm.changeRenameDraft("   ")
        vm.confirmRename()

        assertTrue(vm.state.value.dialog is LibraryDialog.Rename)
        assertEquals("Test project", repo.getProject(ProjectId("project-1"))?.name)
    }

    @Test
    fun deleteRemovesOnlyAfterConfirmation() = runTest {
        val vm = LibraryViewModel(repo, now = { 5_000L })
        repo.saveProject(newTestProject("project-1", importPair(photos)))
        val row = (vm.state.value.content as LibraryContent.Ready).rows.single()

        vm.startDelete(row)
        assertTrue(repo.getProject(ProjectId("project-1")) != null)

        vm.confirmDelete()

        assertEquals(null, repo.getProject(ProjectId("project-1")))
        assertEquals(LibraryContent.Empty, vm.state.value.content)
        assertEquals(LibraryMessage.DELETED, vm.state.value.message)
    }

    @Test
    fun duplicateCreatesACopyUnderTheGivenId() = runTest {
        val vm = LibraryViewModel(repo, now = { 5_000L }, newId = { ProjectId("copy-1") })
        repo.saveProject(newTestProject("project-1", importPair(photos)))

        vm.duplicate(ProjectId("project-1"))

        assertTrue(repo.getProject(ProjectId("copy-1")) != null)
        assertEquals(LibraryMessage.DUPLICATED, vm.state.value.message)
    }
}
