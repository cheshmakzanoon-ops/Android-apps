package com.agency.photoduel.domain.rules

import com.agency.photoduel.domain.model.ChallengeOutcome
import com.agency.photoduel.domain.model.SubmissionStatus
import com.agency.photoduel.domain.model.Tally
import org.junit.Assert.assertEquals
import org.junit.Test

/** Cases beyond the golden vectors: readings documented on [ResultCalculator]. */
class ResultCalculatorTest {

    private val roster = setOf("a", "b", "c", "d")
    private val photos = listOf(
        SubmissionRef("sa", "a", SubmissionStatus.ACTIVE),
        SubmissionRef("sb", "b", SubmissionStatus.ACTIVE),
        SubmissionRef("sc", "c", SubmissionStatus.ACTIVE),
        SubmissionRef("sd", "d", SubmissionStatus.ACTIVE),
    )

    private fun input(
        ballots: List<BallotRef>,
        submissions: List<SubmissionRef> = photos,
        activeMembers: Set<String> = roster,
        blocks: BlockGraph = BlockGraph.EMPTY,
    ) = TallyInput(roster, activeMembers, submissions, ballots, blocks)

    @Test
    fun voidFirstBallotIsNotReplacedByALaterBallotOfTheSameVoter() {
        val result = ResultCalculator.calculate(
            input(
                listOf(
                    BallotRef("a", "sa"), // self vote: void, and it is a's only ballot
                    BallotRef("a", "sb"), // ignored: not a's first ballot
                    BallotRef("c", "sb"),
                    BallotRef("d", "sc"),
                ),
            ),
        )
        assertEquals(2, result.ballotCount)
        assertEquals(ChallengeOutcome.TIE, result.outcome)
        assertEquals(listOf("sb", "sc"), result.coWinnerSubmissionIds)
    }

    @Test
    fun ballotsOfVotersWhoAreNoLongerMembersAreVoid() {
        val result = ResultCalculator.calculate(
            input(
                ballots = listOf(BallotRef("a", "sb"), BallotRef("c", "sb"), BallotRef("d", "sc")),
                activeMembers = setOf("a", "b", "d"),
            ),
        )
        assertEquals(3, result.eligibleVoterCount)
        assertEquals(2, result.ballotCount)
        assertEquals(ChallengeOutcome.TIE, result.outcome)
    }

    @Test
    fun ballotsWithNullVoterAreVoidAndDoNotConsumeAFirstBallotSlot() {
        val result = ResultCalculator.calculate(
            input(listOf(BallotRef(null, "sa"), BallotRef(null, "sa"), BallotRef("b", "sa"), BallotRef("c", "sa"))),
        )
        assertEquals(ChallengeOutcome.WINNER, result.outcome)
        assertEquals(listOf("sa"), result.coWinnerSubmissionIds)
        assertEquals(2, result.ballotCount)
    }

    @Test
    fun nonRosterVotersAndNonRosterAuthorsDoNotCount() {
        val submissions = photos + SubmissionRef("sx", "x", SubmissionStatus.ACTIVE)
        val result = ResultCalculator.calculate(
            input(
                ballots = listOf(BallotRef("x", "sa"), BallotRef("a", "sx"), BallotRef("b", "sa"), BallotRef("c", "sa")),
                submissions = submissions,
                activeMembers = roster + "x",
            ),
        )
        assertEquals(4, result.eligibleVoterCount)
        assertEquals(listOf("sa", "sb", "sc", "sd"), result.tallies.map { it.submissionId })
        assertEquals(2, result.ballotCount)
        assertEquals(listOf("sa"), result.coWinnerSubmissionIds)
    }

    @Test
    fun blockInEitherDirectionVoidsTheBallot() {
        val ballots = listOf(BallotRef("a", "sb"), BallotRef("c", "sd"), BallotRef("d", "sc"))
        val forward = ResultCalculator.calculate(input(ballots, blocks = BlockGraph.of("a" to "b")))
        val backward = ResultCalculator.calculate(input(ballots, blocks = BlockGraph.of("b" to "a")))
        assertEquals(forward, backward)
        assertEquals(2, forward.ballotCount)
        assertEquals(0, forward.tallies.first { it.submissionId == "sb" }.votes)
    }

