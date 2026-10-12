package com.agency.photoduel.data.remote

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [OffsetServerClock] fed by the `Date` header of API responses. */
internal class ServerClockTest : RemoteTestBase() {

    private val deviceNow = AtomicLong(DEVICE_START)
    private val serverClock = OffsetServerClock { deviceNow.get() }

    override fun clock(): OffsetServerClock = serverClock

    private val serverDate = "Sun, 11 Oct 2026 10:00:00 GMT"
    private val serverDateMillis = ZonedDateTime.parse(serverDate, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()

    @Test
    fun beforeAnyResponseTheClockReadsTheDeviceTime() {
        assertFalse(serverClock.isSynchronized)
        assertEquals(DEVICE_START, serverClock.nowEpochMillis())
        assertEquals(0L, serverClock.offsetMillis)
    }

    @Test
    fun theDateHeaderSetsTheOffsetAndTheClockKeepsRunningOnTheDeviceClock() = blocking {
        enqueueJson(200, Fixtures.USER, "Date" to serverDate)

        service.currentUser().success()

        assertTrue(serverClock.isSynchronized)
        val expectedNow = serverDateMillis + OffsetServerClock.DATE_RESOLUTION_MIDPOINT_MILLIS
        assertEquals(expectedNow, serverClock.nowEpochMillis())
        deviceNow.addAndGet(90_000)
        assertEquals(expectedNow + 90_000, serverClock.nowEpochMillis())
    }

    @Test
    fun errorResponsesAlsoUpdateTheOffset() = blocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(410)
                .setHeader("Date", serverDate)
                .setBody("""{"error":{"code":"DEADLINE_PASSED","message":"late"}}"""),
        )

        service.castBallot("c1", "s1").failure()

        assertEquals(serverDateMillis + OffsetServerClock.DATE_RESOLUTION_MIDPOINT_MILLIS, serverClock.nowEpochMillis())
    }

    @Test
    fun aResponseWithoutAReadableDateKeepsThePreviousOffset() = blocking {
        enqueueJson(200, Fixtures.USER, "Date" to serverDate)
        enqueueJson(200, Fixtures.USER)
        enqueueJson(200, Fixtures.USER, "Date" to "not a date")

        service.currentUser().success()
        val offset = serverClock.offsetMillis
        service.currentUser().success()
        service.currentUser().success()

        assertEquals(offset, serverClock.offsetMillis)
    }

    @Test
    fun theLatestDateWins() {
        serverClock.recordServerDate(1_000_000)
        serverClock.recordServerDate(2_000_000)

        assertEquals(2_000_000 + OffsetServerClock.DATE_RESOLUTION_MIDPOINT_MILLIS, serverClock.nowEpochMillis())
        assertEquals(2_000_000 + OffsetServerClock.DATE_RESOLUTION_MIDPOINT_MILLIS - DEVICE_START, serverClock.offsetMillis)
    }

    private companion object {
        const val DEVICE_START = 1_700_000_000_000L
    }
}
