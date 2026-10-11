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

/**
 * Forwards every call to [delegate] and clears the stored session when the server answers 401
 * (`ErrorCode.UNAUTHENTICATED`). The failure is returned unchanged, so the UI sees UNAUTHENTICATED and
 * routes to sign-in; by then the stored token is already gone and no later request reuses it.
 *
 * `createGuest` and `restoreWithRecoveryCode` are NOT guarded: a wrong recovery code also answers 401
 * and must not sign out a user who is already signed in.
 */
class GuardedPhotoDuelService(
    private val delegate: PhotoDuelService,
    private val gate: SessionGate,
) : PhotoDuelService {

    private suspend inline fun <T> guarded(call: () -> AppResult<T>): AppResult<T> {
        val generation = gate.currentGeneration()
        val result = call()
        if (result is AppResult.Failure && result.error.code == ErrorCode.UNAUTHENTICATED) {
            gate.expireIfUnchanged(generation)
        }
        return result
    }

    // Identity
    override suspend fun createGuest(displayName: String): AppResult<Session> = delegate.createGuest(displayName)
    override suspend fun restoreWithRecoveryCode(code: String): AppResult<Session> =
        delegate.restoreWithRecoveryCode(code)
    override suspend fun issueRecoveryCode(): AppResult<RecoveryCode> = guarded { delegate.issueRecoveryCode() }
    override suspend fun currentUser(): AppResult<UserProfile> = guarded { delegate.currentUser() }
    override suspend fun renameSelf(displayName: String): AppResult<UserProfile> =
        guarded { delegate.renameSelf(displayName) }
    override suspend fun logout(): AppResult<Unit> = guarded { delegate.logout() }
    override suspend fun deleteAccount(): AppResult<Unit> = guarded { delegate.deleteAccount() }

    // Catalogue
    override suspend fun prompts(): AppResult<PromptCatalog> = guarded { delegate.prompts() }

    // Rooms and invitations
    override suspend fun myRooms(): AppResult<List<RoomSummary>> = guarded { delegate.myRooms() }
    override suspend fun createRoom(name: String): AppResult<RoomDetail> = guarded { delegate.createRoom(name) }
    override suspend fun room(roomId: String): AppResult<RoomDetail> = guarded { delegate.room(roomId) }
    override suspend fun leaveRoom(roomId: String): AppResult<Unit> = guarded { delegate.leaveRoom(roomId) }
    override suspend fun removeMember(roomId: String, userId: String): AppResult<Unit> =
        guarded { delegate.removeMember(roomId, userId) }
    override suspend fun deleteRoom(roomId: String): AppResult<Unit> = guarded { delegate.deleteRoom(roomId) }
    override suspend fun createInvitation(roomId: String, ttl: InvitationTtl): AppResult<CreatedInvitation> =
        guarded { delegate.createInvitation(roomId, ttl) }
    override suspend fun revokeInvitation(roomId: String, invitationId: String): AppResult<Unit> =
        guarded { delegate.revokeInvitation(roomId, invitationId) }
    override suspend fun previewInvitation(token: String): AppResult<InvitationPreview> =
        guarded { delegate.previewInvitation(token) }
    override suspend fun joinWithInvitation(token: String): AppResult<RoomDetail> =
        guarded { delegate.joinWithInvitation(token) }

    // Challenges
    override suspend fun createChallenge(roomId: String, spec: ChallengeSpec): AppResult<Challenge> =
        guarded { delegate.createChallenge(roomId, spec) }
    override suspend fun startChallenge(challengeId: String): AppResult<Challenge> =
        guarded { delegate.startChallenge(challengeId) }
    override suspend fun cancelChallenge(challengeId: String): AppResult<Challenge> =
        guarded { delegate.cancelChallenge(challengeId) }
    override suspend fun challenge(challengeId: String): AppResult<ChallengeView> =
        guarded { delegate.challenge(challengeId) }
    override suspend fun roomHistory(roomId: String, cursor: String?): AppResult<Page<Challenge>> =
        guarded { delegate.roomHistory(roomId, cursor) }
    override suspend fun myHistory(cursor: String?): AppResult<Page<HistoryEntry>> =
        guarded { delegate.myHistory(cursor) }

    // Submissions, ballots, media
    override suspend fun submitPhoto(
        challengeId: String,
        photo: PreparedPhoto,
        onProgress: (Float) -> Unit,
    ): AppResult<Submission> = guarded { delegate.submitPhoto(challengeId, photo, onProgress) }
    override suspend fun withdrawSubmission(challengeId: String): AppResult<Unit> =
        guarded { delegate.withdrawSubmission(challengeId) }
    override suspend fun deleteSubmission(submissionId: String): AppResult<Unit> =
        guarded { delegate.deleteSubmission(submissionId) }
    override suspend fun castBallot(challengeId: String, submissionId: String): AppResult<Unit> =
        guarded { delegate.castBallot(challengeId, submissionId) }
    override fun mediaUrl(mediaId: String): String = delegate.mediaUrl(mediaId)

    // Safety
    override suspend fun report(target: ReportTarget, reason: ReportReason, details: String?): AppResult<Unit> =
        guarded { delegate.report(target, reason, details) }
    override suspend fun blockedUsers(): AppResult<List<BlockedUser>> = guarded { delegate.blockedUsers() }
    override suspend fun setBlocked(userId: String, blocked: Boolean): AppResult<Unit> =
        guarded { delegate.setBlocked(userId, blocked) }
}
