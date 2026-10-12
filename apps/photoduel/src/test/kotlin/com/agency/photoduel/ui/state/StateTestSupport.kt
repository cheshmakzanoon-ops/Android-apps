@file:OptIn(ExperimentalCoroutinesApi::class)

package com.agency.photoduel.ui.state

import com.agency.photoduel.domain.model.AppResult
import com.agency.photoduel.domain.model.ChallengeSpec
import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.InvitationTtl
import com.agency.photoduel.domain.model.PhotoDuelError
import com.agency.photoduel.domain.model.PreparedPhoto
import com.agency.photoduel.domain.model.UserProfile
import com.agency.photoduel.domain.testing.FakeServerClock
import com.agency.photoduel.domain.testing.InMemoryBackend
import com.agency.photoduel.domain.testing.InMemoryLimits
import com.agency.photoduel.domain.testing.InMemoryPhotoDuelService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

// Shared helpers for the ui/state tests. Everything runs on the in-memory service (the same domain rules
// as the server) and a virtual-time scheduler, so no test waits on a real clock.

/** A scope on virtual time. [settle] runs everything that is due; [advance] moves time on. */
internal class TestHarness {
    val scheduler = TestCoroutineScheduler()
    val scope = CoroutineScope(StandardTestDispatcher(scheduler) + SupervisorJob())

    fun settle() = scheduler.advanceUntilIdle()

    fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    fun close() = scope.cancel()
}

internal fun <T> AppResult<T>.value(): T = when (this) {
    is AppResult.Success -> value
    is AppResult.Failure -> throw AssertionError("expected success, got $error")
}

internal fun <T> AppResult<T>.error(): PhotoDuelError = when (this) {
    is AppResult.Success -> throw AssertionError("expected failure, got $value")
    is AppResult.Failure -> error
}

internal fun offline() = PhotoDuelError(ErrorCode.NETWORK, "offline", retryable = true)

/** Small but valid photo for the in-memory service (short edge at least 200 px). */
internal fun photo(width: Int = 400, height: Int = 300) =
    PreparedPhoto(ByteArray(64) { 1 }, ByteArray(16) { 2 }, width, height)

/** A room called "Sunday walkers" with [count] members. Index 0 is the owner (Ada). */
internal class Friends(
    val clock: FakeServerClock,
    val backend: InMemoryBackend,
    val clients: List<InMemoryPhotoDuelService>,
    val users: List<UserProfile>,
    val roomId: String,
) {
    val owner: InMemoryPhotoDuelService get() = clients[0]

    /** Creates and starts a challenge with the given windows. Returns its id. */
    fun startChallenge(submissionHours: Int = 1, votingHours: Int = 1, promptId: String = "p-001"): String = runBlocking {
        val created = owner.createChallenge(roomId, ChallengeSpec(promptId, submissionHours, votingHours)).value()
        owner.startChallenge(created.id).value()
        created.id
    }

    /** Clients at [indices] each submit a photo. Returns the submission ids in the same order. */
    fun submit(challengeId: String, vararg indices: Int): List<String> = runBlocking {
        indices.map { clients[it].submitPhoto(challengeId, photo()) {}.value().id }
    }

    fun vote(challengeId: String, voter: Int, submissionId: String) = runBlocking {
        clients[voter].castBallot(challengeId, submissionId).value()
    }

    /** Moves the server clock past the submission deadline of a challenge with [submissionHours]. */
    fun closeSubmissions(submissionHours: Int = 1) {
        clock.advanceByHours(submissionHours)
        clock.advanceBy(1)
    }

    fun closeVoting(votingHours: Int = 1) {
        clock.advanceByHours(votingHours)
        clock.advanceBy(1)
    }

    companion object {
        private val NAMES = listOf("Ada", "Ben", "Cy", "Dee", "Eve", "Fay", "Gus", "Hal")

        fun create(count: Int = 3, limits: InMemoryLimits = InMemoryLimits()): Friends = runBlocking {
            val clock = FakeServerClock()
            val backend = InMemoryBackend(clock, limits = limits)
            val owner = InMemoryPhotoDuelService(backend)
            val users = mutableListOf(owner.createGuest(NAMES[0]).value().user)
            val room = owner.createRoom("Sunday walkers").value()
            val clients = mutableListOf(owner)
            if (count > 1) {
                val invitation = owner.createInvitation(room.id, InvitationTtl.THREE_DAYS).value()
                for (i in 1 until count) {
                    val client = owner.newClient()
                    users += client.createGuest(NAMES[i]).value().user
                    client.joinWithInvitation(invitation.token).value()
                    clients += client
                }
            }
            Friends(clock, backend, clients, users, room.id)
        }
    }
}
