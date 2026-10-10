package com.agency.stickerjet.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditHistoryTest {

    @Test
    fun newHistoryHoldsTheInitialStateAndNothingToUndoOrRedo() {
        val history = EditHistory("a")
        assertEquals("a", history.current)
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertNull(history.undo())
        assertNull(history.redo())
        assertEquals("a", history.current)
    }

    @Test
    fun undoAndRedoWalkTheWholeSequence() {
        val history = EditHistory("a")
        history.commit("b")
        history.commit("c")
        assertEquals("c", history.current)

        assertEquals("b", history.undo())
        assertEquals("a", history.undo())
        assertNull(history.undo())
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)

        assertEquals("b", history.redo())
        assertEquals("c", history.redo())
        assertNull(history.redo())
        assertEquals("c", history.current)
        assertFalse(history.canRedo)
    }

    @Test
    fun commitAfterUndoClearsTheRedoChain() {
        val history = EditHistory(0)
        history.commit(1)
        history.commit(2)
        assertEquals(1, history.undo())

        history.commit(5)

        assertFalse(history.canRedo)
        assertNull(history.redo())
        assertEquals(5, history.current)
        assertEquals(1, history.undo())
    }

    @Test
    fun interleavedUndoRedoAndCommitsStayConsistent() {
        val history = EditHistory(0)
        history.commit(1)
        history.commit(2)
        history.commit(3)
        assertEquals(2, history.undo())
        assertEquals(3, history.redo())
        assertEquals(2, history.undo())
        assertEquals(1, history.undo())
        history.commit(9)
        assertEquals(1, history.undo())
        assertEquals(0, history.undo())
        assertNull(history.undo())
        assertEquals(0, history.current)
    }

    @Test
    fun undoDepthIsBoundedAndTheOldestStepsAreDropped() {
        val history = EditHistory(0, maxDepth = 3)
        for (value in 1..5) history.commit(value)

        assertEquals(listOf<Int?>(4, 3, 2, null), List(4) { history.undo() })
        assertFalse(history.canUndo)
    }

    @Test
    fun depthOfOneKeepsOnlyTheLastStep() {
        val history = EditHistory(0, maxDepth = 1)
        history.commit(1)
        history.commit(2)
        assertEquals(1, history.undo())
        assertNull(history.undo())
    }

    @Test
    fun everyCommitIsAStepEvenWhenTheValueDoesNotChange() {
        val history = EditHistory(0)
        history.commit(0)
        assertEquals(0, history.undo())
        assertNull(history.undo())
    }

    @Test
    fun redoRestoresEveryStepUndoneWithinTheDepth() {
        val history = EditHistory(0, maxDepth = 2)
        for (value in 1..4) history.commit(value)
        repeat(2) { history.undo() }
        assertEquals(listOf<Int?>(3, 4, null), List(3) { history.redo() })
    }

    @Test
    fun maxDepthBelowOneIsRejected() {
        assertFailsWith<IllegalArgumentException> { EditHistory(0, maxDepth = 0) }
    }

    @Test
    fun worksWithProjectSnapshots() {
        val source = SourceVideo("clip.mp4", 10_000L, 640, 480, 0, 1L)
        val first = StickerProjects.create("p", source, nowMs = 1L)
        val history = EditHistory(first)
        history.commit(first.withTitle("Renamed", nowMs = 2L))
        assertEquals("Renamed", history.current.title)
        assertEquals("clip", history.undo()?.title)
    }
}
