package com.agency.photoduel.ui.state

import com.agency.photoduel.domain.model.Challenge
import com.agency.photoduel.domain.model.ChallengeOutcome
import com.agency.photoduel.domain.model.ChallengeResult
import com.agency.photoduel.domain.model.ChallengeState
import com.agency.photoduel.domain.model.ChallengeView
import com.agency.photoduel.domain.model.MyStatus
import com.agency.photoduel.domain.model.Prompt
import com.agency.photoduel.domain.model.Submission
import com.agency.photoduel.domain.model.SubmissionStatus
import com.agency.photoduel.domain.model.Tally
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultsModelBuilderTest {

    private fun submission(id: String, author: String = id.uppercase()) =
        Submission(id, "c", "u-$id", author, SubmissionStatus.ACTIVE, "t-$id", "f-$id", 1600, 1200, isMine = false)

    private fun view(result: ChallengeResult?, submissions: List<Submission>) = ChallengeView(
        challenge = Challenge("c", "r", 1, Prompt("p", "t", "b"), ChallengeState.COMPLETED, result?.outcome, 1L, 2L, 3L),
        roster = emptyList(),
        submissions = submissions,
        myStatus = MyStatus(true, null, null, canSubmit = false, canVote = false),
        result = result,
    )

    @Test
    fun noPublishedResultGivesNoModel() {
        assertNull(ResultsModelBuilder.build(view(null, listOf(submission("a")))))
    }

    @Test
    fun aWinnerIsMarkedAndBarsAreScaledToTheMaximum() {
        val result = ChallengeResult(
            outcome = ChallengeOutcome.WINNER,
            coWinnerSubmissionIds = listOf("b"),
            tallies = listOf(Tally("a", 1), Tally("b", 2), Tally("c", 0)),
            ballotCount = 3,
            eligibleVoterCount = 3,
        )
        val model = ResultsModelBuilder.build(view(result, listOf(submission("a"), submission("b"), submission("c"))))!!
        assertEquals(listOf("b", "a", "c"), model.rows.map { it.submissionId })
        assertEquals(listOf(1f, 0.5f, 0f), model.rows.map { it.fraction })
        assertEquals(listOf("b"), model.winners.map { it.submissionId })
        assertEquals("B", model.winners.single().submission?.authorDisplayName)
        assertTrue(model.hasWinner)
        assertFalse(model.isTie)
        assertEquals(2, model.maxVotes)
        assertEquals(3, model.ballotCount)
    }

    @Test
    fun aTieHasEveryCoWinnerAndKeepsTheServerOrder() {
        val result = ChallengeResult(
            outcome = ChallengeOutcome.TIE,
            coWinnerSubmissionIds = listOf("a", "c"),
            tallies = listOf(Tally("a", 2), Tally("b", 1), Tally("c", 2)),
            ballotCount = 5,
            eligibleVoterCount = 6,
        )
        val model = ResultsModelBuilder.build(view(result, listOf(submission("a"), submission("b"), submission("c"))))!!
        assertTrue(model.isTie)
        assertEquals(listOf("a", "c"), model.winners.map { it.submissionId })
        assertEquals(listOf("a", "c", "b"), model.rows.map { it.submissionId })
        assertEquals(6, model.eligibleVoterCount)
    }

    @Test
    fun insufficientOutcomesHaveNoWinnerEvenIfIdsAreListed() {
        val result = ChallengeResult(
            outcome = ChallengeOutcome.INSUFFICIENT_BALLOTS,
            coWinnerSubmissionIds = listOf("a"),
            tallies = listOf(Tally("a", 1), Tally("b", 0)),
            ballotCount = 1,
            eligibleVoterCount = 3,
        )
        val model = ResultsModelBuilder.build(view(result, listOf(submission("a"), submission("b"))))!!
        assertFalse(model.hasWinner)
        assertTrue(model.winners.isEmpty())
        assertTrue(model.rows.none { it.isWinner })
        assertEquals(2, model.rows.size)
    }

    @Test
    fun noVotesMeansEmptyBars() {
        val result = ChallengeResult(ChallengeOutcome.INSUFFICIENT_SUBMISSIONS, emptyList(), emptyList(), 0, 3)
        val model = ResultsModelBuilder.build(view(result, emptyList()))!!
        assertEquals(0, model.maxVotes)
        assertTrue(model.rows.isEmpty())

        val zero = ChallengeResult(ChallengeOutcome.INSUFFICIENT_BALLOTS, emptyList(), listOf(Tally("a", 0)), 0, 3)
        assertEquals(0f, ResultsModelBuilder.build(view(zero, listOf(submission("a"))))!!.rows.single().fraction)
    }

    @Test
    fun aRemovedPhotoReturnedToItsAuthorIsNotShown() {
        val removed = submission("a").copy(status = SubmissionStatus.REMOVED, isMine = true)
        val result = ChallengeResult(ChallengeOutcome.WINNER, listOf("a"), listOf(Tally("a", 2), Tally("b", 1)), 3, 3)
        val model = ResultsModelBuilder.build(view(result, listOf(removed, submission("b"))))!!
        assertNull(model.rows.first { it.submissionId == "a" }.submission)
        assertNotNull(model.rows.first { it.submissionId == "b" }.submission)
    }

    @Test
    fun aPhotoThatIsGoneStillHasItsRow() {
        val result = ChallengeResult(ChallengeOutcome.WINNER, listOf("gone"), listOf(Tally("gone", 2), Tally("a", 1)), 3, 3)
        val model = ResultsModelBuilder.build(view(result, listOf(submission("a"))))!!
        assertNull(model.rows.first().submission)
        assertEquals("gone", model.winners.single().submissionId)
    }
}

