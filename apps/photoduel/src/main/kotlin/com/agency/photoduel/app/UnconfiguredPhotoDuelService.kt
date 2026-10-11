package com.agency.photoduel.app

import com.agency.photoduel.domain.model.AppResult
import com.agency.photoduel.domain.model.BlockedUser
import com.agency.photoduel.domain.model.Challenge
import com.agency.photoduel.domain.model.ChallengeSpec
import com.agency.photoduel.domain.model.ChallengeView
import com.agency.photoduel.domain.model.CreatedInvitation
import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.HistoryEntry
import com.agency.photoduel.domain.model.InvitationPreview
import com.agency.photoduel.domain.model.InvitationTtl
import com.agency.photoduel.domain.model.Page
import com.agency.photoduel.domain.model.PhotoDuelError
import com.agency.photoduel.domain.model.PreparedPhoto
import com.agency.photoduel.domain.model.PromptCatalog
import com.agency.photoduel.domain.model.RecoveryCode
import com.agency.photoduel.domain.model.ReportReason
import com.agency.photoduel.domain.model.ReportTarget
import com.agency.photoduel.domain.model.RoomDetail
import com.agency.photoduel.domain.model.RoomSummary
import com.agency.photoduel.domain.model.Session
import com.agency.photoduel.domain.model.Submission
import com.agency.photoduel.domain.model.UserProfile
import com.agency.photoduel.domain.port.PhotoDuelService
import com.agency.photoduel.domain.port.ServerClock

/**
 * Used when the build has no usable API base URL (every release build today, see decision PD-PM-15).
 * `UiDependencies.serverConfigured` is false and the UI shows its setup state; if something calls the
 * service anyway, it gets an honest failure and nothing touches the network. No success is invented.
 */
object UnconfiguredPhotoDuelService : PhotoDuelService {
    const val MESSAGE = "This build is not connected to a PhotoDuel server."

    private fun <T> unavailable(): AppResult<T> =
        AppResult.Failure(PhotoDuelError(ErrorCode.UNKNOWN, MESSAGE, retryable = false))

    override suspend fun createGuest(displayName: String): AppResult<Session> = unavailable()
    override suspend fun restoreWithRecoveryCode(code: String): AppResult<Session> = unavailable()
    override suspend fun issueRecoveryCode(): AppResult<RecoveryCode> = unavailable()
    override suspend fun currentUser(): AppResult<UserProfile> = unavailable()
    override suspend fun renameSelf(displayName: String): AppResult<UserProfile> = unavailable()
    override suspend fun logout(): AppResult<Unit> = unavailable()
    override suspend fun deleteAccount(): AppResult<Unit> = unavailable()
    override suspend fun prompts(): AppResult<PromptCatalog> = unavailable()
    override suspend fun myRooms(): AppResult<List<RoomSummary>> = unavailable()
    override suspend fun createRoom(name: String): AppResult<RoomDetail> = unavailable()
    override suspend fun room(roomId: String): AppResult<RoomDetail> = unavailable()
    override suspend fun leaveRoom(roomId: String): AppResult<Unit> = unavailable()
    override suspend fun removeMember(roomId: String, userId: String): AppResult<Unit> = unavailable()
    override suspend fun deleteRoom(roomId: String): AppResult<Unit> = unavailable()
    override suspend fun createInvitation(roomId: String, ttl: InvitationTtl): AppResult<CreatedInvitation> =
        unavailable()
    override suspend fun revokeInvitation(roomId: String, invitationId: String): AppResult<Unit> = unavailable()
    override suspend fun previewInvitation(token: String): AppResult<InvitationPreview> = unavailable()
    override suspend fun joinWithInvitation(token: String): AppResult<RoomDetail> = unavailable()
    override suspend fun createChallenge(roomId: String, spec: ChallengeSpec): AppResult<Challenge> = unavailable()
    override suspend fun startChallenge(challengeId: String): AppResult<Challenge> = unavailable()
    override suspend fun cancelChallenge(challengeId: String): AppResult<Challenge> = unavailable()
    override suspend fun challenge(challengeId: String): AppResult<ChallengeView> = unavailable()
    override suspend fun roomHistory(roomId: String, cursor: String?): AppResult<Page<Challenge>> = unavailable()
    override suspend fun myHistory(cursor: String?): AppResult<Page<HistoryEntry>> = unavailable()
    override suspend fun submitPhoto(
        challengeId: String,
        photo: PreparedPhoto,
        onProgress: (Float) -> Unit,
    ): AppResult<Submission> = unavailable()
    override suspend fun withdrawSubmission(challengeId: String): AppResult<Unit> = unavailable()
    override suspend fun deleteSubmission(submissionId: String): AppResult<Unit> = unavailable()
    override suspend fun castBallot(challengeId: String, submissionId: String): AppResult<Unit> = unavailable()
    override fun mediaUrl(mediaId: String): String = ""
    override suspend fun report(target: ReportTarget, reason: ReportReason, details: String?): AppResult<Unit> =
        unavailable()
    override suspend fun blockedUsers(): AppResult<List<BlockedUser>> = unavailable()
    override suspend fun setBlocked(userId: String, blocked: Boolean): AppResult<Unit> = unavailable()
}

/** Device clock, used only when no server is configured. Rules never read it; countdowns need a server. */
object DeviceClockFallback : ServerClock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}
