package com.agency.photoduel.data.remote

import com.agency.photoduel.domain.model.AppResult
import com.agency.photoduel.domain.model.PhotoDuelError
import com.agency.photoduel.domain.port.SessionStore
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before

/** A fake, obviously non-secret bearer value. Tests assert that it never leaks into error text. */
internal const val TEST_TOKEN = "test-session-token-not-real-0001"

/** Runs a suspending test body on a real thread (the client waits on real sockets). Always returns Unit. */
internal fun blocking(block: suspend () -> Unit) {
    runBlocking { block() }
}

/** In-memory [SessionStore] that records every write. */
internal class FakeSessionStore(@Volatile var token: String? = TEST_TOKEN) : SessionStore {
    val writes: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    val reads = AtomicInteger(0)

    @Volatile
    var failReads = false

    override suspend fun read(): String? {
        reads.incrementAndGet()
        if (failReads) throw IllegalStateException("keystore unavailable")
        return token
    }

    override suspend fun write(token: String?) {
        writes += token
        this.token = token
    }
}

/** Starts a [MockWebServer] per test and wires a client and service to it. Retry waits are recorded, not slept. */
internal abstract class RemoteTestBase {
    protected lateinit var server: MockWebServer
    protected val store = FakeSessionStore()
    protected val sleeps: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    protected lateinit var api: PhotoDuelApiClient
    protected lateinit var service: RemotePhotoDuelService

    protected open fun clock(): OffsetServerClock = OffsetServerClock()

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
        api = PhotoDuelApiClient(server.url("/v1/").toString(), store, clock(), sleep = { sleeps += it })
        service = RemotePhotoDuelService(api)
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    protected fun enqueueJson(code: Int, body: String, vararg headers: Pair<String, String>) {
        val response = MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json; charset=utf-8")
            .setBody(body)
        headers.forEach { (name, value) -> response.setHeader(name, value) }
        server.enqueue(response)
    }

    protected fun enqueueNoContent() {
        server.enqueue(MockResponse().setResponseCode(204))
    }

    protected fun enqueueError(code: Int, errorCode: String, message: String = "nope", retryAfterSeconds: Int? = null) {
        val retry = retryAfterSeconds?.let { ""","retryAfterSeconds":$it""" } ?: ""
        enqueueJson(code, """{"error":{"code":"$errorCode","message":"$message"$retry}}""")
    }

    protected fun takeRequest(): RecordedRequest =
        checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "No request reached the server" }

    protected fun RecordedRequest.assertRoute(method: String, path: String) {
        assertEquals(method, this.method)
        assertEquals(path, this.path)
    }

    protected fun RecordedRequest.assertAuthenticated() {
        assertEquals("Bearer $TEST_TOKEN", getHeader("Authorization"))
        assertEquals("application/json", getHeader("Accept"))
    }

    protected fun RecordedRequest.assertAnonymous() {
        assertNull(getHeader("Authorization"))
        assertEquals("application/json", getHeader("Accept"))
    }

    /** The body is JSON and equal to [expected] (same keys, same values, nothing extra). */
    protected fun RecordedRequest.assertJsonBody(expected: String) {
        assertEquals("application/json; charset=utf-8", getHeader("Content-Type"))
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(body.readUtf8()))
    }

    /** No payload: Content-Length 0 and no Content-Type, as for a bare DELETE. */
    protected fun RecordedRequest.assertNoBody() {
        assertEquals(0L, bodySize)
        assertNull(getHeader("Content-Type"))
    }

    protected fun <T> AppResult<T>.success(): T {
        if (this is AppResult.Failure) throw AssertionError("Expected success, got $error")
        return (this as AppResult.Success<T>).value
    }

    protected fun AppResult<*>.failure(): PhotoDuelError {
        assertTrue("Expected failure, got $this", this is AppResult.Failure)
        val error = (this as AppResult.Failure).error
        assertFalse("Token leaked into $error", error.toString().contains(TEST_TOKEN))
        return error
    }
}

/** JSON bodies shaped exactly as docs/api-contract.md shows them. */
internal object Fixtures {
    const val USER = """{"id":"u1","displayName":"Ada","hasRecoveryCode":false}"""

    const val SESSION = """{"user":$USER,"session":{"token":"$TEST_TOKEN","expiresAtEpochMillis":1234}}"""

    const val PROMPT = """{"id":"p-001","title":"Morning light","brief":"Catch the first light of the day."}"""

    const val ROOM_DETAIL = """{
        "id":"r1","name":"Sunday walkers","capacity":8,
        "viewerUserId":"u1","viewerRole":"OWNER",
        "members":[
          {"userId":"u1","displayName":"Ada","role":"OWNER"},
          {"userId":"u2","displayName":"Grace","role":"MEMBER"}
        ],
        "futureField":{"ignored":true}
    }"""

    const val CHALLENGE_OPEN = """{
        "id":"c1","roomId":"r1","roundNumber":2,
        "prompt":$PROMPT,
        "state":"OPEN_FOR_SUBMISSIONS","outcome":null,
        "submissionDeadlineEpochMillis":1234,"votingDeadlineEpochMillis":5678,
        "serverTimeEpochMillis":1200
    }"""

    const val CHALLENGE_CREATED = """{
        "id":"c2","roomId":"r1","roundNumber":3,
        "prompt":$PROMPT,
        "state":"CREATED","outcome":null,
        "submissionDeadlineEpochMillis":null,"votingDeadlineEpochMillis":null,
        "serverTimeEpochMillis":1300
    }"""

    const val CHALLENGE_COMPLETED = """{
        "id":"c0","roomId":"r1","roundNumber":1,
        "prompt":$PROMPT,
        "state":"COMPLETED","outcome":"TIE",
        "submissionDeadlineEpochMillis":100,"votingDeadlineEpochMillis":200,
        "serverTimeEpochMillis":300
    }"""

    const val SUBMISSION_MINE = """{
        "id":"s1","challengeId":"c1","authorUserId":"u1","authorDisplayName":"Ada",
        "status":"ACTIVE","thumbnailMediaId":"m-thumb-1","fullMediaId":"m-full-1",
        "width":1600,"height":1067,"isMine":true
    }"""

    const val SUBMISSION_DELETED_AUTHOR = """{
        "id":"s2","challengeId":"c0","authorUserId":null,"authorDisplayName":"Deleted player",
        "status":"ACTIVE","thumbnailMediaId":"m-thumb-2","fullMediaId":"m-full-2",
        "width":1200,"height":1600,"isMine":false
    }"""

    const val RESULT_TIE = """{
        "outcome":"TIE",
        "coWinnerSubmissionIds":["s1","s2"],
        "tallies":[{"submissionId":"s1","votes":2},{"submissionId":"s2","votes":2}],
        "ballotCount":4,
        "eligibleVoterCount":5
    }"""
}
