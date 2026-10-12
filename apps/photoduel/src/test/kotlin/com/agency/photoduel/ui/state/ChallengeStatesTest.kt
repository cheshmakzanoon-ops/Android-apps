package com.agency.photoduel.ui.state

import com.agency.photoduel.domain.model.Challenge
import com.agency.photoduel.domain.model.ChallengeSpec
import com.agency.photoduel.domain.model.ChallengeState
import com.agency.photoduel.domain.model.ChallengeView
import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.MyStatus
import com.agency.photoduel.domain.model.Prompt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChallengePhaseTest {

    private fun view(state: ChallengeState, status: MyStatus) = ChallengeView(
        challenge = Challenge("c", "r", 1, Prompt("p", "t", "b"), state, null, null, null, 0L),
        roster = emptyList(),
        submissions = emptyList(),
        myStatus = status,
        result = null,
    )

    private val nothing = MyStatus(isRosterMember = true, mySubmissionId = null, myBallotSubmissionId = null, canSubmit = false, canVote = false)

    @Test
    fun everyStateMapsToWhatTheViewerDoesNext() {
        assertEquals(ChallengePhase.WAITING_TO_START, ChallengeData.phaseOf(view(ChallengeState.CREATED, nothing)))
        assertEquals(ChallengePhase.SUBMIT_PHOTO, ChallengeData.phaseOf(view(ChallengeState.OPEN_FOR_SUBMISSIONS, nothing.copy(canSubmit = true))))
        assertEquals(ChallengePhase.PHOTO_SUBMITTED, ChallengeData.phaseOf(view(ChallengeState.OPEN_FOR_SUBMISSIONS, nothing.copy(mySubmissionId = "s", canSubmit = true))))
        assertEquals(ChallengePhase.WATCHING, ChallengeData.phaseOf(view(ChallengeState.OPEN_FOR_SUBMISSIONS, nothing.copy(isRosterMember = false))))
        assertEquals(ChallengePhase.VOTE, ChallengeData.phaseOf(view(ChallengeState.VOTING, nothing.copy(canVote = true))))
        assertEquals(ChallengePhase.VOTE_CAST, ChallengeData.phaseOf(view(ChallengeState.VOTING, nothing.copy(myBallotSubmissionId = "s"))))
        assertEquals(ChallengePhase.WATCHING, ChallengeData.phaseOf(view(ChallengeState.VOTING, nothing)))
        assertEquals(ChallengePhase.RESULTS, ChallengeData.phaseOf(view(ChallengeState.COMPLETED, nothing)))
        assertEquals(ChallengePhase.ARCHIVED, ChallengeData.phaseOf(view(ChallengeState.ARCHIVED, nothing)))
        assertEquals(ChallengePhase.CANCELLED, ChallengeData.phaseOf(view(ChallengeState.CANCELLED, nothing)))
    }
}

class ChallengeDetailStateTest {
    private val harness = TestHarness()
    private val network = NetworkStatus()

    @After
    fun tearDown() = harness.close()

    @Test
    fun theOwnerStartsAWaitingChallenge() {
        val friends = Friends.create(count = 3)
        val created = runBlocking { friends.owner.createChallenge(friends.roomId, ChallengeSpec("p-002", 6, 1)).value() }
        val state = ChallengeDetailState(harness.scope, friends.owner, network, friends.clock::nowEpochMillis, created.id)
        harness.settle()
        val before = state.data.value.valueOrNull!!
        assertTrue(before.isOwner)
        assertEquals(ChallengePhase.WAITING_TO_START, before.phase)
        assertNull(before.deadline)

        state.start()
        harness.settle()
        val after = state.data.value.valueOrNull!!
        assertEquals(ChallengePhase.SUBMIT_PHOTO, after.phase)
        assertEquals(friends.clock.nowEpochMillis() + 6L * 3_600_000L, after.deadline)
    }

    @Test
    fun aMemberIsRefusedWhenTryingToStart() {
        val friends = Friends.create(count = 3)
        val created = runBlocking { friends.owner.createChallenge(friends.roomId, ChallengeSpec("p-002", 6, 1)).value() }
        val state = ChallengeDetailState(harness.scope, friends.clients[1], network, friends.clock::nowEpochMillis, created.id)
        harness.settle()
        assertFalse(state.data.value.valueOrNull!!.isOwner)
        state.start()
        harness.settle()
        assertEquals(ErrorCode.FORBIDDEN, state.ui.value.error?.code)
    }

    @Test
    fun cancelAsksFirstThenCancels() {
        val friends = Friends.create(count = 3)
        val id = friends.startChallenge()
        val state = ChallengeDetailState(harness.scope, friends.owner, network, friends.clock::nowEpochMillis, id)
        harness.settle()
        state.ask(ChallengeConfirm.CANCEL_CHALLENGE)
        assertEquals(ChallengeConfirm.CANCEL_CHALLENGE, state.ui.value.confirm)
        state.cancel()
        harness.settle()
        assertNull(state.ui.value.confirm)
        assertEquals(ChallengePhase.CANCELLED, state.data.value.valueOrNull!!.phase)
    }

    @Test
    fun theViewersOwnSubmissionIsFound() {
        val friends = Friends.create(count = 3)
        val id = friends.startChallenge()
        val (mine) = friends.submit(id, 1)
        val state = ChallengeDetailState(harness.scope, friends.clients[1], network, friends.clock::nowEpochMillis, id)
        harness.settle()
        val data = state.data.value.valueOrNull!!
        assertEquals(ChallengePhase.PHOTO_SUBMITTED, data.phase)
        assertEquals(mine, data.mySubmission?.id)
    }

