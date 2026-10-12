package com.agency.photoduel.ui.state

import com.agency.photoduel.domain.model.Challenge
import com.agency.photoduel.domain.model.ChallengeState
import com.agency.photoduel.domain.model.Prompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountdownTest {

    private fun challenge(state: ChallengeState, submission: Long? = 1_000L, voting: Long? = 2_000L, serverTime: Long = 500L) =
        Challenge(
            id = "c", roomId = "r", roundNumber = 1, prompt = Prompt("p", "t", "b"), state = state, outcome = null,
            submissionDeadlineEpochMillis = submission, votingDeadlineEpochMillis = voting, serverTimeEpochMillis = serverTime,
        )

    @Test
    fun theRunningDeadlineDependsOnTheState() {
        assertEquals(1_000L, Countdown.deadlineOf(challenge(ChallengeState.OPEN_FOR_SUBMISSIONS)))
        assertEquals(2_000L, Countdown.deadlineOf(challenge(ChallengeState.VOTING)))
        for (state in listOf(ChallengeState.CREATED, ChallengeState.COMPLETED, ChallengeState.ARCHIVED, ChallengeState.CANCELLED)) {
            assertNull(state.name, Countdown.deadlineOf(challenge(state)))
        }
    }

    @Test
    fun remainingTimeIsRoundedUpToTheNextSecond() {
        val parts = Countdown.compute(deadlineEpochMillis = 10_001L, serverNowMillis = 0L)
        assertEquals(11, parts.seconds)
        assertEquals("00:00:11", Countdown.clockText(parts))
        val lastMoment = Countdown.compute(deadlineEpochMillis = 1L, serverNowMillis = 0L)
        assertEquals(1, lastMoment.seconds)
        assertEquals(CountdownKind.URGENT, lastMoment.kind)
    }

    @Test
    fun aPassedDeadlineReadsZeroAndEnded() {
        val parts = Countdown.compute(deadlineEpochMillis = 1_000L, serverNowMillis = 5_000L)
        assertEquals(CountdownKind.ENDED, parts.kind)
        assertEquals(0L, parts.remainingMillis)
        assertEquals("00:00:00", Countdown.clockText(parts))
        assertEquals(0, parts.totalMinutesCeil)
        assertEquals(CountdownKind.ENDED, Countdown.compute(1_000L, 1_000L).kind)
    }

    @Test
    fun theLastTenMinutesAreUrgent() {
        val tenMinutes = Countdown.URGENT_THRESHOLD_MILLIS
        assertEquals(CountdownKind.URGENT, Countdown.compute(tenMinutes, 0L).kind)
        assertEquals(CountdownKind.OPEN, Countdown.compute(tenMinutes + 1, 0L).kind)
    }

    @Test
    fun daysAreShownSeparately() {
        val millis = ((2L * 24 + 3) * 3600 + 4 * 60 + 5) * 1000
        val parts = Countdown.compute(millis, 0L)
        assertEquals(2, parts.days)
        assertEquals(3, parts.hours)
        assertEquals(4, parts.minutes)
        assertEquals(5, parts.seconds)
        assertEquals("2d 03:04:05", Countdown.clockText(parts))
    }

    @Test
    fun minutesForTalkBackAreRoundedUp() {
        assertEquals(1, Countdown.compute(1_000L, 0L).totalMinutesCeil)
        assertEquals(1, Countdown.compute(60_000L, 0L).totalMinutesCeil)
        assertEquals(2, Countdown.compute(60_001L, 0L).totalMinutesCeil)
    }

    @Test
    fun theAnchorUsesOnlyElapsedDisplayTime() {
        // The display clock reads a wildly wrong absolute time; only the difference matters.
        val anchor = ServerTimeAnchor(serverTimeEpochMillis = 1_000_000L, clockAtReceiptMillis = 42L)
        assertEquals(1_000_000L, anchor.serverNow(42L))
        assertEquals(1_005_000L, anchor.serverNow(5_042L))
        // A display clock that jumps backwards never moves server time backwards.
        assertEquals(1_000_000L, anchor.serverNow(0L))
    }

    @Test
    fun theAnchorStartsFromTheChallengeServerTime() {
        val anchor = ServerTimeAnchor.of(challenge(ChallengeState.VOTING, serverTime = 777L), clockNowMillis = 10L)
        assertEquals(777L, anchor.serverTimeEpochMillis)
        assertEquals(10L, anchor.clockAtReceiptMillis)
        assertEquals(787L, anchor.serverNow(20L))
    }
}
