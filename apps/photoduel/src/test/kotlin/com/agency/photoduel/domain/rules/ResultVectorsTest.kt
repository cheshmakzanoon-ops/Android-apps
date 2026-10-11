package com.agency.photoduel.domain.rules

import com.agency.photoduel.domain.model.ChallengeOutcome
import com.agency.photoduel.domain.model.ChallengeResult
import com.agency.photoduel.domain.model.SubmissionStatus
import com.agency.photoduel.domain.model.Tally
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/**
 * Runs every case of `contracts/vectors/results.v1.json` through [ResultCalculator]. The Node backend
 * runs the same file, so both tallies are held to one golden definition (architecture section 8).
 */
@RunWith(Parameterized::class)
class ResultVectorsTest(@Suppress("unused") private val name: String, private val case: VectorCase) {

    @Test
    fun producesExpectedResult() {
        val actual = ResultCalculator.calculate(case.toInput())
        val expected = case.expected

        assertEquals("outcome", ChallengeOutcome.valueOf(expected.outcome), actual.outcome)
        assertEquals("coWinnerSubmissionIds", expected.coWinnerSubmissionIds, actual.coWinnerSubmissionIds)
        assertEquals(
            "tallies (sorted by submissionId)",
            expected.tallies.sortedBy { it.submissionId }.map { Tally(it.submissionId, it.votes) },
            actual.tallies,
        )
        assertEquals("ballotCount", expected.ballotCount, actual.ballotCount)
        assertEquals("eligibleVoterCount", expected.eligibleVoterCount, actual.eligibleVoterCount)
        if (actual.outcome == ChallengeOutcome.WINNER) {
            assertEquals("winnerSubmissionId", expected.winnerSubmissionId, actual.coWinnerSubmissionIds.single())
        } else {
            assertNull("winnerSubmissionId", expected.winnerSubmissionId)
        }
    }

    @Test
    fun resultIsSortedAndIndependentOfSubmissionOrder() {
        val input = case.toInput()
        val forward = ResultCalculator.calculate(input)
        val reversed = ResultCalculator.calculate(input.copy(submissions = input.submissions.reversed()))
        assertEquals(forward, reversed)
        assertEquals(forward.tallies.sortedBy { it.submissionId }, forward.tallies)
        assertEquals(forward.coWinnerSubmissionIds.sorted(), forward.coWinnerSubmissionIds)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = VectorFixtures.load().cases.map { arrayOf(it.name, it) }
    }
}

/** Checks on the fixture file itself, so a truncated or renamed file cannot pass silently. */
class ResultVectorsFileTest {

    @Test
    fun fileHasRuleVersionOneAndUniqueNamedCases() {
        val file = VectorFixtures.load()
        assertEquals("1", file.ruleVersion)
        assertTrue("expected at least 9 cases, found ${file.cases.size}", file.cases.size >= 9)
        assertEquals(file.cases.size, file.cases.map { it.name }.toSet().size)
    }

    @Test
    fun fileCoversTheRequiredCategories() {
        val names = VectorFixtures.load().cases.map { it.name }
        val outcomes = VectorFixtures.load().cases.map { it.expected.outcome }.toSet()
        assertEquals(ChallengeOutcome.entries.map { it.name }.toSet(), outcomes)
        listOf("tie", "self_vote", "blocked", "removed", "first_ballot", "insufficient").forEach { key ->
            assertTrue("no case covers '$key'", names.any { key in it })
        }
    }
}

@Serializable
data class VectorFile(val ruleVersion: String, val cases: List<VectorCase>)

@Serializable
data class VectorCase(
    val name: String,
    val roster: List<String>,
    val activeMembers: List<String>,
    val submissions: List<VectorSubmission>,
    val blocks: List<List<String>>,
    val ballots: List<VectorBallot>,
    val expected: VectorExpected,
) {
    fun toInput(): TallyInput = TallyInput(
        roster = roster.toSet(),
        activeMembers = activeMembers.toSet(),
        submissions = submissions.map { SubmissionRef(it.id, it.author, SubmissionStatus.valueOf(it.status)) },
        ballots = ballots.map { BallotRef(it.voter, it.submission) },
        blocks = BlockGraph.of(
            blocks.map { pair ->
                require(pair.size == 2) { "A block must be a pair: $pair" }
                pair[0] to pair[1]
            },
        ),
    )

    fun expectedMatches(actual: ChallengeResult): Boolean =
        ChallengeOutcome.valueOf(expected.outcome) == actual.outcome &&
            expected.coWinnerSubmissionIds == actual.coWinnerSubmissionIds &&
            expected.tallies.sortedBy { it.submissionId }.map { Tally(it.submissionId, it.votes) } == actual.tallies &&
            expected.ballotCount == actual.ballotCount &&
            expected.eligibleVoterCount == actual.eligibleVoterCount

    fun hasSelfVoteBallot(): Boolean {
        val authorOf = submissions.associate { it.id to it.author }
        return ballots.any { it.voter != null && authorOf[it.submission] == it.voter }
    }

    override fun toString(): String = name
}

@Serializable
data class VectorSubmission(val id: String, val author: String?, val status: String)

@Serializable
data class VectorBallot(val voter: String?, val submission: String)

@Serializable
data class VectorExpected(
    val outcome: String,
    val coWinnerSubmissionIds: List<String>,
    val tallies: List<VectorTally>,
    val ballotCount: Int,
    val eligibleVoterCount: Int,
    val winnerSubmissionId: String? = null,
)

@Serializable
data class VectorTally(val submissionId: String, val votes: Int)

object VectorFixtures {
    private const val RELATIVE_PATH = "contracts/vectors/results.v1.json"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Finds the fixture by walking up from the working directory, so the test runs from the pure-JVM
     * core module (apps/photoduel/core), the Android module (apps/photoduel) or the repository root.
     * A missing file fails the test; it is never skipped.
     */
    fun load(): VectorFile {
        val file = locate() ?: error("Cannot find $RELATIVE_PATH above ${File(".").absoluteFile}")
        return json.decodeFromString(VectorFile.serializer(), file.readText())
    }

    private fun locate(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            listOf(File(dir, RELATIVE_PATH), File(dir, "apps/photoduel/$RELATIVE_PATH"))
                .firstOrNull { it.isFile }
                ?.let { return it }
            dir = dir.parentFile
        }
        return null
    }
}