class ResultsStateTest {
    private val harness = TestHarness()
    private val network = NetworkStatus()

    @After
    fun tearDown() = harness.close()

    /** Three photos, everyone votes for the next person: one vote each, a three-way tie. */
    private fun threeWayTie(): Pair<Friends, String> {
        val friends = Friends.create(count = 3)
        val id = friends.startChallenge()
        val s = friends.submit(id, 0, 1, 2)
        friends.closeSubmissions()
        friends.vote(id, 0, s[1])
        friends.vote(id, 1, s[2])
        friends.vote(id, 2, s[0])
        friends.closeVoting()
        return friends to id
    }

    @Test
    fun aTieIsLoadedWithAllCoWinners() {
        val (friends, id) = threeWayTie()
        val state = ResultsState(harness.scope, friends.owner, network, id, reduceMotion = false)
        harness.settle()
        val model = state.data.value.valueOrNull!!.model!!
        assertEquals(ChallengeOutcome.TIE, model.outcome)
        assertEquals(3, model.winners.size)
        assertEquals(setOf("Ada", "Ben", "Cy"), model.winners.map { it.submission?.authorDisplayName }.toSet())
        assertEquals(RevealStage.TALLY, state.ui.value.stage)
        state.showWinner()
        assertEquals(RevealStage.WINNER, state.ui.value.stage)
        state.showTally()
        assertEquals(RevealStage.TALLY, state.ui.value.stage)
    }

    @Test
    fun tooFewPhotosEndsWithoutAWinner() {
        val friends = Friends.create(count = 3)
        val id = friends.startChallenge()
        friends.submit(id, 1)
        friends.closeSubmissions()
        val state = ResultsState(harness.scope, friends.owner, network, id, reduceMotion = true)
        harness.settle()
        val model = state.data.value.valueOrNull!!.model!!
        assertEquals(ChallengeOutcome.INSUFFICIENT_SUBMISSIONS, model.outcome)
        assertFalse(model.hasWinner)
        assertTrue(state.reduceMotion)
    }

    @Test
    fun aRunningChallengeHasNoResultYet() {
        val friends = Friends.create(count = 3)
        val id = friends.startChallenge()
        val state = ResultsState(harness.scope, friends.owner, network, id, reduceMotion = false)
        harness.settle()
        val data = state.data.value.valueOrNull!!
        assertNull(data.model)
        assertEquals(ChallengeState.OPEN_FOR_SUBMISSIONS, data.view.challenge.state)
    }

    @Test
    fun deletingTheOwnPhotoKeepsTheTally() {
        val (friends, id) = threeWayTie()
        val state = ResultsState(harness.scope, friends.clients[1], network, id, reduceMotion = false)
        harness.settle()
        val mine = state.data.value.valueOrNull!!.view.submissions.first { it.isMine }
        state.askDeleteMyPhoto()
        assertTrue(state.ui.value.confirmDelete)
        state.deleteMyPhoto(mine.id)
        assertFalse(state.ui.value.confirmDelete)
        harness.settle()
        assertTrue(state.ui.value.photoDeleted)
        val after = state.data.value.valueOrNull!!
        assertEquals(3, after.model!!.rows.size)
        assertNull(after.model!!.rows.first { it.submissionId == mine.id }.submission)
        assertNotNull(after.model!!.rows.first { it.submissionId != mine.id }.submission)
    }

    @Test
    fun deletingDuringTheChallengeIsRefused() {
        val friends = Friends.create(count = 3)
        val id = friends.startChallenge()
        val (mine) = friends.submit(id, 1)
        val state = ResultsState(harness.scope, friends.clients[1], network, id, reduceMotion = false)
        harness.settle()
        state.deleteMyPhoto(mine)
        harness.settle()
        assertNotNull(state.ui.value.error)
        assertFalse(state.ui.value.photoDeleted)
        state.dismissError()
        assertNull(state.ui.value.error)
    }
}
