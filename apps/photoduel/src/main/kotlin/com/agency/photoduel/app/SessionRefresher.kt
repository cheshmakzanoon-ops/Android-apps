package com.agency.photoduel.app

import com.agency.photoduel.domain.port.PhotoDuelService
import com.agency.photoduel.domain.port.SessionStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Checks the stored session whenever the app comes to the foreground.
 *
 * There is no separate refresh endpoint: `GET /v1/me` both proves the token is still accepted and
 * extends its 90 day lifetime (docs/architecture.md section 5). If the server answers 401, the
 * [GuardedPhotoDuelService] passed in as [service] clears the stored session, so the next screen the
 * user sees is sign-in. Network failures are ignored: being offline is not being signed out.
 *
 * Calls are skipped when no one is signed in, when the build has no server, while one is still in
 * flight, and within [minIntervalMillis] of the previous one.
 */
class SessionRefresher(
    private val scope: CoroutineScope,
    private val store: SessionStore,
    private val service: PhotoDuelService,
    private val enabled: Boolean,
    private val monotonicMillis: () -> Long,
    private val minIntervalMillis: Long = 30_000L,
) {
    private val inFlight = AtomicBoolean(false)
    private var lastStartedAt: Long? = null

    /** Call on the main thread when the process lifecycle reaches RESUMED. */
    fun onProcessResumed() {
        if (!enabled) return
        val now = monotonicMillis()
        val last = lastStartedAt
        if (last != null && now - last < minIntervalMillis) return
        if (!inFlight.compareAndSet(false, true)) return
        lastStartedAt = now
        scope.launch {
            try {
                if (store.read() != null) service.currentUser()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed check never crashes the app and never signs the user out by itself.
            } finally {
                inFlight.set(false)
            }
        }
    }
}
