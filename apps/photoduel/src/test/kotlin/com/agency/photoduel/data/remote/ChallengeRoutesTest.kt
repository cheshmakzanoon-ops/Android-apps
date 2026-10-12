package com.agency.photoduel.data.remote

import com.agency.photoduel.domain.model.Challenge
import com.agency.photoduel.domain.model.ChallengeOutcome
import com.agency.photoduel.domain.model.ChallengeResult
import com.agency.photoduel.domain.model.ChallengeSpec
import com.agency.photoduel.domain.model.ChallengeState
import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.MemberRole
import com.agency.photoduel.domain.model.MyStatus
import com.agency.photoduel.domain.model.Prompt
import com.agency.photoduel.domain.model.PromptCatalog
import com.agency.photoduel.domain.model.RoomMember
import com.agency.photoduel.domain.model.Submission
import com.agency.photoduel.domain.model.SubmissionStatus
import com.agency.photoduel.domain.model.Tally
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** docs/api-contract.md sections 5 to 8: prompts, challenges, history, submissions, ballots and media. */
internal class ChallengeRoutesTest : RemoteTestBase() {

    private val prompt = Prompt("p-001", "Morning light", "Catch the first light of the day.")
    private val openChallenge = Challenge(
        id = "c1",
        roomId = "r1",
        roundNumber = 2,
        prompt = prompt,
        state = ChallengeState.OPEN_FOR_SUBMISSIONS,
        outcome = null,
        submissionDeadlineEpochMillis = 1234,
        votingDeadlineEpochMillis = 5678,
        serverTimeEpochMillis = 1200,
    )
    private val createdChallenge = Challenge(
        id = "c2",
        roomId = "r1",
        roundNumber = 3,
        prompt = prompt,
        state = ChallengeState.CREATED,
        outcome = null,
        submissionDeadlineEpochMillis = null,
        votingDeadlineEpochMillis = null,
        serverTimeEpochMillis = 1300,
    )
    private val completedChallenge = Challenge(
        id = "c0",
        roomId = "r1",
        roundNumber = 1,
        prompt = prompt,
        state = ChallengeState.COMPLETED,
        outcome = ChallengeOutcome.TIE,
        submissionDeadlineEpochMillis = 100,
        votingDeadlineEpochMillis = 200,
        serverTimeEpochMillis = 300,
    )
    private val tie = ChallengeResult(
        outcome = ChallengeOutcome.TIE,
        coWinnerSubmissionIds = listOf("s1", "s2"),
        tallies = listOf(Tally("s1", 2), Tally("s2", 2)),
        ballotCount = 4,
        eligibleVoterCount = 5,
    )
    private val mySubmission = Submission(
        id = "s1",
        challengeId = "c1",
        authorUserId = "u1",
        authorDisplayName = "Ada",
        status = SubmissionStatus.ACTIVE,
        thumbnailMediaId = "m-thumb-1",
        fullMediaId = "m-full-1",
        width = 1600,
        height = 1067,
        isMine = true,
    )

    @Test
    fun promptsReadsTheCatalogue() = blocking {
        enqueueJson(200, """{"version":"1.0.0","prompts":[${Fixtures.PROMPT}]}""")

        assertEquals(PromptCatalog("1.0.0", listOf(prompt)), service.prompts().success())

        val request = takeRequest()
        request.assertRoute("GET", "/v1/prompts")
        request.assertAuthenticated()
    }

    @Test
    fun createChallengePostsTheSpecAndReadsACreatedChallengeWithNullDeadlines() = blocking {
        enqueueJson(201, Fixtures.CHALLENGE_CREATED)

        val challenge = service.createChallenge("r1", ChallengeSpec("p-001", 24, 6)).success()

        assertEquals(createdChallenge, challenge)
        val request = takeRequest()
        request.assertRoute("POST", "/v1/rooms/r1/challenges")
        request.assertAuthenticated()
        request.assertJsonBody("""{"promptId":"p-001","submissionHours":24,"votingHours":6}""")
    }

