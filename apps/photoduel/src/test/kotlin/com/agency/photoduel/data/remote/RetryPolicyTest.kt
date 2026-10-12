package com.agency.photoduel.data.remote

import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.PhotoDuelError
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Automatic retry happens only for GET, at most twice, with growing waits. Nothing else is ever resent. */
internal class RetryPolicyTest : RemoteTestBase() {

    private fun enqueueDisconnect() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
    }

    @Test
    fun getIsRetriedAfterNetworkFailuresWithBackoff() = blocking {
        enqueueDisconnect()
        enqueueDisconnect()
        enqueueJson(200, Fixtures.USER)

        assertEquals("u1", service.currentUser().success().id)

        assertEquals(3, server.requestCount)
        assertEquals(PhotoDuelApiClient.DEFAULT_GET_RETRY_DELAYS_MILLIS, sleeps.toList())
        assertTrue(sleeps[1] > sleeps[0])
    }

    @Test
    fun getGivesUpAfterTwoRetries() = blocking {
        repeat(3) { enqueueDisconnect() }
        enqueueJson(200, Fixtures.USER)

        val error = service.currentUser().failure()

        assertEquals(ErrorCode.NETWORK, error.code)
        assertTrue(error.retryable)
        assertEquals(3, server.requestCount)
        assertEquals(2, sleeps.size)
    }

    @Test
    fun getIsRetriedAfterServerErrors() = blocking {
        enqueueError(503, "SERVER", "busy")
        enqueueError(500, "SERVER", "boom")
        enqueueJson(200, """{"rooms":[]}""")

        assertEquals(emptyList<Any>(), service.myRooms().success())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun getReturnsTheLastServerErrorWhenRetriesRunOut() = blocking {
        repeat(3) { enqueueError(500, "SERVER", "boom") }

        val error = service.myRooms().failure()

        assertEquals(ErrorCode.SERVER, error.code)
        assertTrue(error.retryable)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun getIsNotRetriedAfterAClientError() = blocking {
        enqueueError(404, "NOT_FOUND")
        enqueueJson(200, Fixtures.ROOM_DETAIL)

        assertEquals(ErrorCode.NOT_FOUND, service.room("r1").failure().code)
        assertEquals(1, server.requestCount)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun getIsNotRetriedAfterRateLimiting() = blocking {
        enqueueError(429, "RATE_LIMITED", "slow down", retryAfterSeconds = 30)
        enqueueJson(200, """{"rooms":[]}""")

        assertEquals(30, service.myRooms().failure().retryAfterSeconds)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun postIsSentOnceAfterANetworkFailure() = blocking {
        enqueueDisconnect()
        enqueueJson(201, """{"challengeId":"c1","submissionId":"s2","castAtEpochMillis":1}""")

        val error = service.castBallot("c1", "s2").failure()

        assertEquals(ErrorCode.NETWORK, error.code)
        assertTrue("The caller may retry a ballot itself", error.retryable)
        assertEquals(1, server.requestCount)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun postIsSentOnceAfterAServerError() = blocking {
        enqueueError(500, "SERVER", "boom")
        enqueueJson(201, Fixtures.ROOM_DETAIL)

        assertEquals(ErrorCode.SERVER, service.createRoom("x").failure().code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun okHttpDoesNotSilentlyReplayAPostOn503WithRetryAfterZero() = blocking {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        enqueueJson(201, Fixtures.ROOM_DETAIL)

        val error = service.createRoom("x").failure()

        assertEquals(ErrorCode.SERVER, error.code)
        assertEquals(0, error.retryAfterSeconds)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun okHttpDoesNotSilentlyReplayABodylessDeleteOn503WithRetryAfterZero() = blocking {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        enqueueNoContent()

        assertEquals(ErrorCode.SERVER, service.deleteRoom("r1").failure().code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun okHttpDoesNotSilentlyReplayAPostOn408() = blocking {
        server.enqueue(MockResponse().setResponseCode(408))
        enqueueJson(201, Fixtures.ROOM_DETAIL)

        assertEquals(ErrorCode.UNKNOWN, service.createRoom("x").failure().code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun putPatchAndDeleteAreSentOnceAfterANetworkFailure() = blocking {
        // An empty queue answers 404 at once, so a retry would show up as an extra request and a different code.
        (server.dispatcher as QueueDispatcher).setFailFast(true)
        val calls: List<suspend () -> PhotoDuelError> = listOf(
            { service.setBlocked("u2", blocked = true).failure() },
            { service.renameSelf("Grace").failure() },
            { service.deleteRoom("r1").failure() },
        )

        calls.forEachIndexed { index, call ->
            enqueueDisconnect()
            assertEquals(ErrorCode.NETWORK, call().code)
            assertEquals(index + 1, server.requestCount)
        }
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun aRetriedGetReadsTheTokenAgainForEachAttempt() = blocking {
        enqueueDisconnect()
        enqueueJson(200, Fixtures.USER)

        service.currentUser().success()

        assertEquals(2, store.reads.get())
    }
}
