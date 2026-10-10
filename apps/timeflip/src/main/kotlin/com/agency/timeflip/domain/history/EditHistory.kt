package com.agency.timeflip.domain.history

/**
 * Bounded undo/redo over immutable snapshots. Committing an equal value is a no-op, and a new
 * commit clears the redo branch. The oldest entries are dropped beyond [capacity].
 */
class EditHistory<T>(initial: T, private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity >= 1) { "capacity must be at least 1" }
    }

    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()

    var current: T = initial
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty()

    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Records [next] as the current state. Returns false when it equals the current state. */
    fun commit(next: T): Boolean {
        if (next == current) return false
        undoStack.addLast(current)
        if (undoStack.size > capacity) undoStack.removeFirst()
        current = next
        redoStack.clear()
        return true
    }

    fun undo(): T? {
        if (undoStack.isEmpty()) return null
        redoStack.addLast(current)
        current = undoStack.removeLast()
        return current
    }

    fun redo(): T? {
        if (redoStack.isEmpty()) return null
        undoStack.addLast(current)
        current = redoStack.removeLast()
        return current
    }

    companion object {
        const val DEFAULT_CAPACITY = 100
    }
}
