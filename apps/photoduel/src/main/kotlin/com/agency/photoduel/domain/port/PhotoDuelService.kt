package com.agency.photoduel.domain.port

import com.agency.photoduel.domain.model.AppResult
import com.agency.photoduel.domain.model.BlockedUser
import com.agency.photoduel.domain.model.Challenge
import com.agency.photoduel.domain.model.ChallengeSpec
import com.agency.photoduel.domain.model.ChallengeView
import com.agency.photoduel.domain.model.CreatedInvitation
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

// Frozen contract v1 (docs/architecture.md section 12). Every method returns AppResult.
// Implementations: data/remote (HTTP) and domain/testing (in-memory, same rules as the server).
interface PhotoDuelService {
    // Identity
    suspend fun createGuest(displayName: String): AppResult<Session>
    suspend fun restoreWithRecoveryCode(code: String): AppResult<Session>
    suspend fun issueRecoveryCode(): AppResult<RecoveryCode>
    suspend fun currentUser(): AppResult<UserProfile>
    suspend fun renameSelf(displayName: String): AppResult<UserProfile>
    suspend fun logout(): AppResult<Unit>
    suspend fun deleteAccount(): AppResult<Unit>

    // Catalogue
    suspend fun prompts(): AppResult<PromptCatalog>

    // Rooms and invitations
    suspend fun myRooms(): AppResult<List<RoomSummary>>
    suspend fun createRoom(name: String): AppResult<RoomDetail>
    suspend fun room(roomId: String): AppResult<RoomDetail>
    suspend fun leaveRoom(roomId: String): AppResult<Unit>
    suspend fun removeMember(roomId: String, userId: String): AppResult<Unit>
    suspend fun deleteRoom(roomId: String): AppResult<Unit>
    suspend fun createInvitation(roomId: String, ttl: InvitationTtl): AppResult<CreatedInvitation>
    suspend fun revokeInvitation(roomId: String, invitationId: String): AppResult<Unit>
    suspend fun previewInvitation(token: String): AppResult<InvitationPreview>
    suspend fun joinWithInvitation(token: String): AppResult<RoomDetail>

    // Challenges
    suspend fun createChallenge(roomId: String, spec: ChallengeSpec): AppResult<Challenge>
    suspend fun startChallenge(challengeId: String): AppResult<Challenge>
    suspend fun cancelChallenge(challengeId: String): AppResult<Challenge>
    suspend fun challenge(challengeId: String): AppResult<ChallengeView>
    suspend fun roomHistory(roomId: String, cursor: String?): AppResult<Page<Challenge>>
    suspend fun myHistory(cursor: String?): AppResult<Page<HistoryEntry>>

    // Submissions, ballots, media
    suspend fun submitPhoto(
        challengeId: String,
        photo: PreparedPhoto,
        onProgress: (Float) -> Unit,
    ): AppResult<Submission>
    suspend fun withdrawSubmission(challengeId: String): AppResult<Unit>
    suspend fun deleteSubmission(submissionId: String): AppResult<Unit>
    suspend fun castBallot(challengeId: String, submissionId: String): AppResult<Unit>

    /** Absolute URL. The data layer adds the Authorization header; the UI never handles tokens. */
    fun mediaUrl(mediaId: String): String

    // Safety
    suspend fun report(target: ReportTarget, reason: ReportReason, details: String?): AppResult<Unit>
    suspend fun blockedUsers(): AppResult<List<BlockedUser>>
    suspend fun setBlocked(userId: String, blocked: Boolean): AppResult<Unit>
}