    @Test
    fun startChallengePostsAnEmptyObject() = blocking {
        enqueueJson(200, Fixtures.CHALLENGE_OPEN)

        assertEquals(openChallenge, service.startChallenge("c1").success())

        val request = takeRequest()
        request.assertRoute("POST", "/v1/challenges/c1/start")
        request.assertAuthenticated()
        request.assertJsonBody("{}")
    }

    @Test
    fun startingTwiceIsInvalidTransition() = blocking {
        enqueueError(409, "INVALID_TRANSITION", "Challenge is not in CREATED")

        val error = service.startChallenge("c1").failure()

        assertEquals(ErrorCode.INVALID_TRANSITION, error.code)
        assertFalse(error.retryable)
    }

    @Test
    fun cancelChallengePostsAnEmptyObject() = blocking {
        enqueueJson(200, Fixtures.CHALLENGE_OPEN.replace("OPEN_FOR_SUBMISSIONS", "CANCELLED"))

        assertEquals(ChallengeState.CANCELLED, service.cancelChallenge("c1").success().state)

        val request = takeRequest()
        request.assertRoute("POST", "/v1/challenges/c1/cancel")
        request.assertAuthenticated()
        request.assertJsonBody("{}")
    }

    @Test
    fun challengeReadsTheFullViewIncludingADeletedAuthorAndTheResult() = blocking {
        enqueueJson(
            200,
            """{
              "challenge":${Fixtures.CHALLENGE_COMPLETED},
              "roster":[{"userId":"u1","displayName":"Ada","role":"OWNER"},{"userId":"u3","displayName":"Lin","role":"MEMBER"}],
              "submissions":[${Fixtures.SUBMISSION_MINE},${Fixtures.SUBMISSION_DELETED_AUTHOR}],
              "myStatus":{"isRosterMember":true,"mySubmissionId":"s1","myBallotSubmissionId":null,"canSubmit":false,"canVote":false},
              "result":${Fixtures.RESULT_TIE}
            }""",
        )

        val view = service.challenge("c0").success()

        assertEquals(completedChallenge, view.challenge)
        assertEquals(listOf(RoomMember("u1", "Ada", MemberRole.OWNER), RoomMember("u3", "Lin", MemberRole.MEMBER)), view.roster)
        assertEquals(mySubmission, view.submissions[0])
        assertNull(view.submissions[1].authorUserId)
        assertEquals("Deleted player", view.submissions[1].authorDisplayName)
        assertEquals(MyStatus(true, "s1", null, canSubmit = false, canVote = false), view.myStatus)
        assertEquals(tie, view.result)
        val request = takeRequest()
        request.assertRoute("GET", "/v1/challenges/c0")
        request.assertAuthenticated()
    }

    @Test
    fun challengeWithoutResultReadsANullResult() = blocking {
        enqueueJson(
            200,
            """{"challenge":${Fixtures.CHALLENGE_OPEN},"roster":[],"submissions":[],
               "myStatus":{"isRosterMember":false,"mySubmissionId":null,"myBallotSubmissionId":null,"canSubmit":false,"canVote":false},
               "result":null}""",
        )

        val view = service.challenge("c1").success()

        assertNull(view.result)
        assertEquals(openChallenge, view.challenge)
    }

    @Test
    fun anEnumValueOutsideTheContractIsAnUnreadableResponseNotACrash() = blocking {
        enqueueJson(200, Fixtures.CHALLENGE_OPEN.replace("OPEN_FOR_SUBMISSIONS", "PAUSED"))

        val error = service.startChallenge("c1").failure()

        assertEquals(ErrorCode.UNKNOWN, error.code)
        assertEquals(ApiErrors.UNREADABLE_MESSAGE, error.message)
    }

    @Test
    fun malformedJsonIsAnUnreadableResponse() = blocking {
        enqueueJson(200, """{"id":"c1",""")

        assertEquals(ErrorCode.UNKNOWN, service.challenge("c1").failure().code)
    }

    @Test
    fun roomHistoryAsksForTwentyAndOmitsAMissingCursor() = blocking {
        enqueueJson(200, """{"items":[${Fixtures.CHALLENGE_OPEN},${Fixtures.CHALLENGE_COMPLETED}],"nextCursor":"next-1"}""")

        val page = service.roomHistory("r1", null).success()

        assertEquals(listOf(openChallenge, completedChallenge), page.items)
        assertEquals("next-1", page.nextCursor)
        val request = takeRequest()
        request.assertRoute("GET", "/v1/rooms/r1/challenges?limit=20")
        request.assertAuthenticated()
    }

