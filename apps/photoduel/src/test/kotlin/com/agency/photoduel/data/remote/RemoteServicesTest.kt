package com.agency.photoduel.data.remote

import com.agency.photoduel.domain.model.AppResult
import java.util.concurrent.TimeUnit
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The app's entry point: [RemoteServices.create], base URL handling, client settings and media auth. */
internal class RemoteServicesTest {
    private lateinit var server: MockWebServer
    private val store = FakeSessionStore()

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun takeRequest() = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))

    private fun enqueueUser() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setHeader("Date", "Sun, 11 Oct 2026 10:00:00 GMT")
                .setBody(Fixtures.USER),
        )
    }

    @Test
    fun createReturnsTheHttpServiceAndTheClockItFeeds() = blocking {
        val remote = RemoteServices.create(server.url("/v1/").toString(), store)
        enqueueUser()

        assertTrue(remote.service is RemotePhotoDuelService)
        val clock = remote.clock as OffsetServerClock
        assertFalse(clock.isSynchronized)
        assertTrue(remote.service.currentUser() is AppResult.Success)
        assertTrue(clock.isSynchronized)
        assertNotNull(remote.mediaCallFactory)
    }

    @Test
    fun theBaseUrlMayOmitTheVersionSegment() = blocking {
        val withVersion = RemoteServices.create(server.url("/v1").toString(), store)
        val withoutVersion = RemoteServices.create(server.url("/").toString(), store)
        enqueueUser()
        enqueueUser()

        withVersion.service.currentUser()
        withoutVersion.service.currentUser()

        assertEquals("/v1/me", takeRequest().path)
        assertEquals("/v1/me", takeRequest().path)
        assertEquals(server.url("/v1/media/m1").toString(), withoutVersion.service.mediaUrl("m1"))
    }

    @Test
    fun aPathPrefixInTheBaseUrlIsKept() = blocking {
        val remote = RemoteServices.create(server.url("/photoduel/api/v1/").toString(), store)
        enqueueUser()

        remote.service.currentUser()

        assertEquals("/photoduel/api/v1/me", takeRequest().path)
        assertEquals(server.url("/photoduel/api/v1/media/m1").toString(), remote.service.mediaUrl("m1"))
    }

    @Test
    fun anUnusableBaseUrlThrowsIllegalArgumentException() {
        listOf("", "not a url", "ftp://127.0.0.1/v1/", "photoduel://invite/x").forEach { value ->
            try {
                RemoteServices.create(value, store)
                throw AssertionError("Expected IllegalArgumentException for '$value'")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun clientUsesTheContractTimeoutsAndNeverRetriesOrRedirectsOnItsOwn() {
        val api = PhotoDuelApiClient(server.url("/v1/").toString(), store)
        val client = api.httpClient

        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(30_000, client.readTimeoutMillis)
        assertEquals(60_000, client.writeTimeoutMillis)
        assertFalse(client.retryOnConnectionFailure)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertTrue(client.interceptors.any { it is ServerDateInterceptor })
    }

    @Test
    fun atMostTwoGetRetriesCanBeConfigured() {
        try {
            PhotoDuelApiClient(server.url("/v1/").toString(), store, getRetryDelaysMillis = listOf(1, 2, 3))
            throw AssertionError("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun theMediaClientAddsTheTokenToMediaUrlsOnly() {
        val remote = RemoteServices.create(server.url("/v1/").toString(), store)
        val media = checkNotNull(remote.mediaCallFactory)
        repeat(2) { server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody("jpeg")) }

        media.newCall(Request.Builder().url(remote.service.mediaUrl("m1")).build()).execute().close()
        media.newCall(Request.Builder().url(server.url("/v1/rooms")).build()).execute().close()

        assertEquals("Bearer $TEST_TOKEN", takeRequest().getHeader("Authorization"))
        assertNull(takeRequest().getHeader("Authorization"))
    }

    @Test
    fun theMediaClientNeverSendsTheTokenToAnotherOrigin() {
        val other = MockWebServer()
        other.start()
        try {
            val remote = RemoteServices.create(server.url("/v1/").toString(), store)
            other.enqueue(MockResponse().setBody("jpeg"))

            checkNotNull(remote.mediaCallFactory)
                .newCall(Request.Builder().url(other.url("/v1/media/m1")).build())
                .execute()
                .close()

            assertNull(checkNotNull(other.takeRequest(5, TimeUnit.SECONDS)).getHeader("Authorization"))
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun theMediaClientSendsNoTokenWhenSignedOut() {
        store.token = null
        val remote = RemoteServices.create(server.url("/v1/").toString(), store)
        server.enqueue(MockResponse().setResponseCode(401))

        checkNotNull(remote.mediaCallFactory)
            .newCall(Request.Builder().url(remote.service.mediaUrl("m1")).build())
            .execute()
            .close()

        assertNull(takeRequest().getHeader("Authorization"))
    }
}
