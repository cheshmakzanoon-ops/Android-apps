package com.agency.photoduel.domain.model

// Frozen contract v1: docs/architecture.md section 12. Changes need a PM decision and a revision bump.
// Timestamps are epoch milliseconds from the server clock. Ids are opaque strings.

enum class ChallengeState { CREATED, OPEN_FOR_SUBMISSIONS, VOTING, COMPLETED, ARCHIVED, CANCELLED }
enum class ChallengeOutcome { WINNER, TIE, INSUFFICIENT_SUBMISSIONS, INSUFFICIENT_BALLOTS }
enum class MemberRole { OWNER, MEMBER }
enum class SubmissionStatus { ACTIVE, WITHDRAWN, REMOVED, HIDDEN }
enum class MediaVariant { FULL, THUMBNAIL }
enum class ReportReason { INAPPROPRIATE, HARASSMENT, PRIVACY, SPAM, NOT_A_PHOTO_OF_PROMPT, OTHER }
enum class InvitationTtl(val hours: Int) { ONE_DAY(24), THREE_DAYS(72), ONE_WEEK(168) }

data class UserProfile(val id: String, val displayName: String, val hasRecoveryCode: Boolean)

/** [token] is the bearer credential. The data layer persists it through SessionStore only. */
data class Session(val user: UserProfile, val token: String, val expiresAtEpochMillis: Long)

/** Shown to the user once. Never persisted in plain text by the app. */
data class RecoveryCode(val code: String)

data class Prompt(val id: String, val title: String, val brief: String)
data class PromptCatalog(val version: String, val prompts: List<Prompt>)

data class RoomSummary(
    val id: String,
    val name: String,
    val memberCount: Int,
    val capacity: Int,
    val activeChallengeState: ChallengeState?,
)

data class RoomMember(val userId: String, val displayName: String, val role: MemberRole)

data class RoomDetail(
    val id: String,
    val name: String,
    val capacity: Int,
    val members: List<RoomMember>,
    val viewerRole: MemberRole,
    val viewerUserId: String,
)

/** [token] is shown once. The server keeps only its hash. */
data class CreatedInvitation(val id: String, val token: String, val expiresAtEpochMillis: Long)

data class InvitationPreview(
    val roomName: String,
    val ownerDisplayName: String,
    val memberCount: Int,
    val capacity: Int,
    val expiresAtEpochMillis: Long,
)

data class ChallengeSpec(val promptId: String, val submissionHours: Int, val votingHours: Int)

data class Challenge(
    val id: String,
    val roomId: String,
    val roundNumber: Int,
    val prompt: Prompt,
    val state: ChallengeState,
    val outcome: ChallengeOutcome?,
    val submissionDeadlineEpochMillis: Long?,
    val votingDeadlineEpochMillis: Long?,
    val serverTimeEpochMillis: Long,
)

data class Submission(
    val id: String,
    val challengeId: String,
    /** Null when the author deleted their account. */
    val authorUserId: String?,
    val authorDisplayName: String,
    val status: SubmissionStatus,
    val thumbnailMediaId: String,
    val fullMediaId: String,
    val width: Int,
    val height: Int,
    val isMine: Boolean,
)

data class Tally(val submissionId: String, val votes: Int)

data class ChallengeResult(
    val outcome: ChallengeOutcome,
    val coWinnerSubmissionIds: List<String>,
    val tallies: List<Tally>,
    val ballotCount: Int,
    val eligibleVoterCount: Int,
)

data class MyStatus(
    val isRosterMember: Boolean,
    val mySubmissionId: String?,
    val myBallotSubmissionId: String?,
    val canSubmit: Boolean,
    val canVote: Boolean,
)

data class ChallengeView(
    val challenge: Challenge,
    val roster: List<RoomMember>,
    val submissions: List<Submission>,
    val myStatus: MyStatus,
    val result: ChallengeResult?,
)

data class Page<T>(val items: List<T>, val nextCursor: String?)

data class HistoryEntry(
    val challenge: Challenge,
    val roomName: String,
    val mySubmissionId: String?,
    val result: ChallengeResult?,
)

data class BlockedUser(val userId: String, val displayName: String)

sealed interface ReportTarget {
    data class Submission(val submissionId: String) : ReportTarget
    data class User(val userId: String) : ReportTarget
}

/** Output of the engine pipeline. Both encodings have EXIF and location metadata removed. */
class PreparedPhoto(
    val fullJpeg: ByteArray,
    val thumbnailJpeg: ByteArray,
    val width: Int,
    val height: Int,
)
