package com.agency.photoduel.data.remote

import com.agency.photoduel.domain.model.ErrorCode
import java.io.IOException
import java.net.SocketTimeoutException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/api-contract.md section 3, through real HTTP responses and through the mapper directly. */
internal class ErrorMappingTest : RemoteTestBase() {

    @Test
    fun unauthorizedIsUnauthenticatedAndNotRetryable() = blocking {
        enqueueError(401, "UNAUTHENTICATED", "Authentication required")

        val error = service.currentUser().failure()

        assertEquals(ErrorCode.UNAUTHENTICATED, error.code)
        assertEquals("Authentication required", error.message)
        assertFalse(error.retryable)
        assertNull(error.retryAfterSeconds)
    }

    @Test
    fun notFoundIsNotFound() = blocking {
        enqueueError(404, "NOT_FOUND", "Not found")

        val error = service.room("r404").failure()

        assertEquals(ErrorCode.NOT_FOUND, error.code)
        assertFalse(error.retryable)
    }

    @Test
    fun notFoundWithInviteUnavailableKeepsTheInviteCode() = blocking {
        enqueueError(404, "INVITE_UNAVAILABLE", "This invitation is no longer available")

        val error = service.previewInvitation("AbC-dEf_123").failure()

        assertEquals(ErrorCode.INVITE_UNAVAILABLE, error.code)
        assertFalse(error.retryable)
    }

    @Test
    fun conflictWithAlreadyVotedKeepsTheBallotCode() = blocking {
        enqueueError(409, "ALREADY_VOTED", "You have already voted in this challenge")

        assertEquals(ErrorCode.ALREADY_VOTED, service.castBallot("c1", "s9").failure().code)
    }

    @Test
    fun goneIsDeadlinePassed() = blocking {
        enqueueError(410, "DEADLINE_PASSED", "The voting deadline has passed")

        val error = service.castBallot("c1", "s2").failure()

        assertEquals(ErrorCode.DEADLINE_PASSED, error.code)
        assertFalse(error.retryable)
    }

