package com.agency.photoduel.data.remote

import com.agency.photoduel.data.remote.PhotoDuelApiClient.Companion.DELETE
import com.agency.photoduel.data.remote.PhotoDuelApiClient.Companion.GET
import com.agency.photoduel.data.remote.PhotoDuelApiClient.Companion.PATCH
import com.agency.photoduel.data.remote.PhotoDuelApiClient.Companion.POST
import com.agency.photoduel.data.remote.PhotoDuelApiClient.Companion.PUT
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
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.Call
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * [PhotoDuelService] over HTTP, following docs/api-contract.md route by route.
 *
 * Session handling: a successful guest creation or restore stores the new token through the session
 * store; a successful logout or account deletion clears it. Clearing the session after a `401` is the
 * caller's job (app/GuardedPhotoDuelService), because only the caller knows whether the failed request
 * still belongs to the current session.
 *
 * Retries: only GET routes are retried automatically (see [PhotoDuelApiClient]). [castBallot] and
 * [submitPhoto] are idempotent on the server for the same input, so the caller may repeat them after a
 * `NETWORK` or `TIMEOUT` failure: a ballot already recorded for the same submission answers `200` and is
 * reported as success.
 */
class RemotePhotoDuelService(private val api: PhotoDuelApiClient) : PhotoDuelService {

    private val sessionStore get() = api.sessionStore

    /**
     * The code issued when [restoreWithRecoveryCode] rotated the old one (the server returns it once).
     * The frozen [PhotoDuelService] returns only a [Session] from restore, so the rotated code is kept
     * here in memory until it is read with [takeRotatedRecoveryCode], a logout or an account deletion.
     */
    @Volatile
    private var rotatedRecoveryCode: RecoveryCode? = null

    /** Returns the code rotated by the last successful restore, once, and forgets it. */
    fun takeRotatedRecoveryCode(): RecoveryCode? = rotatedRecoveryCode.also { rotatedRecoveryCode = null }

    /** Authenticated call factory for [mediaUrl] (for example Coil's OkHttp fetcher). */
    val mediaCallFactory: Call.Factory get() = api.mediaClient

    // ---- Identity (section 2) ------------------------------------------------------------------

    override suspend fun createGuest(displayName: String): AppResult<Session> {
        val result = api.execute(
            POST,
            listOf("identity", "guest"),
            body = json(GuestRequestDto(displayName)),
            authenticated = false,
        ) { decode<SessionResponseDto>(it).toDomain() }
        return storeSessionOf(result)
    }

    override suspend fun restoreWithRecoveryCode(code: String): AppResult<Session> {
        var rotated: String? = null
        val result = api.execute(
            POST,
            listOf("identity", "restore"),
            body = json(RestoreRequestDto(code)),
            authenticated = false,
        ) { text -> decode<SessionResponseDto>(text).also { rotated = it.recoveryCode }.toDomain() }
        val stored = storeSessionOf(result)
        if (stored is AppResult.Success) rotatedRecoveryCode = rotated?.let(::RecoveryCode)
        return stored
    }

    override suspend fun issueRecoveryCode(): AppResult<RecoveryCode> =
        api.execute(POST, listOf("identity", "recovery-code"), body = emptyJson()) {
            decode<RecoveryCodeDto>(it).toDomain()
        }

    override suspend fun currentUser(): AppResult<UserProfile> =
        api.execute(GET, listOf("me")) { decode<UserDto>(it).toDomain() }

    override suspend fun renameSelf(displayName: String): AppResult<UserProfile> =
        api.execute(PATCH, listOf("me"), body = json(RenameRequestDto(displayName))) { decode<UserDto>(it).toDomain() }

    override suspend fun logout(): AppResult<Unit> =
        clearSessionAfter(api.execute(POST, listOf("identity", "logout"), body = emptyJson()) { })

    override suspend fun deleteAccount(): AppResult<Unit> =
        clearSessionAfter(api.execute(DELETE, listOf("me"), body = json(DeleteAccountRequestDto(DELETE_CONFIRMATION))) { })

    // ---- Catalogue (section 5) -----------------------------------------------------------------

    override suspend fun prompts(): AppResult<PromptCatalog> =
        api.execute(GET, listOf("prompts")) { decode<PromptCatalogDto>(it).toDomain() }

    // ---- Rooms and invitations (section 4) -----------------------------------------------------

    override suspend fun myRooms(): AppResult<List<RoomSummary>> =
        api.execute(GET, listOf("rooms")) { text -> decode<RoomsResponseDto>(text).rooms.map { it.toDomain() } }

