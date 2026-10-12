package com.agency.photoduel.data.remote

import kotlinx.serialization.Serializable

// docs/api-contract.md sections 5 to 8 (Prompts, Challenges, Submissions, Ballots).

/** `{"id":"p-001","title":"…","brief":"…"}`. */
@Serializable
internal data class PromptDto(val id: String, val title: String, val brief: String)

/** `GET /v1/prompts` response: `{"version":"1.0.0","prompts":[…]}`. */
@Serializable
internal data class PromptCatalogDto(val version: String, val prompts: List<PromptDto>)

/** `POST /v1/rooms/{roomId}/challenges` request: `{"promptId":"p-001","submissionHours":24,"votingHours":6}`. */
@Serializable
internal data class CreateChallengeRequestDto(val promptId: String, val submissionHours: Int, val votingHours: Int)

/** `Challenge`. Deadlines are null until the challenge is started; `outcome` is null until it completes. */
@Serializable
internal data class ChallengeDto(
    val id: String,
    val roomId: String,
    val roundNumber: Int,
    val prompt: PromptDto,
    val state: String,
    val outcome: String? = null,
    val submissionDeadlineEpochMillis: Long? = null,
    val votingDeadlineEpochMillis: Long? = null,
    val serverTimeEpochMillis: Long,
)

/** `Submission`. `authorUserId` is null when the author deleted their account. */
@Serializable
internal data class SubmissionDto(
    val id: String,
    val challengeId: String,
    val authorUserId: String? = null,
    val authorDisplayName: String,
    val status: String,
    val thumbnailMediaId: String,
    val fullMediaId: String,
    val width: Int,
    val height: Int,
    val isMine: Boolean,
)

/** `{"submissionId":"…","votes":3}`. */
@Serializable
internal data class TallyDto(val submissionId: String, val votes: Int)

/** `result` of a completed challenge. */
@Serializable
internal data class ChallengeResultDto(
    val outcome: String,
    val coWinnerSubmissionIds: List<String>,
    val tallies: List<TallyDto>,
    val ballotCount: Int,
    val eligibleVoterCount: Int,
)

/** `myStatus` of a `ChallengeView`. */
@Serializable
internal data class MyStatusDto(
    val isRosterMember: Boolean,
    val mySubmissionId: String? = null,
    val myBallotSubmissionId: String? = null,
    val canSubmit: Boolean,
    val canVote: Boolean,
)

/** `GET /v1/challenges/{challengeId}` response. */
@Serializable
internal data class ChallengeViewDto(
    val challenge: ChallengeDto,
    val roster: List<RoomMemberDto>,
    val submissions: List<SubmissionDto>,
    val myStatus: MyStatusDto,
    val result: ChallengeResultDto? = null,
)

/** `HistoryEntry`: `{"challenge":Challenge,"roomName":"…","mySubmissionId":"…"|null,"result":ChallengeResult|null}`. */
@Serializable
internal data class HistoryEntryDto(
    val challenge: ChallengeDto,
    val roomName: String,
    val mySubmissionId: String? = null,
    val result: ChallengeResultDto? = null,
)

/** `POST /v1/challenges/{challengeId}/ballot` request: `{"submissionId":"…"}`. */
@Serializable
internal data class BallotRequestDto(val submissionId: String)

/** `POST /v1/challenges/{challengeId}/ballot` response (`201` new, `200` same submission already recorded). */
@Serializable
internal data class BallotResponseDto(val challengeId: String, val submissionId: String, val castAtEpochMillis: Long)
