package com.agency.photoduel.domain.port

/**
 * Server-synchronised time for countdowns only. Rules never use the device clock.
 * The data layer updates the offset from each response's Date header.
 */
interface ServerClock {
    fun nowEpochMillis(): Long
}
