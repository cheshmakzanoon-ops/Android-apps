package com.agency.stickerjet.ui.library

import com.agency.stickerjet.domain.ExportRecord
import com.agency.stickerjet.domain.ProjectRepository
import com.agency.stickerjet.domain.StickerProject
import com.agency.stickerjet.fakes.InMemoryProjectRepository
import com.agency.stickerjet.ui.testdoubles.TestFiles
import com.agency.stickerjet.ui.testdoubles.testProject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LibraryViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun drafts_are_listed_newest_first_and_missing_originals_are_flagged() {
        val files = TestFiles(tmp.root)
        writeSource(files, "old")
        val repository = InMemoryProjectRepository(
            listOf(testProject("old", updatedAtMs = 1_000L), testProject("new", updatedAtMs = 2_000L)),
        )
        val vm = LibraryViewModel(repository, files)
        runBlocking { vm.reload() }

        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals(listOf("new", "old"), state.items.map { it.project.id })
        assertTrue(state.items.first { it.project.id == "new" }.sourceMissing)
        assertFalse(state.items.first { it.project.id == "old" }.sourceMissing)
    }

    @Test
    fun recent_exports_are_newest_first_and_report_missing_files() {
        val files = TestFiles(tmp.root)
        val withExports = testProject("a", title = "Alpha").copy(
            exports = listOf(record("a-old.webp", createdAtMs = 10L), record("a-new.webp", createdAtMs = 30L)),
        )
        val repository = InMemoryProjectRepository(listOf(withExports))
        files.exportsDir("a").mkdirs()
        File(files.exportsDir("a"), "a-new.webp").writeBytes(ByteArray(4))

        val vm = LibraryViewModel(repository, files)
        runBlocking { vm.reload() }

        val exports = vm.state.value.recentExports
        assertEquals(listOf("a-new.webp", "a-old.webp"), exports.map { it.record.fileName })
        assertTrue(exports[0].fileExists)
        assertFalse(exports[1].fileExists)
    }

    @Test
    fun deleting_a_draft_removes_it_and_reports_the_deletion() {
        val files = TestFiles(tmp.root)
        val repository = InMemoryProjectRepository(listOf(testProject("gone")))
        val vm = LibraryViewModel(repository, files)
        runBlocking { vm.reload() }
        vm.requestDelete(vm.state.value.items.single())
        assertEquals("gone", vm.state.value.pendingDelete!!.project.id)

        runBlocking { vm.delete("gone") }

        assertNull(runBlocking { repository.load("gone") })
        assertEquals(LibraryNotice.DELETED, vm.state.value.notice)
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test
    fun a_failing_repository_sets_load_failed_instead_of_crashing() {
        val failing = object : ProjectRepository {
            override suspend fun list(): List<StickerProject> = throw IllegalStateException("corrupt index")
            override suspend fun load(id: String): StickerProject? = null
            override suspend fun save(project: StickerProject) = Unit
            override suspend fun delete(id: String) = Unit
        }
        val vm = LibraryViewModel(failing, TestFiles(tmp.root))
        runBlocking { vm.reload() }
        assertTrue(vm.state.value.loadFailed)
        assertFalse(vm.state.value.loading)
    }

    private fun writeSource(files: TestFiles, id: String) {
        files.sourceFile(id).apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(8))
        }
    }

    private fun record(name: String, createdAtMs: Long) = ExportRecord(
        fileName = name,
        createdAtMs = createdAtMs,
        sizeBytes = 512L,
        frameCount = 24,
        durationMs = 2_000L,
    )
}
