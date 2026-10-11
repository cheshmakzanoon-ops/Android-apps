package com.agency.photoduel.domain.rules

import com.agency.photoduel.domain.model.ChallengeOutcome
import com.agency.photoduel.domain.model.ChallengeResult
import com.agency.photoduel.domain.model.SubmissionStatus
import com.agency.photoduel.domain.model.Tally

/**
 * Everything the tally needs at voting close (architecture section 8).
 *
 * @property roster the frozen roster fixed when the challenge started.
 * @property activeMembers users who are still room members at close. Only roster members in this set
 *   are eligible voters; other entries (for example people who joined during the challenge) are ignored.
 * @property submissions every submission of the challenge with its status as of close.
 * @property ballots ballots in the order they were recorded.
 * @property blocks block relations as of close.
 */
data class TallyInput(
    val roster: Set<String>,
    val activeMembers: Set<String>,
    val submissions: List<SubmissionRef>,
    val ballots: List<BallotRef>,
    val blocks: BlockGraph = BlockGraph.EMPTY,
)

/**
 * Deterministic tally shared with the Node backend through `contracts/vectors/results.v1.json`.
 *
 * Rules, in order:
 * 1. `P` = ACTIVE submissions whose author is on the frozen roster.
 * 2. `B` = counted ballots. Only the **first recorded ballot of each voter** is considered at all; a
 *    later ballot by the same voter is ignored even when the first one is void. The first ballot then
 *    counts only if the voter is on the roster and still a member, the target is in `P`, the voter is
 *    not the target's author, and the pair is not blocked in either direction.
 * 3. `E` = roster members who are still members (`eligibleVoterCount`).
 * 4. `|P| < 2` gives INSUFFICIENT_SUBMISSIONS with empty tallies and `ballotCount` 0; else `|B| < 2`
 *    gives INSUFFICIENT_BALLOTS with a tally for every `p` in `P`; else the leaders win (one leader is
 *    WINNER, several are TIE with every leader as a co-winner).
 * 5. Tallies and co-winners are sorted by submission id (ascending, plain string comparison, which
 *    matches JavaScript's default UTF-16 code-unit sort used by the backend).
 *
 * Reading chosen for fairness (rule 2): ballots are immutable and the server refuses a second ballot
 * with ALREADY_VOTED, so a voter owns exactly one ballot. If that ballot later becomes void (for example
 * its target is hidden or removed before close) the voter's vote is lost; any later record for the same
 * voter can only come from a data anomaly and must never stand in for the void first ballot. Ballots
 * with a null voter (deleted account) are void and do not occupy anyone's first-ballot slot.
 */
object ResultCalculator {

    fun calculate(input: TallyInput): ChallengeResult {
        val eligibleVoters = eligibleVoters(input)
        val countable = countableSubmissions(input)

        if (countable.size < 2) {
            return ChallengeResult(
                outcome = ChallengeOutcome.INSUFFICIENT_SUBMISSIONS,
                coWinnerSubmissionIds = emptyList(),
                tallies = emptyList(),
                ballotCount = 0,
                eligibleVoterCount = eligibleVoters.size,
            )
        }

        val counted = countedBallots(input, countable, eligibleVoters)
        val votesBySubmission = countable.keys.associateWith { 0 }.toMutableMap()
        for (ballot in counted) {
            votesBySubmission[ballot.submissionId] = votesBySubmission.getValue(ballot.submissionId) + 1
        }
        val tallies = votesBySubmission
            .map { (submissionId, votes) -> Tally(submissionId = submissionId, votes = votes) }
            .sortedBy { it.submissionId }

        if (counted.size < 2) {
            return ChallengeResult(
                outcome = ChallengeOutcome.INSUFFICIENT_BALLOTS,
                coWinnerSubmissionIds = emptyList(),
                tallies = tallies,
                ballotCount = counted.size,
                eligibleVoterCount = eligibleVoters.size,
            )
        }

        val max = tallies.maxOf { it.votes }
        val leaders = tallies.filter { it.votes == max }.map { it.submissionId }.sorted()
        return ChallengeResult(
            outcome = if (leaders.size == 1) ChallengeOutcome.WINNER else ChallengeOutcome.TIE,
            coWinnerSubmissionIds = leaders,
            tallies = tallies,
            ballotCount = counted.size,
            eligibleVoterCount = eligibleVoters.size,
        )
    }

    /** `E`: roster members who are still room members. */
    fun eligibleVoters(input: TallyInput): Set<String> = input.roster.intersect(input.activeMembers)

    /**
     * `P`: ACTIVE submissions whose author is on the frozen roster, keyed by id. If an id appears more
     * than once, the first occurrence wins so the result stays deterministic.
     */
    fun countableSubmissions(input: TallyInput): Map<String, SubmissionRef> {
        val result = LinkedHashMap<String, SubmissionRef>()
        for (submission in input.submissions) {
            if (submission.id in result) continue
            val author = submission.authorUserId ?: continue
            if (submission.status == SubmissionStatus.ACTIVE && author in input.roster) {
                result[submission.id] = submission
            }
        }
        return result
    }

    /** `B`: the counted ballots, in recorded order. */
    fun countedBallots(input: TallyInput): List<BallotRef> =
        countedBallots(input, countableSubmissions(input), eligibleVoters(input))

    private fun countedBallots(
        input: TallyInput,
        countable: Map<String, SubmissionRef>,
        eligibleVoters: Set<String>,
    ): List<BallotRef> {
        val votersSeen = HashSet<String>()
        val counted = ArrayList<BallotRef>()
        for (ballot in input.ballots) {
            val voter = ballot.voterUserId ?: continue
            // First recorded ballot per voter is the only one ever considered (see the KDoc above).
            if (!votersSeen.add(voter)) continue
            if (voter !in eligibleVoters) continue
            val target = countable[ballot.submissionId] ?: continue
            if (target.authorUserId == voter) continue
            if (input.blocks.blockedEitherWay(voter, target.authorUserId)) continue
            counted += ballot
        }
        return counted
    }
}
