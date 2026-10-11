package com.agency.photoduel.ui.state

import com.agency.photoduel.domain.model.AppResult
import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.PhotoDuelError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Presentation state for PhotoDuel screens. This package is pure Kotlin: no androidx, no android.*,
// no Compose. It depends only on kotlinx.coroutines and the frozen domain contracts, so every class can
// be unit tested on a plain JVM. Screens (ui/screens) collect the StateFlows and render them; text is
// chosen in the UI layer from string resources, so nothing in here holds user-facing copy.

/** Content that comes from the service. */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>

    /** [stale] is true when a later refresh failed and the content on screen may be out of date. */
    data class Loaded<T>(val value: T, val stale: Boolean = false) : Loadable<T>

    /** [reconnecting] is true when the failure was a lost connection and retries are running. */
    data class Failed(val error: PhotoDuelError, val reconnecting: Boolean) : Loadable<Nothing>
}

val <T> Loadable<T>.valueOrNull: T? get() = (this as? Loadable.Loaded<T>)?.value

/** True for failures caused by the connection rather than by the request. */
fun PhotoDuelError.isConnectivity(): Boolean = code == ErrorCode.NETWORK || code == ErrorCode.TIMEOUT

/**
 * Tracks whether the last service call reached the server. Any answer from the server (even an
 * error) means we are online; only a network failure or timeout flips it to offline. The root screen
 * shows the "reconnecting" banner from [offline].
 */
class NetworkStatus {
    private val _offline = MutableStateFlow(false)
    val offline: StateFlow<Boolean> = _offline.asStateFlow()

    fun <T> track(result: AppResult<T>): AppResult<T> {
        _offline.value = result is AppResult.Failure && result.error.isConnectivity()
        return result
    }
}

/**
 * Loads one value and keeps it fresh. A lost connection is retried with a growing delay for as long as
 * [scope] lives, so the screen can say "reconnecting" and mean it. Other failures are not retried
 * automatically; the screen offers a Retry button when [PhotoDuelError.retryable] is true.
 */
class Loader<T>(
    private val scope: CoroutineScope,
    private val network: NetworkStatus,
    private val retryDelaysMillis: List<Long> = DEFAULT_RETRY_DELAYS_MILLIS,
    private val onLoaded: (T) -> Unit = {},
    private val fetch: suspend () -> AppResult<T>,
) {
    private val _state = MutableStateFlow<Loadable<T>>(Loadable.Loading)
    val state: StateFlow<Loadable<T>> = _state.asStateFlow()
    private var job: Job? = null

    val value: T? get() = _state.value.valueOrNull

    /** Starts a load. Content already on screen stays visible while the new copy loads. */
    fun load() {
        job?.cancel()
        if (_state.value !is Loadable.Loaded) _state.value = Loadable.Loading
        job = scope.launch {
            var attempt = 0
            while (true) {
                when (val result = network.track(fetch())) {
                    is AppResult.Success -> {
                        _state.value = Loadable.Loaded(result.value)
                        onLoaded(result.value)
                        return@launch
                    }
                    is AppResult.Failure -> {
                        val connectivity = result.error.isConnectivity()
                        val current = _state.value
                        _state.value = if (current is Loadable.Loaded) {
                            current.copy(stale = true)
                        } else {
                            Loadable.Failed(result.error, reconnecting = connectivity)
                        }
                        if (!connectivity || retryDelaysMillis.isEmpty()) return@launch
                        delay(retryDelaysMillis[minOf(attempt, retryDelaysMillis.lastIndex)])
                        attempt++
                    }
                }
            }
        }
    }

    /** Same as [load]; named for the pull-to-refresh and "deadline reached" callers. */
    fun refresh() = load()

    /** Replaces the loaded value after a local action, without calling the service. */
    fun update(transform: (T) -> T) {
        _state.update { current -> if (current is Loadable.Loaded) Loadable.Loaded(transform(current.value)) else current }
    }

    fun cancel() {
        job?.cancel()
    }

    companion object {
        val DEFAULT_RETRY_DELAYS_MILLIS: List<Long> = listOf(2_000, 4_000, 8_000, 15_000, 30_000)
    }
}

/** Marker for what a one-shot UI action did. */
sealed interface ActionResult {
    data object Idle : ActionResult
    data object Working : ActionResult
    data class Failed(val error: PhotoDuelError) : ActionResult
}
