package com.agency.photoduel.data.remote

import com.agency.photoduel.domain.port.ServerClock
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Interceptor
import okhttp3.Response

/**
 * [ServerClock] that keeps the offset between the device wall clock and the server clock, learned from
 * the `Date` header that every API response carries (docs/api-contract.md, common rules).
 *
 * It is for countdowns only: every rule is decided by the server. Until the first response arrives the
 * offset is zero, so the clock reads the device time and [isSynchronized] is false.
 *
 * The `Date` header has a resolution of one second and is truncated, so the real server instant lies
 * in `[date, date + 1000)`. The midpoint of that window is used, which keeps the average error near zero
 * instead of always running up to one second behind.
 */
class OffsetServerClock(
    private val deviceMillis: () -> Long = System::currentTimeMillis,
) : ServerClock {
    private val offset = AtomicLong(0L)

    @Volatile
    private var synchronized = false

    override fun nowEpochMillis(): Long = deviceMillis() + offset.get()

    /** Server time minus device time, in milliseconds. Zero until the first `Date` header is seen. */
    val offsetMillis: Long get() = offset.get()

    /** True once at least one response carried a readable `Date` header. */
    val isSynchronized: Boolean get() = synchronized

    /**
     * Records a `Date` header value (already parsed to epoch milliseconds) observed now. The latest
     * observation wins, so a corrected device clock or server clock is picked up on the next response.
     */
    fun recordServerDate(serverDateEpochMillis: Long) {
        offset.set(serverDateEpochMillis + DATE_RESOLUTION_MIDPOINT_MILLIS - deviceMillis())
        synchronized = true
    }

    companion object {
        /** Half of the one-second resolution of an HTTP date. */
        const val DATE_RESOLUTION_MIDPOINT_MILLIS: Long = 500L
    }
}

/** Feeds the `Date` header of every response, including error responses, into [clock]. */
class ServerDateInterceptor(private val clock: OffsetServerClock) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        response.headers.getDate("Date")?.let { clock.recordServerDate(it.time) }
        return response
    }
}
