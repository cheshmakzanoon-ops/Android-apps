package com.agency.photoduel.domain.rules

import com.agency.photoduel.domain.model.ChallengeState
import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.SubmissionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class EligibilityTest {

    private val deadline = 1_767_312_000_000L
    private val roster = setOf("a", "b", "c")

    // ---- Submissions -------------------------------------------------------------------------------

    private fun submit(
        user: String = "a",
        state: ChallengeState = ChallengeState.OPEN_FOR_SUBMISSIONS,
        now: Long = deadline - 1,
        submissionDeadline: Long? = deadline,
    ) = Eligibility.checkSubmission(user, roster, state, submissionDeadline, now)

    @Test
    fun submissionBoundaryAroundTheDeadline() {
        assertEquals(RuleResult.Ok(Unit), submit(now = deadline - 1))
        assertEquals(SubmissionRefusal.DeadlinePassed(deadline, deadline), submit(now = deadline).refusalOrNull())
        assertEquals(SubmissionRefusal.DeadlinePassed(deadline, deadline + 1), submit(now = deadline + 1).refusalOrNull())
        assertEquals(ErrorCode.DEADLINE_PASSED, submit(now = deadline).refusalOrNull()!!.code)
    }

    @Test
    fun submissionRequiresRosterMembership() {
        val reason = submit(user = "x").refusalOrNull()
        assertEquals(SubmissionRefusal.NotOnRoster, reason)
        assertEquals(ErrorCode.FORBIDDEN, reason!!.code)
    }

    @Test
    fun lateSubmissionIsDeadlinePassedWhetherOrNotTheStateHasAdvanced() {
        for (state in listOf(ChallengeState.VOTING, ChallengeState.COMPLETED, ChallengeState.ARCHIVED)) {
            assertEquals(ErrorCode.DEADLINE_PASSED, submit(state = state, now = deadline + 1).refusalOrNull()!!.code)
        }
    }

    @Test
    fun submissionInCreatedOrCancelledIsInvalidTransition() {
        for (state in listOf(ChallengeState.CREATED, ChallengeState.CANCELLED)) {
            val reason = submit(state = state, submissionDeadline = null).refusalOrNull()
            assertEquals(SubmissionRefusal.WrongState(state), reason)
            assertEquals(ErrorCode.INVALID_TRANSITION, reason!!.code)
        }
    }

    @Test
    fun withdrawalOnlyWhileTheWindowIsOpen() {
        fun withdraw(state: ChallengeState, now: Long, has: Boolean = true) =
            Eligibility.checkWithdrawal("a", roster, state, deadline, now, has)

        assertEquals(RuleResult.Ok(Unit), withdraw(ChallengeState.OPEN_FOR_SUBMISSIONS, deadline - 1))
        assertEquals(ErrorCode.INVALID_TRANSITION, withdraw(ChallengeState.OPEN_FOR_SUBMISSIONS, deadline).refusalOrNull()!!.code)
        assertEquals(ErrorCode.INVALID_TRANSITION, withdraw(ChallengeState.OPEN_FOR_SUBMISSIONS, deadline + 1).refusalOrNull()!!.code)
        assertEquals(ErrorCode.INVALID_TRANSITION, withdraw(ChallengeState.VOTING, deadline - 1).refusalOrNull()!!.code)
        assertEquals(SubmissionRefusal.NothingToWithdraw, withdraw(ChallengeState.OPEN_FOR_SUBMISSIONS, deadline - 1, has = false).refusalOrNull())
        assertEquals(
            SubmissionRefusal.NotOnRoster,
            Eligibility.checkWithdrawal("x", roster, ChallengeState.OPEN_FOR_SUBMISSIONS, deadline, deadline - 1, true).refusalOrNull(),
        )
    }

    // ---- Ballots -----------------------------------------------------------------------------------

    private val sb = SubmissionRef("sb", "b", SubmissionStatus.ACTIVE)

    private fun ballot(
        voter: String = "a",
        members: Set<String> = roster,
        state: ChallengeState = ChallengeState.VOTING,
        now: Long = deadline - 1,
        target: SubmissionRef? = sb,
        blocks: BlockGraph = BlockGraph.EMPTY,
        existing: String? = null,
    ) = Eligibility.checkBallot(
        BallotAttempt(
            voterUserId = voter,
            roster = roster,
            currentMembers = members,
            state = state,
            votingDeadlineEpochMillis = deadline,
            nowEpochMillis = now,
            target = target,
            blocks = blocks,
            existingBallotSubmissionId = existing,
        ),
    )

    @Test
    fun validBallotIsRecorded() {
        assertEquals(RuleResult.Ok(BallotDecision.RECORD_NEW), ballot())
    }

    @Test
    fun ballotBoundaryAroundTheVotingDeadline() {
        assertEquals(RuleResult.Ok(BallotDecision.RECORD_NEW), ballot(now = deadline - 1))
        assertEquals(BallotRefusal.DeadlinePassed(deadline, deadline), ballot(now = deadline).refusalOrNull())
        assertEquals(BallotRefusal.DeadlinePassed(deadline, deadline + 1), ballot(now = deadline + 1).refusalOrNull())
        assertEquals(ErrorCode.DEADLINE_PASSED, ballot(now = deadline).refusalOrNull()!!.code)
    }

    @Test
    fun lateBallotIsDeadlinePassedWhetherOrNotTheStateHasAdvanced() {
        for (state in listOf(ChallengeState.COMPLETED, ChallengeState.ARCHIVED)) {
            assertEquals(ErrorCode.DEADLINE_PASSED, ballot(state = state, now = deadline + 1).refusalOrNull()!!.code)
        }
    }

    @Test
    fun ballotOutsideVotingIsInvalidTransition() {
        for (state in listOf(ChallengeState.CREATED, ChallengeState.OPEN_FOR_SUBMISSIONS, ChallengeState.CANCELLED)) {
            val reason = ballot(state = state).refusalOrNull()
            assertEquals(BallotRefusal.WrongState(state), reason)
            assertEquals(ErrorCode.INVALID_TRANSITION, reason!!.code)
        }
    }

    @Test
    fun voterMustBeOnTheRosterAndStillAMember() {
        assertEquals(BallotRefusal.NotOnRoster, ballot(voter = "x", members = roster + "x").refusalOrNull())
        assertEquals(BallotRefusal.NoLongerMember, ballot(members = setOf("b", "c")).refusalOrNull())
        assertEquals(ErrorCode.FORBIDDEN, ballot(voter = "x").refusalOrNull()!!.code)
        assertEquals(ErrorCode.FORBIDDEN, ballot(members = setOf("b", "c")).refusalOrNull()!!.code)
    }

    @Test
    fun selfVoteIsRefused() {
        val reason = ballot(voter = "b").refusalOrNull()
        assertEquals(BallotRefusal.SelfVote, reason)
        assertEquals(ErrorCode.INVALID_BALLOT, reason!!.code)
    }

    @Test
    fun targetMustBeAnActiveSubmissionOfThisChallengeByARosterMember() {
        assertEquals(BallotRefusal.TargetNotInChallenge, ballot(target = null).refusalOrNull())
        for (status in listOf(SubmissionStatus.WITHDRAWN, SubmissionStatus.REMOVED, SubmissionStatus.HIDDEN)) {
            val reason = ballot(target = sb.copy(status = status)).refusalOrNull()
            assertEquals(BallotRefusal.TargetNotActive(status), reason)
            assertEquals(ErrorCode.INVALID_BALLOT, reason!!.code)
        }
        assertEquals(ErrorCode.INVALID_BALLOT, ballot(target = sb.copy(authorUserId = null)).refusalOrNull()!!.code)
        assertEquals(ErrorCode.INVALID_BALLOT, ballot(target = sb.copy(authorUserId = "x")).refusalOrNull()!!.code)
    }

    @Test
    fun blockInEitherDirectionRefusesTheBallot() {
        assertEquals(BallotRefusal.BlockedPair, ballot(blocks = BlockGraph.of("a" to "b")).refusalOrNull())
        assertEquals(BallotRefusal.BlockedPair, ballot(blocks = BlockGraph.of("b" to "a")).refusalOrNull())
        assertEquals(RuleResult.Ok(BallotDecision.RECORD_NEW), ballot(blocks = BlockGraph.of("a" to "c")))
    }

    @Test
    fun duplicateBallotForTheSameTargetIsIdempotentAndADifferentTargetIsAlreadyVoted() {
        assertEquals(RuleResult.Ok(BallotDecision.ALREADY_RECORDED), ballot(existing = "sb"))
        val reason = ballot(existing = "sc").refusalOrNull()
        assertEquals(BallotRefusal.AlreadyVoted("sc"), reason)
        assertEquals(ErrorCode.ALREADY_VOTED, reason!!.code)
    }

    @Test
    fun checksRunInContractOrder() {
        // Roster before state, state before deadline, deadline before target, target before duplicate.
        assertEquals(BallotRefusal.NotOnRoster, ballot(voter = "x", state = ChallengeState.CREATED).refusalOrNull())
        assertEquals(BallotRefusal.WrongState(ChallengeState.OPEN_FOR_SUBMISSIONS), ballot(state = ChallengeState.OPEN_FOR_SUBMISSIONS, now = deadline + 1).refusalOrNull())
        assertEquals(ErrorCode.DEADLINE_PASSED, ballot(now = deadline, target = null).refusalOrNull()!!.code)
        assertEquals(BallotRefusal.SelfVote, ballot(voter = "b", existing = "sc").refusalOrNull())
    }

    // ---- Visibility --------------------------------------------------------------------------------

    private val all = listOf(
        SubmissionRef("sa", "a", SubmissionStatus.ACTIVE),
        SubmissionRef("sb", "b", SubmissionStatus.ACTIVE),
        SubmissionRef("sc", "c", SubmissionStatus.HIDDEN),
        SubmissionRef("sd", "d", SubmissionStatus.ACTIVE),
    )

    private fun visible(state: ChallengeState, viewer: String = "a", blocks: BlockGraph = BlockGraph.EMPTY) =
        Eligibility.visibleSubmissions(viewer, state, all, blocks).map { it.id }

    @Test
    fun visibilityFollowsTheChallengeState() {
        assertEquals(emptyList<String>(), visible(ChallengeState.CREATED))
        assertEquals(emptyList<String>(), visible(ChallengeState.CANCELLED))
        assertEquals(listOf("sa"), visible(ChallengeState.OPEN_FOR_SUBMISSIONS))
        for (state in listOf(ChallengeState.VOTING, ChallengeState.COMPLETED, ChallengeState.ARCHIVED)) {
            assertEquals(listOf("sa", "sb", "sd"), visible(state))
        }
    }

    @Test
    fun blockedAuthorsAreHiddenInBothDirectionsButOwnPhotoAlwaysShows() {
        assertEquals(listOf("sa", "sd"), visible(ChallengeState.VOTING, blocks = BlockGraph.of("a" to "b")))
        assertEquals(listOf("sa", "sd"), visible(ChallengeState.VOTING, blocks = BlockGraph.of("b" to "a")))
        assertEquals(listOf("sc"), visible(ChallengeState.OPEN_FOR_SUBMISSIONS, viewer = "c"))
        assertEquals(listOf("sa", "sb", "sc", "sd"), visible(ChallengeState.VOTING, viewer = "c"))
    }
}
