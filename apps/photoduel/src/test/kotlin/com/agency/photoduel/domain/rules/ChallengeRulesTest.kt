package com.agency.photoduel.domain.rules

import com.agency.photoduel.domain.model.ChallengeOutcome
import com.agency.photoduel.domain.model.ChallengeSpec
import com.agency.photoduel.domain.model.ChallengeState
import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.SubmissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChallengeRulesTest {

    private val t0 = 1_767_225_600_000L
    private val hour = Durations.HOUR_MILLIS
    private val spec = ChallengeSpec("p-001", submissionHours = 24, votingHours = 6)
    private val created = ChallengeLifecycle.created(spec)
    private val open = ok(ChallengeRules.start(created, memberCount = 3, nowEpochMillis = t0))
    private val submissionDeadline = t0 + 24 * hour
    private val votingDeadline = submissionDeadline + 6 * hour

    private val roster = setOf("a", "b", "c")
    private fun tally(vararg active: String, ballots: List<BallotRef> = emptyList()) = TallyInput(
        roster = roster,
        activeMembers = roster,
        submissions = listOf("a", "b", "c").map {
            SubmissionRef("s$it", it, if (it in active) SubmissionStatus.ACTIVE else SubmissionStatus.WITHDRAWN)
        },
        ballots = ballots,
    )

    private val fullTally = tally("a", "b", "c", ballots = listOf(BallotRef("a", "sb"), BallotRef("c", "sb")))
    private val voting = ok(ChallengeRules.closeSubmissions(open, submissionDeadline, fullTally))
    private val completed = ok(ChallengeRules.closeVoting(voting, votingDeadline, fullTally))
    private val archived = ok(ChallengeRules.archive(completed, votingDeadline + Durations.days(30)))
    private val cancelled = ok(ChallengeRules.cancel(created, t0))

    private val lifecycleIn: Map<ChallengeState, ChallengeLifecycle> = mapOf(
        ChallengeState.CREATED to created,
        ChallengeState.OPEN_FOR_SUBMISSIONS to open,
        ChallengeState.VOTING to voting,
        ChallengeState.COMPLETED to completed,
        ChallengeState.ARCHIVED to archived,
        ChallengeState.CANCELLED to cancelled,
    )

    // ---- Transition table --------------------------------------------------------------------------

    @Test
    fun transitionTableMatchesArchitectureSection7() {
        val expected = mapOf(
            ChallengeState.CREATED to mapOf(
                ChallengeEvent.START to setOf(ChallengeState.OPEN_FOR_SUBMISSIONS),
                ChallengeEvent.CANCEL to setOf(ChallengeState.CANCELLED),
            ),
            ChallengeState.OPEN_FOR_SUBMISSIONS to mapOf(
                ChallengeEvent.CLOSE_SUBMISSIONS to setOf(ChallengeState.VOTING, ChallengeState.COMPLETED),
                ChallengeEvent.CANCEL to setOf(ChallengeState.CANCELLED),
            ),
            ChallengeState.VOTING to mapOf(ChallengeEvent.CLOSE_VOTING to setOf(ChallengeState.COMPLETED)),
            ChallengeState.COMPLETED to mapOf(ChallengeEvent.ARCHIVE to setOf(ChallengeState.ARCHIVED)),
            ChallengeState.ARCHIVED to emptyMap(),
            ChallengeState.CANCELLED to emptyMap(),
        )
        assertEquals(expected, ChallengeRules.TRANSITIONS)
        assertEquals(ChallengeState.entries.toSet(), ChallengeRules.TRANSITIONS.keys)
    }

    @Test
    fun everyStateEventPairIsEitherAllowedOrExplicitlyRefused() {
        var allowed = 0
        var refused = 0
        for (state in ChallengeState.entries) {
            val lifecycle = lifecycleIn.getValue(state)
            assertEquals(state, lifecycle.state)
            for (event in ChallengeEvent.entries) {
                val result = apply(event, lifecycle)
                val targets = ChallengeRules.targetsOf(state, event)
                if (event in ChallengeRules.allowedEvents(state)) {
                    val next = result.valueOrNull()
                    assertNotNull("$state + $event should be allowed: $result", next)
                    assertTrue("$state + $event went to ${next!!.state}", next.state in targets)
                    allowed++
                } else {
                    val reason = result.refusalOrNull()
                    assertEquals("$state + $event", TransitionFailure.InvalidTransition(state, event), reason)
                    assertEquals(ErrorCode.INVALID_TRANSITION, reason!!.code)
                    assertTrue(targets.isEmpty())
                    refused++
                }
            }
        }
        assertEquals(6, allowed)
        assertEquals(ChallengeState.entries.size * ChallengeEvent.entries.size - 6, refused)
    }

    @Test
    fun terminalStatesAllowNothing() {
        for (state in ChallengeRules.TERMINAL_STATES) {
            assertTrue(ChallengeRules.allowedEvents(state).isEmpty())
        }
    }

    // ---- Start -------------------------------------------------------------------------------------

    @Test
    fun startNeedsAtLeastThreeMembers() {
        for (members in 0..2) {
            val reason = ChallengeRules.start(created, members, t0).refusalOrNull()
            assertEquals(TransitionFailure.NotEnoughMembers(members, 3), reason)
            assertEquals(ErrorCode.CONFLICT, reason!!.code)
        }
        assertTrue(ChallengeRules.start(created, 3, t0).isOk)
        assertTrue(ChallengeRules.start(created, 8, t0).isOk)
    }

    @Test
    fun startFixesBothDeadlinesFromTheStartInstant() {
        assertEquals(ChallengeState.OPEN_FOR_SUBMISSIONS, open.state)
        assertEquals(t0, open.startedAtEpochMillis)
        assertEquals(t0 + 24 * hour, open.submissionDeadlineEpochMillis)
        assertEquals(t0 + 30 * hour, open.votingDeadlineEpochMillis)
        assertNull(open.outcome)

        for (submissionHours in Validation.SUBMISSION_HOURS) {
            for (votingHours in Validation.VOTING_HOURS) {
                val started = ok(
                    ChallengeRules.start(ChallengeLifecycle.created(ChallengeSpec("p-001", submissionHours, votingHours)), 3, t0 + 7),
                )
                assertEquals(t0 + 7 + submissionHours * hour, started.submissionDeadlineEpochMillis)
                assertEquals(t0 + 7 + (submissionHours + votingHours) * hour, started.votingDeadlineEpochMillis)
            }
        }
    }

    @Test
    fun startRefusesWindowsOutsideTheAllowedOptions() {
        val bad = ChallengeLifecycle.created(ChallengeSpec("p-001", submissionHours = 12, votingHours = 6))
        val reason = ChallengeRules.start(bad, 3, t0).refusalOrNull()
        assertEquals(TransitionFailure.InvalidWindow(12, 6), reason)
        assertEquals(ErrorCode.VALIDATION, reason!!.code)
    }

    @Test
    fun createdHasNoDeadlines() {
        assertNull(created.submissionDeadlineEpochMillis)
        assertNull(created.votingDeadlineEpochMillis)
        assertNull(created.outcome)
    }

    // ---- Submission deadline -----------------------------------------------------------------------

    @Test
    fun submissionWindowBoundaryIsExact() {
        assertTrue(ChallengeRules.isSubmissionWindowOpen(open, submissionDeadline - 1))
        assertFalse(ChallengeRules.isSubmissionWindowOpen(open, submissionDeadline))
        assertFalse(ChallengeRules.isSubmissionWindowOpen(open, submissionDeadline + 1))
        assertFalse(ChallengeRules.isSubmissionWindowOpen(created, t0))
    }

    @Test
    fun closeSubmissionsAtDeadlineMinusOneIsNotYetDue() {
        val reason = ChallengeRules.closeSubmissions(open, submissionDeadline - 1, fullTally).refusalOrNull()
        assertEquals(TransitionFailure.NotYetDue(ChallengeEvent.CLOSE_SUBMISSIONS, submissionDeadline, submissionDeadline - 1), reason)
        assertEquals(ErrorCode.INVALID_TRANSITION, reason!!.code)
    }

    @Test
    fun closeSubmissionsAtAndAfterDeadlineOpensVoting() {
        for (now in listOf(submissionDeadline, submissionDeadline + 1)) {
            val next = ok(ChallengeRules.closeSubmissions(open, now, fullTally))
            assertEquals(ChallengeState.VOTING, next.state)
            assertNull(next.result)
        }
    }

    @Test
    fun fewerThanTwoActiveSubmissionsShortCircuitsToCompleted() {
        for (input in listOf(tally(), tally("a"))) {
            val next = ok(ChallengeRules.closeSubmissions(open, submissionDeadline, input))
            assertEquals(ChallengeState.COMPLETED, next.state)
            assertEquals(ChallengeOutcome.INSUFFICIENT_SUBMISSIONS, next.outcome)
            assertEquals(submissionDeadline, next.completedAtEpochMillis)
            assertEquals(0, next.result!!.ballotCount)
            assertEquals(3, next.result!!.eligibleVoterCount)
        }
        assertEquals(ChallengeState.VOTING, ok(ChallengeRules.closeSubmissions(open, submissionDeadline, tally("a", "b"))).state)
    }

    @Test
    fun submissionsByAuthorsOffTheRosterDoNotKeepTheChallengeAlive() {
        val input = tally("a").copy(
            submissions = tally("a").submissions + SubmissionRef("sx", "x", SubmissionStatus.ACTIVE),
        )
        val next = ok(ChallengeRules.closeSubmissions(open, submissionDeadline, input))
        assertEquals(ChallengeOutcome.INSUFFICIENT_SUBMISSIONS, next.outcome)
    }

    // ---- Voting deadline ---------------------------------------------------------------------------

    @Test
    fun votingWindowBoundaryIsExact() {
        assertTrue(ChallengeRules.isVotingWindowOpen(voting, votingDeadline - 1))
        assertFalse(ChallengeRules.isVotingWindowOpen(voting, votingDeadline))
        assertFalse(ChallengeRules.isVotingWindowOpen(voting, votingDeadline + 1))
        assertFalse(ChallengeRules.isVotingWindowOpen(open, submissionDeadline - 1))
    }

    @Test
    fun closeVotingBoundaries() {
        assertEquals(
            TransitionFailure.NotYetDue(ChallengeEvent.CLOSE_VOTING, votingDeadline, votingDeadline - 1),
            ChallengeRules.closeVoting(voting, votingDeadline - 1, fullTally).refusalOrNull(),
        )
        for (now in listOf(votingDeadline, votingDeadline + 1)) {
            val next = ok(ChallengeRules.closeVoting(voting, now, fullTally))
            assertEquals(ChallengeState.COMPLETED, next.state)
            assertEquals(votingDeadline, next.completedAtEpochMillis)
            assertEquals(ChallengeOutcome.WINNER, next.outcome)
            assertEquals(listOf("sb"), next.result!!.coWinnerSubmissionIds)
        }
    }

    // ---- Archive -----------------------------------------------------------------------------------

    @Test
    fun archiveHappensExactlyThirtyDaysAfterCompletion() {
        val due = votingDeadline + Durations.days(30)
        assertEquals(due, ChallengeRules.archiveAt(completed.completedAtEpochMillis!!))
        assertEquals(
            TransitionFailure.NotYetDue(ChallengeEvent.ARCHIVE, due, due - 1),
            ChallengeRules.archive(completed, due - 1).refusalOrNull(),
        )
        for (now in listOf(due, due + 1)) {
            val next = ok(ChallengeRules.archive(completed, now))
            assertEquals(ChallengeState.ARCHIVED, next.state)
            assertEquals(completed.result, next.result)
        }
    }

    // ---- Cancel ------------------------------------------------------------------------------------

    @Test
    fun cancelFromCreatedAndFromOpenBeforeTheDeadline() {
        val fromCreated = ok(ChallengeRules.cancel(created, t0 + 5))
        assertEquals(ChallengeState.CANCELLED, fromCreated.state)
        assertEquals(t0 + 5, fromCreated.cancelledAtEpochMillis)
        assertEquals(ChallengeState.CANCELLED, ok(ChallengeRules.cancel(open, submissionDeadline - 1)).state)
    }

    @Test
    fun cancelIsRefusedOnceTheSubmissionDeadlineIsReachedEvenIfStillStoredAsOpen() {
        for (now in listOf(submissionDeadline, submissionDeadline + 1)) {
            assertEquals(
                TransitionFailure.InvalidTransition(ChallengeState.OPEN_FOR_SUBMISSIONS, ChallengeEvent.CANCEL),
                ChallengeRules.cancel(open, now).refusalOrNull(),
            )
        }
    }

    @Test
    fun cancelIsRefusedFromVotingCompletedArchivedAndCancelled() {
        for (state in listOf(ChallengeState.VOTING, ChallengeState.COMPLETED, ChallengeState.ARCHIVED, ChallengeState.CANCELLED)) {
            val reason = ChallengeRules.cancel(lifecycleIn.getValue(state), t0).refusalOrNull()
            assertEquals(TransitionFailure.InvalidTransition(state, ChallengeEvent.CANCEL), reason)
        }
    }

    // ---- Advance (lazy evaluation) -----------------------------------------------------------------

    @Test
    fun advanceCatchesUpThroughEveryDueTransitionInOrder() {
        val result = ChallengeRules.advance(open, votingDeadline) { fullTally }
        assertEquals(listOf(ChallengeEvent.CLOSE_SUBMISSIONS, ChallengeEvent.CLOSE_VOTING), result.applied)
        assertEquals(ChallengeState.COMPLETED, result.lifecycle.state)
        assertEquals(completed, result.lifecycle)

        val later = ChallengeRules.advance(open, votingDeadline + Durations.days(30)) { fullTally }
        assertEquals(
            listOf(ChallengeEvent.CLOSE_SUBMISSIONS, ChallengeEvent.CLOSE_VOTING, ChallengeEvent.ARCHIVE),
            later.applied,
        )
        assertEquals(ChallengeState.ARCHIVED, later.lifecycle.state)
    }

    @Test
    fun advanceStopsAtTheFirstTransitionThatIsNotDue() {
        val atDeadline = ChallengeRules.advance(open, submissionDeadline) { fullTally }
        assertEquals(listOf(ChallengeEvent.CLOSE_SUBMISSIONS), atDeadline.applied)
        assertEquals(ChallengeState.VOTING, atDeadline.lifecycle.state)

        val insufficient = ChallengeRules.advance(open, votingDeadline + 1) { tally("a") }
        assertEquals(listOf(ChallengeEvent.CLOSE_SUBMISSIONS), insufficient.applied)
        assertEquals(ChallengeOutcome.INSUFFICIENT_SUBMISSIONS, insufficient.lifecycle.outcome)
    }

    @Test
    fun advanceWithNothingDueReturnsTheSameLifecycleAndNeverReadsTheTally() {
        var reads = 0
        val provider = { reads++; fullTally }
        for ((lifecycle, now) in listOf(
            created to t0 + Durations.days(400),
            open to submissionDeadline - 1,
            voting to votingDeadline - 1,
            cancelled to t0 + Durations.days(400),
            archived to t0 + Durations.days(400),
        )) {
            val result = ChallengeRules.advance(lifecycle, now, provider)
            assertSame(lifecycle, result.lifecycle)
            assertTrue(result.applied.isEmpty())
        }
        assertEquals(0, reads)
    }

    @Test
    fun advanceReadsTheTallyAtMostOnce() {
        var reads = 0
        ChallengeRules.advance(open, votingDeadline) { reads++; fullTally }
        assertEquals(1, reads)
    }

    // ---- Helpers -----------------------------------------------------------------------------------

    /** Applies [event] at an instant where its time condition holds, so only the state decides. */
    private fun apply(event: ChallengeEvent, lifecycle: ChallengeLifecycle): RuleResult<ChallengeLifecycle, TransitionFailure> {
        val farFuture = t0 + Durations.days(400)
        return when (event) {
            ChallengeEvent.START -> ChallengeRules.start(lifecycle, 3, t0)
            ChallengeEvent.CLOSE_SUBMISSIONS -> ChallengeRules.closeSubmissions(lifecycle, farFuture, fullTally)
            ChallengeEvent.CLOSE_VOTING -> ChallengeRules.closeVoting(lifecycle, farFuture, fullTally)
            ChallengeEvent.ARCHIVE -> ChallengeRules.archive(lifecycle, farFuture)
            ChallengeEvent.CANCEL -> ChallengeRules.cancel(lifecycle, t0 + 1)
        }
    }

    private fun <T> ok(result: RuleResult<T, *>): T = when (result) {
        is RuleResult.Ok -> result.value
        is RuleResult.Refused -> throw AssertionError("Expected Ok but was refused: ${result.reason}")
    }
}