    @Test
    fun theCountdownAnchorUsesTheResponseServerTime() {
        val friends = Friends.create(count = 3)
        val id = friends.startChallenge()
        var displayClock = 5L
        val state = ChallengeDetailState(harness.scope, friends.owner, network, { displayClock }, id)
        harness.settle()
        val anchor = state.data.value.valueOrNull!!.anchor
        assertEquals(friends.clock.nowEpochMillis(), anchor.serverTimeEpochMillis)
        assertEquals(5L, anchor.clockAtReceiptMillis)
        displayClock = 1_005L
        assertEquals(friends.clock.nowEpochMillis() + 1_000L, anchor.serverNow(displayClock))
    }

    @Test
    fun reachingTheDeadlineAsksTheServerUntilTheStateMoves() {
        val friends = Friends.create(count = 3)
        val id = friends.startChallenge()
        friends.submit(id, 0, 1)
        val state = ChallengeDetailState(
            harness.scope, friends.clients[2], network, friends.clock::nowEpochMillis, id,
            pollDelaysMillis = listOf(1_000, 1_000, 1_000),
        )
        harness.settle()
        assertEquals(ChallengePhase.SUBMIT_PHOTO, state.data.value.valueOrNull!!.phase)
        // The display reached zero, but the server clock has not passed the deadline yet.
        state.onDeadlineReached()
        harness.advance(1_000)
        assertEquals(ChallengeState.OPEN_FOR_SUBMISSIONS, state.data.value.valueOrNull!!.challenge.state)
        // Now the server moves on; the next poll picks it up and polling stops.
        friends.closeSubmissions()
        harness.advance(1_500)
        assertEquals(ChallengeState.VOTING, state.data.value.valueOrNull!!.challenge.state)
        assertEquals(ChallengePhase.VOTE, state.data.value.valueOrNull!!.phase)
    }
}

class CreateChallengeStateTest {
    private val harness = TestHarness()
    private val network = NetworkStatus()

    @After
    fun tearDown() = harness.close()

    @Test
    fun theFirstPromptAndDefaultWindowsArePreselected() {
        val friends = Friends.create(count = 1)
        val state = CreateChallengeState(harness.scope, friends.owner, network, friends.roomId)
        assertFalse(state.ui.value.canSubmit)
        harness.settle()
        assertEquals("p-001", state.ui.value.promptId)
        assertEquals(ChallengeDurations.DEFAULT_SUBMISSION_HOURS, state.ui.value.submissionHours)
        assertEquals(ChallengeDurations.DEFAULT_VOTING_HOURS, state.ui.value.votingHours)
        assertTrue(state.ui.value.canSubmit)
        assertEquals(5, state.prompts.value.valueOrNull!!.prompts.size)
    }

    @Test
    fun aRematchDoesNotPreselectThePreviousPrompt() {
        val friends = Friends.create(count = 1)
        val state = CreateChallengeState(harness.scope, friends.owner, network, friends.roomId, previousPromptId = "p-001")
        harness.settle()
        assertEquals("p-002", state.ui.value.promptId)
    }

    @Test
    fun onlyAllowedWindowsAreAccepted() {
        val friends = Friends.create(count = 1)
        val state = CreateChallengeState(harness.scope, friends.owner, network, friends.roomId)
        state.setSubmissionHours(5)
        state.setVotingHours(48)
        assertEquals(ChallengeDurations.DEFAULT_SUBMISSION_HOURS, state.ui.value.submissionHours)
        assertEquals(ChallengeDurations.DEFAULT_VOTING_HOURS, state.ui.value.votingHours)
        state.setSubmissionHours(72)
        state.setVotingHours(1)
        assertEquals(72, state.ui.value.submissionHours)
        assertEquals(1, state.ui.value.votingHours)
    }

    @Test
    fun submittingCreatesTheChallengeWithTheChosenValues() {
        val friends = Friends.create(count = 1)
        val state = CreateChallengeState(harness.scope, friends.owner, network, friends.roomId)
        harness.settle()
        state.selectPrompt("p-004")
        state.setSubmissionHours(48)
        state.setVotingHours(24)
        state.submit()
        assertTrue(state.ui.value.submitting)
        harness.settle()
        val created = state.ui.value.created!!
        assertEquals("p-004", created.prompt.id)
        assertEquals(ChallengeState.CREATED, created.state)
        state.consumeCreated()
        assertNull(state.ui.value.created)
    }

    @Test
    fun aSecondActiveChallengeIsRefused() {
        val friends = Friends.create(count = 1)
        runBlocking { friends.owner.createChallenge(friends.roomId, ChallengeSpec("p-001", 1, 1)).value() }
        val state = CreateChallengeState(harness.scope, friends.owner, network, friends.roomId)
        harness.settle()
        state.submit()
        harness.settle()
        assertEquals(ErrorCode.CONFLICT, state.ui.value.error?.code)
        assertNull(state.ui.value.created)
        state.selectPrompt("p-003")
        assertNull(state.ui.value.error)
    }

    @Test
    fun aMemberCannotCreate() {
        val friends = Friends.create(count = 2)
        val state = CreateChallengeState(harness.scope, friends.clients[1], network, friends.roomId)
        harness.settle()
        state.submit()
        harness.settle()
        assertEquals(ErrorCode.FORBIDDEN, state.ui.value.error?.code)
        assertNotNull(state.ui.value.promptId)
        assertNotEquals(null, state.prompts.value.valueOrNull)
    }
}
