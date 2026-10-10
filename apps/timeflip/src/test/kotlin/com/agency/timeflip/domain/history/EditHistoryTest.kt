package com.agency.timeflip.domain.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {

    @Test
    fun undoAndRedoWalkThroughCommits() {
        val history = EditHistory("a")
        assertTrue(history.commit("b"))
        assertTrue(history.commit("c"))
        assertEquals("b", history.undo())
        assertEquals("a", history.undo())
        assertNull(history.undo())
        assertEquals("b", history.redo())
        assertEquals("c", history.redo())
        assertNull(history.redo())
    }

    @Test
    fun committingEqualValueIsANoOp() {
        val history = EditHistory(1)
        assertFalse(history.commit(1))
        assertFalse(history.canUndo)
    }

    @Test
    fun newCommitClearsRedoBranch() {
        val history = EditHistory(0)
        history.commit(1)
        history.undo()
        assertTrue(history.canRedo)
        history.commit(2)
        assertFalse(history.canRedo)
        assertEquals(2, history.current)
    }

    @Test
    fun capacityDropsOldestEntries() {
        val history = EditHistory(0, capacity = 3)
        for (value in 1..6) history.commit(value)
        // Only the three most recent previous states remain undoable: 5, 4 and 3.
        assertEquals(5, history.undo())
        assertEquals(4, history.undo())
        assertEquals(3, history.undo())
        assertNull(history.undo())
    }
}