    @Test
    fun hiddenAndWithdrawnSubmissionsAreNotInPAndTheirBallotsAreVoid() {
        val submissions = listOf(
            SubmissionRef("sa", "a", SubmissionStatus.ACTIVE),
            SubmissionRef("sb", "b", SubmissionStatus.HIDDEN),
            SubmissionRef("sc", "c", SubmissionStatus.ACTIVE),
            SubmissionRef("sd", "d", SubmissionStatus.WITHDRAWN),
        )
        val result = ResultCalculator.calculate(
            input(listOf(BallotRef("a", "sb"), BallotRef("b", "sa"), BallotRef("d", "sa")), submissions = submissions),
        )
        assertEquals(listOf(Tally("sa", 2), Tally("sc", 0)), result.tallies)
        assertEquals(ChallengeOutcome.WINNER, result.outcome)
    }

    @Test
    fun threeWayTieListsEveryLeaderSorted() {
        val submissions = listOf(
            SubmissionRef("s3", "c", SubmissionStatus.ACTIVE),
            SubmissionRef("s1", "a", SubmissionStatus.ACTIVE),
            SubmissionRef("s2", "b", SubmissionStatus.ACTIVE),
        )
        val result = ResultCalculator.calculate(
            TallyInput(
                roster = setOf("a", "b", "c"),
                activeMembers = setOf("a", "b", "c"),
                submissions = submissions,
                ballots = listOf(BallotRef("a", "s2"), BallotRef("b", "s3"), BallotRef("c", "s1")),
            ),
        )
        assertEquals(ChallengeOutcome.TIE, result.outcome)
        assertEquals(listOf("s1", "s2", "s3"), result.coWinnerSubmissionIds)
        assertEquals(listOf("s1", "s2", "s3"), result.tallies.map { it.submissionId })
    }

    @Test
    fun noSubmissionsIsInsufficientSubmissionsWithEmptyTallies() {
        val result = ResultCalculator.calculate(input(emptyList(), submissions = emptyList()))
        assertEquals(ChallengeOutcome.INSUFFICIENT_SUBMISSIONS, result.outcome)
        assertEquals(emptyList<Tally>(), result.tallies)
        assertEquals(0, result.ballotCount)
        assertEquals(4, result.eligibleVoterCount)
    }

    @Test
    fun insufficientSubmissionsIgnoresBallotsEntirely() {
        val submissions = listOf(SubmissionRef("sa", "a", SubmissionStatus.ACTIVE))
        val result = ResultCalculator.calculate(
            input(listOf(BallotRef("b", "sa"), BallotRef("c", "sa")), submissions = submissions),
        )
        assertEquals(ChallengeOutcome.INSUFFICIENT_SUBMISSIONS, result.outcome)
        assertEquals(0, result.ballotCount)
        assertEquals(emptyList<String>(), result.coWinnerSubmissionIds)
    }

    /** Proposed correction of the fixture case tie_two_way_is_co_winners (no self-vote). */
    @Test
    fun twoWayTieWithoutSelfVotes() {
        val result = ResultCalculator.calculate(
            input(listOf(BallotRef("a", "sc"), BallotRef("b", "sd"), BallotRef("c", "sd"), BallotRef("d", "sc"))),
        )
        assertEquals(ChallengeOutcome.TIE, result.outcome)
        assertEquals(listOf("sc", "sd"), result.coWinnerSubmissionIds)
        assertEquals(listOf(Tally("sa", 0), Tally("sb", 0), Tally("sc", 2), Tally("sd", 2)), result.tallies)
        assertEquals(4, result.ballotCount)
    }

    /** Proposed correction of the fixture case only_first_ballot_per_voter_counts (no self-vote). */
    @Test
    fun onlyFirstBallotPerVoterCountsWithoutSelfVotes() {
        val result = ResultCalculator.calculate(
            TallyInput(
                roster = setOf("a", "b", "c"),
                activeMembers = setOf("a", "b", "c"),
                submissions = photos.take(3),
                ballots = listOf(BallotRef("a", "sb"), BallotRef("a", "sc"), BallotRef("c", "sb")),
            ),
        )
        assertEquals(ChallengeOutcome.WINNER, result.outcome)
        assertEquals(listOf("sb"), result.coWinnerSubmissionIds)
        assertEquals(listOf(Tally("sa", 0), Tally("sb", 2), Tally("sc", 0)), result.tallies)
        assertEquals(2, result.ballotCount)
    }

    @Test
    fun calculationIsDeterministic() {
        val ballots = listOf(BallotRef("a", "sb"), BallotRef("b", "sc"), BallotRef("c", "sb"), BallotRef("d", "sa"))
        val first = ResultCalculator.calculate(input(ballots))
        repeat(20) { assertEquals(first, ResultCalculator.calculate(input(ballots))) }
    }
}