    override suspend fun createRoom(name: String): AppResult<RoomDetail> =
        api.execute(POST, listOf("rooms"), body = json(CreateRoomRequestDto(name))) { decode<RoomDetailDto>(it).toDomain() }

    override suspend fun room(roomId: String): AppResult<RoomDetail> =
        api.execute(GET, listOf("rooms", roomId)) { decode<RoomDetailDto>(it).toDomain() }

    override suspend fun leaveRoom(roomId: String): AppResult<Unit> =
        api.execute(POST, listOf("rooms", roomId, "leave"), body = emptyJson()) { }

    override suspend fun removeMember(roomId: String, userId: String): AppResult<Unit> =
        api.execute(DELETE, listOf("rooms", roomId, "members", userId)) { }

    override suspend fun deleteRoom(roomId: String): AppResult<Unit> =
        api.execute(DELETE, listOf("rooms", roomId)) { }

    override suspend fun createInvitation(roomId: String, ttl: InvitationTtl): AppResult<CreatedInvitation> =
        api.execute(POST, listOf("rooms", roomId, "invitations"), body = json(CreateInvitationRequestDto(ttl.hours))) {
            decode<CreatedInvitationDto>(it).toDomain()
        }

    /**
     * `GET /v1/rooms/{roomId}/invitations`. Not part of the frozen [PhotoDuelService]; exposed here so the
     * route is covered until the port gains it.
     */
    suspend fun invitations(roomId: String): AppResult<List<InvitationSummaryDto>> =
        api.execute(GET, listOf("rooms", roomId, "invitations")) { decode<InvitationListResponseDto>(it).invitations }

    override suspend fun revokeInvitation(roomId: String, invitationId: String): AppResult<Unit> =
        api.execute(DELETE, listOf("rooms", roomId, "invitations", invitationId)) { }

    override suspend fun previewInvitation(token: String): AppResult<InvitationPreview> =
        api.execute(GET, listOf("invitations", token), missingResource = ErrorCode.INVITE_UNAVAILABLE) {
            decode<InvitationPreviewDto>(it).toDomain()
        }

    override suspend fun joinWithInvitation(token: String): AppResult<RoomDetail> =
        api.execute(
            POST,
            listOf("invitations", token, "join"),
            body = emptyJson(),
            missingResource = ErrorCode.INVITE_UNAVAILABLE,
        ) { decode<RoomDetailDto>(it).toDomain() }

    // ---- Challenges (section 6) ----------------------------------------------------------------

    override suspend fun createChallenge(roomId: String, spec: ChallengeSpec): AppResult<Challenge> =
        api.execute(
            POST,
            listOf("rooms", roomId, "challenges"),
            body = json(CreateChallengeRequestDto(spec.promptId, spec.submissionHours, spec.votingHours)),
        ) { decode<ChallengeDto>(it).toDomain() }

    override suspend fun startChallenge(challengeId: String): AppResult<Challenge> =
        api.execute(POST, listOf("challenges", challengeId, "start"), body = emptyJson()) { decode<ChallengeDto>(it).toDomain() }

    override suspend fun cancelChallenge(challengeId: String): AppResult<Challenge> =
        api.execute(POST, listOf("challenges", challengeId, "cancel"), body = emptyJson()) { decode<ChallengeDto>(it).toDomain() }

    override suspend fun challenge(challengeId: String): AppResult<ChallengeView> =
        api.execute(GET, listOf("challenges", challengeId)) { decode<ChallengeViewDto>(it).toDomain() }

    override suspend fun roomHistory(roomId: String, cursor: String?): AppResult<Page<Challenge>> =
        api.execute(GET, listOf("rooms", roomId, "challenges"), query = pageQuery(cursor)) { text ->
            decode<PageDto<ChallengeDto>>(text).toDomain { it.toDomain() }
        }

    override suspend fun myHistory(cursor: String?): AppResult<Page<HistoryEntry>> =
        api.execute(GET, listOf("me", "history"), query = pageQuery(cursor)) { text ->
            decode<PageDto<HistoryEntryDto>>(text).toDomain { it.toDomain() }
        }

    // ---- Submissions, ballots, media (sections 7 and 8) ----------------------------------------