    @Test
    fun roomHistorySendsTheOpaqueCursorEncoded() = blocking {
        enqueueJson(200, """{"items":[],"nextCursor":null}""")

        val page = service.roomHistory("r1", "a b&c=d").success()

        assertEquals(emptyList<Challenge>(), page.items)
        assertNull(page.nextCursor)
        takeRequest().assertRoute("GET", "/v1/rooms/r1/challenges?cursor=a%20b%26c%3Dd&limit=20")
    }

    @Test
    fun myHistoryReadsEntriesWithNullableSubmissionAndResult() = blocking {
        enqueueJson(
            200,
            """{"items":[
                 {"challenge":${Fixtures.CHALLENGE_COMPLETED},"roomName":"Sunday walkers","mySubmissionId":"s1","result":${Fixtures.RESULT_TIE}},
                 {"challenge":${Fixtures.CHALLENGE_OPEN},"roomName":"Lunch club","mySubmissionId":null,"result":null}
               ],"nextCursor":null}""",
        )

        val page = service.myHistory("cur-2").success()

        assertEquals(2, page.items.size)
        assertEquals("Sunday walkers", page.items[0].roomName)
        assertEquals(tie, page.items[0].result)
        assertNull(page.items[1].mySubmissionId)
        assertNull(page.items[1].result)
        assertNull(page.nextCursor)
        val request = takeRequest()
        request.assertRoute("GET", "/v1/me/history?cursor=cur-2&limit=20")
        request.assertAuthenticated()
    }

    @Test
    fun withdrawSubmissionIsABodylessDelete() = blocking {
        enqueueNoContent()

        service.withdrawSubmission("c1").success()

        val request = takeRequest()
        request.assertRoute("DELETE", "/v1/challenges/c1/submission")
        request.assertAuthenticated()
        request.assertNoBody()
    }

    @Test
    fun deleteSubmissionIsABodylessDelete() = blocking {
        enqueueNoContent()

        service.deleteSubmission("s1").success()

        val request = takeRequest()
        request.assertRoute("DELETE", "/v1/submissions/s1")
        request.assertAuthenticated()
        request.assertNoBody()
    }

    @Test
    fun castBallotPostsTheSubmissionIdAndAcceptsANewBallot() = blocking {
        enqueueJson(201, """{"challengeId":"c1","submissionId":"s2","castAtEpochMillis":4000}""")

        service.castBallot("c1", "s2").success()

        val request = takeRequest()
        request.assertRoute("POST", "/v1/challenges/c1/ballot")
        request.assertAuthenticated()
        request.assertJsonBody("""{"submissionId":"s2"}""")
    }

    @Test
    fun castBallotIsSafeToRepeatBecauseTheSameBallotAnswers200() = blocking {
        enqueueJson(201, """{"challengeId":"c1","submissionId":"s2","castAtEpochMillis":4000}""")
        enqueueJson(200, """{"challengeId":"c1","submissionId":"s2","castAtEpochMillis":4000}""")

        service.castBallot("c1", "s2").success()
        service.castBallot("c1", "s2").success()

        assertEquals(2, server.requestCount)
    }

    @Test
    fun castBallotForADifferentSubmissionIsAlreadyVoted() = blocking {
        enqueueError(409, "ALREADY_VOTED", "You have already voted in this challenge")

        val error = service.castBallot("c1", "s3").failure()

        assertEquals(ErrorCode.ALREADY_VOTED, error.code)
        assertFalse(error.retryable)
    }

    @Test
    fun mediaUrlIsAbsoluteUnderTheConfiguredBase() {
        assertEquals(server.url("/v1/media/m-full-1").toString(), service.mediaUrl("m-full-1"))
        assertEquals(server.url("/v1/media/a%2Fb").toString(), service.mediaUrl("a/b"))
        assertEquals(0, server.requestCount)
    }
}
