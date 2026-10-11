package com.agency.photoduel.domain.testing

import com.agency.photoduel.domain.port.ServerClock
import com.agency.photoduel.domain.rules.Durations
import java.util.concurrent.atomic.AtomicLong

/**
 * Manually driven server clock for tests and the in-memory service. It never reads the device clock.
 *
 * Like the real server clock it only moves forward: a deadline that has passed can never reopen.
 * Moving it backwards is a programming error in the test and throws [IllegalArgumentException].
 *
 * Test-only. Never wire it into the production composition root.
 */
class FakeServerClock(startEpochMillis: Long = DEFAULT_START_EPOCH_MILLIS) : ServerClock {

    private val now = AtomicLong(startEpochMillis)

    override fun nowEpochMillis(): Long = now.get()

    /** Sets the clock to [epochMillis], which must not be earlier than the current value. */
    fun set(epochMillis: Long) {
        now.updateAndGet { current ->
            require(epochMillis >= current) { "FakeServerClock cannot move backwards ($epochMillis < $current)" }
            epochMillis
        }
    }

    /** Moves the clock forward by [millis] (zero or more) and returns the new value. */
    fun advanceBy(millis: Long): Long {
        require(millis >= 0) { "FakeServerClock cannot move backwards (advanceBy($millis))" }
        return now.addAndGet(millis)
    }

    fun advanceByHours(hours: Int): Long = advanceBy(Durations.hours(hours))

    fun advanceByDays(days: Int): Long = advanceBy(Durations.days(days))

    companion object {
        /** 2026-01-01T00:00:00Z: a fixed, readable starting point. */
        const val DEFAULT_START_EPOCH_MILLIS: Long = 1_767_225_600_000L
    }
}