    /**
     * `PUT /v1/challenges/{challengeId}/submission` as `multipart/form-data` with the JPEG parts `full`
     * and `thumbnail`. [onProgress] receives strictly increasing fractions from 0 to 1 while the body is
     * written, on the OkHttp writer thread.
     */
    override suspend fun submitPhoto(
        challengeId: String,
        photo: PreparedPhoto,
        onProgress: (Float) -> Unit,
    ): AppResult<Submission> {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(PART_FULL, "$PART_FULL.jpg", photo.fullJpeg.toRequestBody(JPEG_MEDIA_TYPE))
            .addFormDataPart(PART_THUMBNAIL, "$PART_THUMBNAIL.jpg", photo.thumbnailJpeg.toRequestBody(JPEG_MEDIA_TYPE))
            .build()
        return api.execute(PUT, listOf("challenges", challengeId, "submission"), body = CountingRequestBody(multipart, onProgress)) {
            decode<SubmissionDto>(it).toDomain()
        }
    }

    override suspend fun withdrawSubmission(challengeId: String): AppResult<Unit> =
        api.execute(DELETE, listOf("challenges", challengeId, "submission")) { }

    override suspend fun deleteSubmission(submissionId: String): AppResult<Unit> =
        api.execute(DELETE, listOf("submissions", submissionId)) { }

    /** `201` (new ballot) and `200` (the same submission was already recorded) are both success. */
    override suspend fun castBallot(challengeId: String, submissionId: String): AppResult<Unit> =
        api.execute(POST, listOf("challenges", challengeId, "ballot"), body = json(BallotRequestDto(submissionId))) {
            decode<BallotResponseDto>(it)
            Unit
        }

    override fun mediaUrl(mediaId: String): String = api.url(listOf("media", mediaId)).toString()

    // ---- Safety (section 9) --------------------------------------------------------------------

    override suspend fun report(target: ReportTarget, reason: ReportReason, details: String?): AppResult<Unit> {
        val request = when (target) {
            is ReportTarget.Submission -> ReportRequestDto(TARGET_SUBMISSION, target.submissionId, reason.name, details)
            is ReportTarget.User -> ReportRequestDto(TARGET_USER, target.userId, reason.name, details)
        }
        return api.execute(POST, listOf("reports"), body = json(request)) { }
    }

    override suspend fun blockedUsers(): AppResult<List<BlockedUser>> =
        api.execute(GET, listOf("blocks")) { text -> decode<BlockedListDto>(text).blocked.map { it.toDomain() } }

    override suspend fun setBlocked(userId: String, blocked: Boolean): AppResult<Unit> =
        api.execute(if (blocked) PUT else DELETE, listOf("blocks", userId)) { }

    // ---- Health (section 1) --------------------------------------------------------------------

    /** `GET /v1/health`, without authentication. Not part of the frozen [PhotoDuelService]. */
    suspend fun health(): AppResult<HealthDto> =
        api.execute(GET, listOf("health"), authenticated = false) { decode<HealthDto>(it) }

    // ---- Helpers -------------------------------------------------------------------------------

    private suspend fun storeSessionOf(result: AppResult<Session>): AppResult<Session> {
        if (result !is AppResult.Success) return result
        return if (writeSession(result.value.token)) result else AppResult.Failure(ApiErrors.sessionStorage())
    }

    private suspend fun clearSessionAfter(result: AppResult<Unit>): AppResult<Unit> {
        if (result !is AppResult.Success) return result
        rotatedRecoveryCode = null
        return if (writeSession(null)) result else AppResult.Failure(ApiErrors.sessionStorage())
    }

    private suspend fun writeSession(token: String?): Boolean = try {
        sessionStore.write(token)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private fun pageQuery(cursor: String?): List<Pair<String, String>> =
        listOfNotNull(cursor?.let { "cursor" to it }, "limit" to PAGE_LIMIT.toString())

    private inline fun <reified T> json(value: T): RequestBody =
        RemoteJson.request.encodeToString(value).toRequestBody(JSON_MEDIA_TYPE)

    private fun emptyJson(): RequestBody = RemoteJson.EMPTY_OBJECT.toRequestBody(JSON_MEDIA_TYPE)

    private inline fun <reified T> decode(text: String): T = RemoteJson.response.decodeFromString(text)

    companion object {
        /** Page size the contract shows for both history routes. */
        const val PAGE_LIMIT = 20
        const val PART_FULL = "full"
        const val PART_THUMBNAIL = "thumbnail"
        private const val DELETE_CONFIRMATION = "DELETE"
        private const val TARGET_SUBMISSION = "SUBMISSION"
        private const val TARGET_USER = "USER"
    }
}
