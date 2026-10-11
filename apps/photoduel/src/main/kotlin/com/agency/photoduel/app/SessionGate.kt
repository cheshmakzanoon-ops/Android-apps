package com.agency.photoduel.app

import com.agency.photoduel.domain.port.SessionStore
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Wraps the real [SessionStore] so that a 401 can sign the user out safely.
 *
 * Every write bumps a generation counter. A request notes the generation before it is sent; if it comes
 * back 401 the session is cleared only when no sign-in or sign-out happened in the meantime. Without
 * that check, a slow request from the previous session could wipe a session that was just created.
 *
 * Pure Kotlin (coroutines only) so the rule is covered by plain JVM tests.
 */
class SessionGate(private val delegate: SessionStore) : SessionStore {
    private val generation = AtomicLong(0)
    private val lock = Mutex()
    private val expiredEvents = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Emits once each time a stored session was cleared because the server rejected it (401). */
    val expired: SharedFlow<Unit> = expiredEvents.asSharedFlow()

    override suspend fun read(): String? = delegate.read()

    override suspend fun write(token: String?) {
        lock.withLock {
            generation.incrementAndGet()
            delegate.write(token)
        }
    }

    /** Opaque marker of the current session state. Read it before sending a request. */
    fun currentGeneration(): Long = generation.get()

    /**
     * Clears the stored session if it is still the one that [expectedGeneration] was read for.
     * Returns true only when a stored token was actually removed.
     */
    suspend fun expireIfUnchanged(expectedGeneration: Long): Boolean {
        val cleared = lock.withLock {
            if (generation.get() != expectedGeneration) {
                false
            } else if (delegate.read() == null) {
                false
            } else {
                generation.incrementAndGet()
                delegate.write(null)
                true
            }
        }
        if (cleared) expiredEvents.tryEmit(Unit)
        return cleared
    }
}
