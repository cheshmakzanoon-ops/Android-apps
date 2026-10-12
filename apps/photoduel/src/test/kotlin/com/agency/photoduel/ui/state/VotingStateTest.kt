package com.agency.photoduel.ui.state

import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.InvitationTtl
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VotingStateTest {
    private val harness = TestHarness()
    private val network = NetworkStatus()
    private val friends = Friends.create(count = 3)
    private val challengeId = friends.startChallenge()
    private val submissions = friends.submit(challengeId, 0, 1, 2)
    private val ada = 0
    private val events = mutableListOf<VotingEvent>()

    init {
        friends.closeSubmissions()
    }

    @After
    fun tearDown() = harness.close()

    private fun state(voter: Int = ada): VotingState {
        val state = VotingState(harness.scope, friends.clients[voter], network, friends.clock::nowEpochMillis, challengeId)
        harness.scope.launch { state.events.collect { events += it } }
        harness.settle()
        return state
    }

    @Test
    fun votingStartsWithTheIntroAndShowsEveryActivePhoto() {
        val state = state()
        assertEquals(VotingStage.INTRO, state.ui.value.stage)
        val data = state.data.value.valueOrNull!!
        assertEquals(submissions.toSet(), data.candidates.map { it.id }.toSet())
        assertEquals(1, data.candidates.count { it.isMine })
        state.startVoting()
        assertEquals(VotingStage.BALLOT, state.ui.value.stage)
    }

    @Test
    fun selectingBeforeTheBallotIsShownIsRejected() {
        val state = state()
        assertFalse(state.select(submissions[1]))
        assertNull(state.ui.value.selectedId)
        harness.settle()
        assertEquals(listOf(VotingEvent.REJECTED), events)
    }

    @Test
    fun theOwnPhotoCannotBeSelected() {
        val state = state()
        state.startVoting()
        assertFalse(state.select(submissions[0]))
        assertNull(state.ui.value.selectedId)
        assertFalse(state.select("unknown"))
        harness.settle()
        assertEquals(listOf(VotingEvent.REJECTED, VotingEvent.REJECTED), events)
    }

    @Test
    fun aSelectionCanChangeFreelyAndConfirmCastsIt() {
        val state = state()
        state.startVoting()
        assertTrue(state.select(submissions[1]))
        assertTrue(state.select(submissions[2]))
        assertEquals(submissions[2], state.ui.value.selectedId)
        state.confirm()
        assertTrue(state.ui.value.casting)
        harness.settle()
        assertEquals(VotingStage.COMPLETED, state.ui.value.stage)
        assertEquals(submissions[2], state.ui.value.castSubmissionId)
        assertEquals(listOf(VotingEvent.SELECTED, VotingEvent.SELECTED, VotingEvent.CAST), events)
        val view = runBlocking { friends.owner.challenge(challengeId).value() }
        assertEquals(submissions[2], view.myStatus.myBallotSubmissionId)
    }

    @Test
    fun confirmWithoutASelectionDoesNothing() {
        val state = state()
        state.startVoting()
        state.confirm()
        harness.settle()
        assertEquals(VotingStage.BALLOT, state.ui.value.stage)
        assertFalse(state.ui.value.casting)
    }

    @Test
    fun anEarlierBallotFromAnotherDeviceIsShownAsDone() {
        friends.vote(challengeId, voter = 0, submissionId = submissions[1])
        val state = state()
        assertEquals(VotingStage.COMPLETED, state.ui.value.stage)
        assertEquals(submissions[1], state.ui.value.castSubmissionId)
    }

    @Test
    fun aBallotRaceIsReportedAndTheScreenCatchesUp() {
        val state = state()
        state.startVoting()
        state.select(submissions[2])
        // The same account voted for another photo on a second device in the meantime.
        friends.vote(challengeId, voter = 0, submissionId = submissions[1])
        state.confirm()
        harness.settle()
        assertEquals(ErrorCode.ALREADY_VOTED, state.ui.value.error?.code)
        assertEquals(VotingStage.COMPLETED, state.ui.value.stage)
        assertEquals(submissions[1], state.ui.value.castSubmissionId)
        assertTrue(VotingEvent.REJECTED in events)
        state.clearError()
        assertNull(state.ui.value.error)
    }

    @Test
    fun someoneWhoJoinedAfterTheStartCannotVote() {
        val token = runBlocking { friends.owner.createInvitation(friends.roomId, InvitationTtl.ONE_DAY).value().token }
        val late = friends.owner.newClient()
        runBlocking {
            late.createGuest("Late").value()
            late.joinWithInvitation(token).value()
        }
        val state = VotingState(harness.scope, late, network, friends.clock::nowEpochMillis, challengeId)
        harness.settle()
        assertEquals(VotingStage.UNAVAILABLE, state.ui.value.stage)
    }

    @Test
    fun aClosedWindowIsReportedWhenConfirming() {
        val state = state()
        state.startVoting()
        state.select(submissions[1])
        friends.closeVoting()
        state.confirm()
        harness.settle()
        val code = state.ui.value.error?.code
        assertTrue("$code", code == ErrorCode.DEADLINE_PASSED || code == ErrorCode.INVALID_TRANSITION)
        // After the refresh the viewer can no longer vote here.
        assertEquals(VotingStage.UNAVAILABLE, state.ui.value.stage)
    }
}
