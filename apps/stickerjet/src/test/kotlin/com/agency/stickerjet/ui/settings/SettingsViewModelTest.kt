package com.agency.stickerjet.ui.settings

import com.agency.stickerjet.ui.testdoubles.FakeMaintenance
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsViewModelTest {

    @Test
    fun clearing_temporary_files_reports_done() {
        val maintenance = FakeMaintenance()
        val vm = SettingsViewModel(maintenance)
        runBlocking { vm.clearTemporary() }
        assertEquals(ActionState.DONE, vm.state.value.cleanup)
        assertEquals(1, maintenance.cleared)
        assertEquals(ActionState.IDLE, vm.state.value.delete)
    }

    @Test
    fun a_failed_cleanup_is_reported_without_touching_delete_state() {
        val vm = SettingsViewModel(FakeMaintenance(fail = true))
        runBlocking { vm.clearTemporary() }
        assertEquals(ActionState.FAILED, vm.state.value.cleanup)
        assertEquals(ActionState.IDLE, vm.state.value.delete)
    }

    @Test
    fun deleting_everything_reports_done_once() {
        val maintenance = FakeMaintenance()
        val vm = SettingsViewModel(maintenance)
        runBlocking { vm.deleteAll() }
        assertEquals(ActionState.DONE, vm.state.value.delete)
        assertEquals(1, maintenance.deletedAll)
    }
}