    @Test
    fun payloadTooLargeAndUnsupportedMedia() = blocking {
        enqueueError(413, "PAYLOAD_TOO_LARGE", "Upload over 3 MB")
        enqueueError(415, "UNSUPPORTED_MEDIA", "Image too small")

        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, service.createRoom("x").failure().code)
        assertEquals(ErrorCode.UNSUPPORTED_MEDIA, service.createRoom("x").failure().code)
    }

    @Test
    fun rateLimitedCarriesRetryAfterSecondsFromTheBody() = blocking {
        enqueueJson(
            429,
            """{"error":{"code":"RATE_LIMITED","message":"Too many requests, try again later","retryAfterSeconds":42}}""",
            "Retry-After" to "42",
        )

        val error = service.createRoom("Sunday walkers").failure()

        assertEquals(ErrorCode.RATE_LIMITED, error.code)
        assertEquals(42, error.retryAfterSeconds)
        assertTrue(error.retryable)
    }

    @Test
    fun rateLimitedFallsBackToTheRetryAfterHeader() = blocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "17").setBody("slow down"))

        val error = service.createRoom("Sunday walkers").failure()

        assertEquals(ErrorCode.RATE_LIMITED, error.code)
        assertEquals(17, error.retryAfterSeconds)
        assertEquals(ApiErrors.defaultMessage(ErrorCode.RATE_LIMITED), error.message)
    }

    @Test
    fun aNetworkFailureIsNetworkAndRetryable() = blocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        val error = service.createRoom("Sunday walkers").failure()

        assertEquals(ErrorCode.NETWORK, error.code)
        assertTrue(error.retryable)
        assertEquals(ApiErrors.NETWORK_MESSAGE, error.message)
    }

    @Test
    fun anUnreachableServerIsNetworkAndRetryable() = blocking {
        val closed = MockWebServer()
        closed.start()
        val closedUrl = closed.url("/v1/").toString()
        closed.shutdown()
        val unreachable = RemotePhotoDuelService(PhotoDuelApiClient(closedUrl, store, sleep = { }))

        val error = unreachable.createRoom("Sunday walkers").failure()

        assertEquals(ErrorCode.NETWORK, error.code)
        assertTrue(error.retryable)
    }

    @Test
    fun theTokenNeverAppearsInErrorTextEvenWhenTheServerEchoesIt() = blocking {
        enqueueError(401, "UNAUTHENTICATED", "Session $TEST_TOKEN was revoked")
        enqueueError(500, "SERVER", "token=$TEST_TOKEN")

        val unauthenticated = service.createRoom("x").failure()
        val serverError = service.createRoom("x").failure()

        assertEquals("Session ${ApiErrors.REDACTED} was revoked", unauthenticated.message)
        assertFalse(serverError.message.contains(TEST_TOKEN))
    }

    @Test
    fun theCodeInTheBodyDecidesOverTheStatus() = blocking {
        enqueueError(422, "VALIDATION", "You cannot block yourself")

        assertEquals(ErrorCode.VALIDATION, service.setBlocked("u1", blocked = true).failure().code)
    }

    @Test
    fun aBodyWithoutAKnownCodeFallsBackToTheStatus() = blocking {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>"))
        enqueueJson(409, """{"error":{"code":"SOMETHING_NEW","message":"Newer server"}}""")

        val gateway = service.createRoom("x").failure()
        val unknownCode = service.createRoom("x").failure()

        assertEquals(ErrorCode.SERVER, gateway.code)
        assertTrue(gateway.retryable)
        assertEquals(ApiErrors.defaultMessage(ErrorCode.SERVER), gateway.message)
        assertEquals(ErrorCode.CONFLICT, unknownCode.code)
        assertEquals("Newer server", unknownCode.message)
    }

    @Test
    fun aRedirectIsNotFollowedAndIsUnknown() = blocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/v1/elsewhere"))

        assertEquals(ErrorCode.UNKNOWN, service.createRoom("x").failure().code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun statusTableMatchesSectionThree() {
        val expected = mapOf(
            400 to ErrorCode.VALIDATION,
            401 to ErrorCode.UNAUTHENTICATED,
            403 to ErrorCode.FORBIDDEN,
            404 to ErrorCode.NOT_FOUND,
            409 to ErrorCode.CONFLICT,
            410 to ErrorCode.DEADLINE_PASSED,
            413 to ErrorCode.PAYLOAD_TOO_LARGE,
            415 to ErrorCode.UNSUPPORTED_MEDIA,
            422 to ErrorCode.INVALID_BALLOT,
            429 to ErrorCode.RATE_LIMITED,
            500 to ErrorCode.SERVER,
            503 to ErrorCode.SERVER,
            418 to ErrorCode.UNKNOWN,
            302 to ErrorCode.UNKNOWN,
        )
        expected.forEach { (status, code) -> assertEquals("HTTP $status", code, ApiErrors.forStatus(status)) }
    }

    @Test
    fun everyServerCodeOfSectionThreeIsReadFromTheBody() {
        val serverCodes = ErrorCode.entries - setOf(ErrorCode.NETWORK, ErrorCode.TIMEOUT, ErrorCode.UNKNOWN)
        serverCodes.forEach { code ->
            val body = """{"error":{"code":"${code.name}","message":"m"}}"""
            val error = ApiErrors.fromResponse(400, body, null, emptyList())
            assertEquals(code, error.code)
            assertEquals(code == ErrorCode.RATE_LIMITED || code == ErrorCode.SERVER, error.retryable)
        }
    }

    @Test
    fun clientOnlyCodesAreNeverTakenFromABody() {
        val error = ApiErrors.fromResponse(400, """{"error":{"code":"NETWORK","message":"m"}}""", null, emptyList())
        assertEquals(ErrorCode.VALIDATION, error.code)
    }

    @Test
    fun timeoutsAreTimeoutAndOtherIoFailuresAreNetwork() {
        val timeout = ApiErrors.transport(SocketTimeoutException("Read timed out"))
        val reset = ApiErrors.transport(IOException("Connection reset while reading /v1/invitations/secret"))

        assertEquals(ErrorCode.TIMEOUT, timeout.code)
        assertTrue(timeout.retryable)
        assertEquals(ErrorCode.NETWORK, reset.code)
        assertTrue(reset.retryable)
        assertFalse("Exception text must not leak", reset.message.contains("secret"))
    }
}
