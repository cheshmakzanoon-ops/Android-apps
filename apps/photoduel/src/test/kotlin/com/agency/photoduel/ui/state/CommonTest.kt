package com.agency.photoduel.ui.state

import com.agency.photoduel.domain.model.AppResult
import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.PhotoDuelError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommonTest {
    private val harness = TestHarness()
    private val network = NetworkStatus()

    @After
    fun tearDown() = harness.close()

    /** Answers from a list, one per call; the last answer repeats. */
    private class Script<T>(vararg answers: AppResult<T>) {
        private val queue = answers.toMutableList()
        var calls = 0
        fun next(): AppResult<T> {
            calls++
            return if (queue.size > 1) queue.removeAt(0) else queue.first()
        }
    }

    @Test
    fun loaderStartsLoadingThenShowsTheValue() {
        val script = Script(AppResult.Success("hello"))
        val loader = Loader(harness.scope, network) { script.next() }
        assertEquals(Loadable.Loading, loader.state.value)
        loader.load()
        harness.settle()
        assertEquals(Loadable.Loaded("hello"), loader.state.value)
        assertEquals("hello", loader.value)
        assertEquals(1, script.calls)
    }

    @Test
    fun aRequestErrorIsShownAndNotRetried() {
        val refused = PhotoDuelError(ErrorCode.FORBIDDEN, "no", retryable = false)
        val script = Script<String>(AppResult.Failure(refused))
        val loader = Loader(harness.scope, network) { script.next() }
        loader.load()
        harness.settle()
        assertEquals(Loadable.Failed(refused, reconnecting = false), loader.state.value)
        harness.advance(60_000)
        assertEquals(1, script.calls)
        assertFalse(network.offline.value)
    }

    @Test
    fun aLostConnectionIsRetriedWithGrowingDelaysUntilItWorks() {
        val script = Script(AppResult.Failure(offline()), AppResult.Failure(offline()), AppResult.Success(7))
        val loader = Loader(harness.scope, network, retryDelaysMillis = listOf(1_000, 5_000)) { script.next() }
        loader.load()
        harness.scheduler.runCurrent()
        assertEquals(Loadable.Failed(offline(), reconnecting = true), loader.state.value)
        assertTrue(network.offline.value)

        harness.advance(999)
        assertEquals(1, script.calls)
        harness.advance(1)
        assertEquals(2, script.calls)
        // Second delay is the larger one.
        harness.advance(4_999)
        assertEquals(2, script.calls)
        harness.advance(1)
        assertEquals(3, script.calls)
        assertEquals(Loadable.Loaded(7), loader.state.value)
        assertFalse(network.offline.value)
    }

    @Test
    fun aFailedRefreshKeepsTheContentAndMarksItStale() {
        val script = Script(AppResult.Success(1), AppResult.Failure(PhotoDuelError(ErrorCode.SERVER, "x", retryable = true)))
        val loader = Loader(harness.scope, network) { script.next() }
        loader.load()
        harness.settle()
        loader.refresh()
        harness.settle()
        assertEquals(Loadable.Loaded(1, stale = true), loader.state.value)
    }

    @Test
    fun contentStaysVisibleWhileRefreshing() {
        val script = Script(AppResult.Success(1), AppResult.Success(2))
        val loader = Loader(harness.scope, network) { script.next() }
        loader.load()
        harness.settle()
        loader.refresh()
        assertEquals(Loadable.Loaded(1), loader.state.value)
        harness.settle()
        assertEquals(Loadable.Loaded(2), loader.state.value)
    }

    @Test
    fun updateChangesOnlyLoadedContent() {
        val loader = Loader(harness.scope, network) { AppResult.Success(10) }
        loader.update { it + 1 }
        assertEquals(Loadable.Loading, loader.state.value)
        loader.load()
        harness.settle()
        loader.update { it + 1 }
        assertEquals(Loadable.Loaded(11), loader.state.value)
    }

    @Test
    fun onLoadedRunsAfterEachSuccess() {
        val seen = mutableListOf<Int>()
        var next = 0
        val loader = Loader(harness.scope, network, onLoaded = { seen += it }) { AppResult.Success(++next) }
        loader.load()
        harness.settle()
        loader.refresh()
        harness.settle()
        assertEquals(listOf(1, 2), seen)
    }

    @Test
    fun cancelStopsReconnecting() {
        val script = Script<Int>(AppResult.Failure(offline()))
        val loader = Loader(harness.scope, network, retryDelaysMillis = listOf(1_000)) { script.next() }
        loader.load()
        harness.scheduler.runCurrent()
        loader.cancel()
        harness.advance(10_000)
        assertEquals(1, script.calls)
    }

    @Test
    fun networkStatusFollowsTheLastAnswer() {
        network.track(AppResult.Failure(PhotoDuelError(ErrorCode.TIMEOUT, "slow", retryable = true)))
        assertTrue(network.offline.value)
        // Any answer from the server, even an error, means the connection works.
        network.track(AppResult.Failure(PhotoDuelError(ErrorCode.CONFLICT, "busy", retryable = false)))
        assertFalse(network.offline.value)
        network.track(AppResult.Failure(offline()))
        network.track(AppResult.Success(Unit))
        assertFalse(network.offline.value)
    }

    @Test
    fun networkStatusReportsARejectedSession() {
        var seen = 0
        harness.scope.launch { network.sessionRejected.first(); seen++ }
        harness.settle()
        network.track(AppResult.Failure(PhotoDuelError(ErrorCode.NOT_FOUND, "x", retryable = false)))
        harness.settle()
        assertEquals(0, seen)
        network.track(AppResult.Failure(PhotoDuelError(ErrorCode.UNAUTHENTICATED, "x", retryable = false)))
        harness.settle()
        assertEquals(1, seen)
    }

    @Test
    fun onlyNetworkAndTimeoutCountAsConnectivity() {
        for (code in ErrorCode.entries) {
            val expected = code == ErrorCode.NETWORK || code == ErrorCode.TIMEOUT
            assertEquals(code.name, expected, PhotoDuelError(code, "", retryable = true).isConnectivity())
        }
    }

    @Test
    fun valueOrNullReadsOnlyLoadedContent() {
        assertNull(Loadable.Loading.valueOrNull)
        assertNull(Loadable.Failed(offline(), reconnecting = true).valueOrNull)
        assertEquals("x", Loadable.Loaded("x").valueOrNull)
    }
}
