package com.agency.stickerjet.domain

/**
 * Linear undo/redo over immutable snapshots of [T].
 *
 * - [commit] makes [next] the current state and records the previous one for undo. Every commit is a
 *   step, even when [next] equals the current state. A commit clears the redo chain.
 * - Undo depth is bounded by [maxDepth]. When the bound is exceeded, the oldest step is dropped.
 * - The redo chain is at most [maxDepth] long too, because it is made only by undoing recorded steps.
 *
 * Not thread-safe. An editor owns one instance and calls it from one thread.
 */
class EditHistory<T>(initial: T, private val maxDepth: Int = 50) {

    init {
        require(maxDepth >= 1) { "maxDepth must be at least 1, was $maxDepth" }
    }

    private var present: T = initial
    private val undoSteps = ArrayDeque<T>()
    private val redoSteps = ArrayDeque<T>()

    /** The state the editor shows right now. */
    val current: T get() = present

    val canUndo: Boolean get() = undoSteps.isNotEmpty()

    val canRedo: Boolean get() = redoSteps.isNotEmpty()

    /** Records the present state for undo, makes [next] current, and clears the redo chain. */
    fun commit(next: T) {
        undoSteps.addLast(present)
        if (undoSteps.size > maxDepth) undoSteps.removeFirst()
        present = next
        redoSteps.clear()
    }

    /** Steps back. Returns the new current state, or null when there is nothing to undo. */
    fun undo(): T? {
        if (undoSteps.isEmpty()) return null
        redoSteps.addLast(present)
        present = undoSteps.removeLast()
        return present
    }

    /** Steps forward again. Returns the new current state, or null when there is nothing to redo. */
    fun redo(): T? {
        if (redoSteps.isEmpty()) return null
        undoSteps.addLast(present)
        present = redoSteps.removeLast()
        return present
    }
}
