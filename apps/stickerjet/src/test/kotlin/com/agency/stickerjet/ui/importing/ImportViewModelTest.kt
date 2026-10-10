package com.agency.stickerjet.ui.importing

import com.agency.stickerjet.domain.ImportRejection
import com.agency.stickerjet.ui.ports.ClipImportPort
import com.agency.stickerjet.ui.ports.ImportFailureKind
import com.agency.stickerjet.ui.ports.ImportResult
import com.agency.stickerjet.ui.testdoubles.QueuedImporter
import com.agency.stickerjet.ui.testdoubles.testProject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportViewModelTest {

    @Test
    fun a_rejected_clip_shows_its_reason_and_keeps_the_source_for_later() {
        val importer = QueuedImporter(ImportResult.Rejected(ImportRejection.TOO_LONG))
        val vm = ImportViewModel(importer) { "p1" }
        runBlocking { vm.importClip("content://clip") }
        assertEquals(ImportPhase.Rejected(ImportRejection.TOO_LONG), vm.state.value.phase)
        assertEquals("content://clip", vm.state.value.lastSource)
    }

    @Test
    fun a_failed_import_can_be_retried_with_the_same_source_and_then_opens_the_editor() {
        val project = testProject("p2")
        val importer = QueuedImporter(
            ImportResult.Failed(ImportFailureKind.ACCESS_REVOKED),
            ImportResult.Imported(project),
        )
        val vm = ImportViewModel(importer) { "p2" }
        runBlocking { vm.importClip("content://clip") }
        assertEquals(ImportPhase.Failed(ImportFailureKind.ACCESS_REVOKED), vm.state.value.phase)

        runBlocking { vm.importClip(vm.state.value.lastSource!!) }

        assertEquals(ImportPhase.Imported("p2"), vm.state.value.phase)
        assertEquals(listOf("content://clip", "content://clip"), importer.sources)
    }

    @Test
    fun an_exception_from_the_port_becomes_an_io_failure_not_a_crash() {
        val throwing = object : ClipImportPort {
            override suspend fun import(contentUri: String, projectId: String): ImportResult =
                throw IllegalStateException("copy broke")
        }
        val vm = ImportViewModel(throwing) { "p3" }
        runBlocking { vm.importClip("content://clip") }
        assertEquals(ImportPhase.Failed(ImportFailureKind.IO_ERROR), vm.state.value.phase)
    }

    @Test
    fun a_dismissed_picker_changes_nothing() {
        val vm = ImportViewModel(QueuedImporter()) { "p4" }
        vm.onPicked(null)
        assertEquals(ImportPhase.Idle, vm.state.value.phase)
        assertNull(vm.state.value.lastSource)
    }

    @Test
    fun a_missing_picker_moves_to_the_unsupported_state() {
        val vm = ImportViewModel(QueuedImporter()) { "p5" }
        vm.onUnsupported()
        assertEquals(ImportPhase.Unsupported, vm.state.value.phase)
    }
}
