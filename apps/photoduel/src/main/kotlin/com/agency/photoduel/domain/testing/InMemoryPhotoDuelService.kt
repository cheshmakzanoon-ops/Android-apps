package com.agency.photoduel.domain.testing

import com.agency.photoduel.domain.model.AppResult
import com.agency.photoduel.domain.model.BlockedUser
import com.agency.photoduel.domain.model.Challenge
import com.agency.photoduel.domain.model.ChallengeSpec
import com.agency.photoduel.domain.model.ChallengeState
import com.agency.photoduel.domain.model.ChallengeView
import com.agency.photoduel.domain.model.CreatedInvitation
import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.HistoryEntry
import com.agency.photoduel.domain.model.InvitationPreview
import com.agency.photoduel.domain.model.InvitationTtl
import com.agency.photoduel.domain.model.MemberRole
import com.agency.photoduel.domain.model.MyStatus
import com.agency.photoduel.domain.model.Page
import com.agency.photoduel.domain.model.PhotoDuelError
import com.agency.photoduel.domain.model.PreparedPhoto
import com.agency.photoduel.domain.model.PromptCatalog
import com.agency.photoduel.domain.model.RecoveryCode
import com.agency.photoduel.domain.model.ReportReason
import com.agency.photoduel.domain.model.ReportTarget
import com.agency.photoduel.domain.model.RoomDetail
import com.agency.photoduel.domain.model.RoomMember
import com.agency.photoduel.domain.model.RoomSummary
import com.agency.photoduel.domain.model.Session
import com.agency.photoduel.domain.model.Submission
import com.agency.photoduel.domain.model.SubmissionStatus
import com.agency.photoduel.domain.model.UserProfile
import com.agency.photoduel.domain.port.PhotoDuelService
import com.agency.photoduel.domain.port.ServerClock
import com.agency.photoduel.domain.rules.BallotAttempt
import com.agency.photoduel.domain.rules.BallotDecision
import com.agency.photoduel.domain.rules.ChallengeLifecycle
import com.agency.photoduel.domain.rules.ChallengeRules
import com.agency.photoduel.domain.rules.DomainRefusal
import com.agency.photoduel.domain.rules.Durations
import com.agency.photoduel.domain.rules.Eligibility
import com.agency.photoduel.domain.rules.InvitationRoomState
import com.agency.photoduel.domain.rules.Invitations
import com.agency.photoduel.domain.rules.JoinDecision
import com.agency.photoduel.domain.rules.RosterRules
import com.agency.photoduel.domain.rules.RuleResult
import com.agency.photoduel.domain.rules.SubmissionRef
import com.agency.photoduel.domain.rules.Validation
import com.agency.photoduel.domain.rules.toError

/**
 * In-memory [PhotoDuelService] that applies the same pure rules as the server (domain/rules). It is the
 * fake used by UI and journey tests and must never be wired into production.
 *
 * One instance is one signed-in client. Create more clients on the same [backend] with [newClient] to
 * simulate several friends. Time comes only from the backend's [ServerClock] (use [FakeServerClock]);
 * ids come from a counter; media URLs have the form `memory://media/<id>`.
 *
 * Deliberate simplifications, documented so nobody mistakes them for server behaviour:
 * - photos are stored as given; there is no decoding, re-encoding or magic-byte sniffing, only the size
 *   and dimension limits;
 * - guest creation and upload quotas per IP or per day are not modelled (room and report quotas are);
 * - photos due for purge are dropped at once instead of "within 24 hours".
 */
class InMemoryPhotoDuelService(val backend: InMemoryBackend) : PhotoDuelService {

    constructor(clock: ServerClock, catalog: PromptCatalog = InMemoryBackend.DEFAULT_CATALOG) :
        this(InMemoryBackend(clock, catalog))

    /** Bearer token of this client's session, or null when signed out. */
    @Volatile
    var sessionToken: String? = null
        private set

    /**
     * The code issued when [restoreWithRecoveryCode] rotated the old one. The frozen contract returns only
     * a [Session] from restore, so the rotated code is exposed here for tests (see the change request in
     * the domain report).
     */
    @Volatile
    var lastRotatedRecoveryCode: RecoveryCode? = null
        private set

    /** A new signed-out client sharing this client's backend (another friend's device). */
    fun newClient(): InMemoryPhotoDuelService = InMemoryPhotoDuelService(backend)

    /** Signs this client in with an existing token (for example one created by another client). */
    fun useSessionToken(token: String?) {
        sessionToken = token
    }

    // ---- Identity ---------------------------------------------------------------------------------

    override suspend fun createGuest(displayName: String): AppResult<Session> = locked {
        val name = when (val checked = Validation.displayName(displayName)) {
            is RuleResult.Ok -> checked.value
            is RuleResult.Refused -> return@locked fail(checked.reason)
        }
        val user = UserRecord(backend.nextId("user"), name)
        backend.users[user.id] = user
        val session = backend.openSession(user)
        sessionToken = session.token
        AppResult.Success(Session(profile(user), session.token, session.expiresAtEpochMillis))
    }

    override suspend fun restoreWithRecoveryCode(code: String): AppResult<Session> = locked {
        val canonical = when (val checked = Validation.recoveryCode(code)) {
            is RuleResult.Ok -> checked.value
            is RuleResult.Refused -> return@locked fail(checked.reason)
        }
        val user = backend.users.values.firstOrNull { !it.deleted && it.recoveryCode == canonical }
            ?: return@locked fail(ErrorCode.UNAUTHENTICATED, "INVALID_RECOVERY_CODE: The recovery code is not valid.")
        val rotated = backend.nextRecoveryCode()
        user.recoveryCode = rotated
        lastRotatedRecoveryCode = RecoveryCode(Validation.formatRecoveryCode(rotated))
        val session = backend.openSession(user)
        sessionToken = session.token
        AppResult.Success(Session(profile(user), session.token, session.expiresAtEpochMillis))
    }

    override suspend fun issueRecoveryCode(): AppResult<RecoveryCode> = authed { user ->
        val code = backend.nextRecoveryCode()
        user.recoveryCode = code
        AppResult.Success(RecoveryCode(Validation.formatRecoveryCode(code)))
    }

    override suspend fun currentUser(): AppResult<UserProfile> = authed { user -> AppResult.Success(profile(user)) }

    override suspend fun renameSelf(displayName: String): AppResult<UserProfile> = authed { user ->
        when (val checked = Validation.displayName(displayName)) {
            is RuleResult.Refused -> fail(checked.reason)
            is RuleResult.Ok -> {
                user.displayName = checked.value
                AppResult.Success(profile(user))
            }
        }
    }

    override suspend fun logout(): AppResult<Unit> = authed { _ ->
        backend.sessions[sessionToken]?.revoked = true
        sessionToken = null
        AppResult.Success(Unit)
    }

    /**
     * Deletes the account (architecture section 11): sessions revoked, photos purged (submissions become
     * REMOVED with no author), memberships removed, ballots kept without voter identity, blocks removed.
     *
     * Rooms the user owns are deleted with the account. The contract does not say what happens to them;
     * deleting is the reading that never leaves a room without an accountable owner.
     */
    override suspend fun deleteAccount(): AppResult<Unit> = authed { user ->
        backend.sessions.values.filter { it.userId == user.id }.forEach { it.revoked = true }
        for (room in backend.rooms.values) {
            if (room.ownerUserId == user.id && !room.deleted) {
                room.deleted = true
                backend.purgeRoomMedia(room.id)
            }
            room.members.remove(user.id)
        }
        for (submission in backend.submissions.values) {
            if (submission.authorUserId != user.id) continue
            backend.purgeMedia(submission)
            submission.status = SubmissionStatus.REMOVED
            submission.authorUserId = null
        }
        backend.ballots.filter { it.voterUserId == user.id }.forEach { it.voterUserId = null }
        backend.blockEdges.removeAll { it.first == user.id || it.second == user.id }
        user.deleted = true
        user.displayName = ""
        user.recoveryCode = null
        sessionToken = null
        AppResult.Success(Unit)
    }

    // ---- Catalogue --------------------------------------------------------------------------------

    override suspend fun prompts(): AppResult<PromptCatalog> = authed { _ -> AppResult.Success(backend.catalog) }

    // ---- Rooms and invitations --------------------------------------------------------------------

    override suspend fun myRooms(): AppResult<List<RoomSummary>> = authed { user ->
        val summaries = backend.rooms.values
            .filter { !it.deleted && user.id in it.members }
            .map { room ->
                RoomSummary(
                    id = room.id,
                    name = room.name,
                    memberCount = room.members.size,
                    capacity = room.capacity,
                    activeChallengeState = backend.challengeStatesOf(room.id)
                        .firstOrNull { it in ChallengeRules.ACTIVE_STATES },
                )
            }
        AppResult.Success(summaries)
    }

    override suspend fun createRoom(name: String): AppResult<RoomDetail> = authed { user ->
        val roomName = when (val checked = Validation.roomName(name)) {
            is RuleResult.Ok -> checked.value
            is RuleResult.Refused -> return@authed fail(checked.reason)
        }
        val now = backend.now()
        val dayStart = Math.floorDiv(now, Durations.DAY_MILLIS) * Durations.DAY_MILLIS
        val createdToday = backend.roomCreations.count { (creator, at) -> creator == user.id && at >= dayStart }
        if (createdToday >= backend.limits.roomsPerUserPerDay) {
            return@authed rateLimited(dayStart + Durations.DAY_MILLIS - now, "Room quota reached for today.")
        }
        val capacity = when (val checked = RosterRules.checkCapacity(RosterRules.DEFAULT_CAPACITY)) {
            is RuleResult.Ok -> checked.value
            is RuleResult.Refused -> return@authed fail(checked.reason)
        }
        val room = RoomRecord(backend.nextId("room"), roomName, user.id, capacity)
        room.members[user.id] = MemberRole.OWNER
        backend.rooms[room.id] = room
        backend.roomCreations += user.id to now
        AppResult.Success(roomDetail(room, user))
    }

    override suspend fun room(roomId: String): AppResult<RoomDetail> = authed { user ->
        val room = backend.visibleRoom(user.id, roomId) ?: return@authed notFound()
        AppResult.Success(roomDetail(room, user))
    }

    override suspend fun leaveRoom(roomId: String): AppResult<Unit> = authed { user ->
        val room = backend.visibleRoom(user.id, roomId) ?: return@authed notFound()
        val role = room.members.getValue(user.id)
        when (val checked = RosterRules.checkLeave(role, backend.challengeStatesOf(room.id))) {
            is RuleResult.Refused -> fail(checked.reason)
            is RuleResult.Ok -> {
                room.members.remove(user.id)
                AppResult.Success(Unit)
            }
        }
    }

    override suspend fun removeMember(roomId: String, userId: String): AppResult<Unit> = authed { user ->
        val room = backend.visibleRoom(user.id, roomId) ?: return@authed notFound()
        val actorRole = room.members.getValue(user.id)
        val targetRole = room.members[userId]
        when (val checked = RosterRules.checkRemove(actorRole, targetRole, backend.challengeStatesOf(room.id))) {
            is RuleResult.Refused -> fail(checked.reason)
            is RuleResult.Ok -> {
                room.members.remove(userId)
                AppResult.Success(Unit)
            }
        }
    }

    override suspend fun deleteRoom(roomId: String): AppResult<Unit> = authed { user ->
        val room = backend.visibleRoom(user.id, roomId) ?: return@authed notFound()
        if (room.ownerUserId != user.id) return@authed fail(ErrorCode.FORBIDDEN, "Only the room owner can delete the room.")
        room.deleted = true
        backend.purgeRoomMedia(room.id)
        AppResult.Success(Unit)
    }

    override suspend fun createInvitation(roomId: String, ttl: InvitationTtl): AppResult<CreatedInvitation> = authed { user ->
        val room = backend.visibleRoom(user.id, roomId) ?: return@authed notFound()
        val now = backend.now()
        val active = backend.invitations.values.count {
            it.roomId == room.id && Invitations.isActive(backend.invitationState(it), now)
        }
        when (val checked = Invitations.checkCreate(room.members.size, room.capacity, active)) {
            is RuleResult.Refused -> return@authed fail(checked.reason)
            is RuleResult.Ok -> Unit
        }
        val invitation = InvitationRecord(
            id = backend.nextId("invitation"),
            roomId = room.id,
            token = backend.nextId("invite-token"),
            createdByUserId = user.id,
            expiresAtEpochMillis = Invitations.expiresAt(now, ttl),
            revoked = false,
            usesRemaining = Invitations.initialUses(room.capacity),
        )
        backend.invitations[invitation.id] = invitation
        AppResult.Success(CreatedInvitation(invitation.id, invitation.token, invitation.expiresAtEpochMillis))
    }

    override suspend fun revokeInvitation(roomId: String, invitationId: String): AppResult<Unit> = authed { user ->
        val room = backend.visibleRoom(user.id, roomId) ?: return@authed notFound()
        val invitation = backend.invitations[invitationId]?.takeIf { it.roomId == room.id }
            ?: return@authed notFound()
        val role = room.members.getValue(user.id)
        when (val checked = Invitations.checkRevoke(backend.invitationState(invitation), user.id, role)) {
            is RuleResult.Refused -> fail(checked.reason)
            is RuleResult.Ok -> {
                invitation.revoked = true
                AppResult.Success(Unit)
            }
        }
    }

    override suspend fun previewInvitation(token: String): AppResult<InvitationPreview> = authed { user ->
        val invitation = backend.invitations.values.firstOrNull { it.token == token }
        val room = invitation?.let { backend.rooms[it.roomId] }
        when (val checked = Invitations.checkPreview(invitation?.let(backend::invitationState), room?.let { roomState(it, user) }, backend.now())) {
            is RuleResult.Refused -> fail(checked.reason)
            is RuleResult.Ok -> AppResult.Success(
                InvitationPreview(
                    roomName = room!!.name,
                    ownerDisplayName = backend.displayNameOf(room.ownerUserId),
                    memberCount = room.members.size,
                    capacity = room.capacity,
                    expiresAtEpochMillis = invitation!!.expiresAtEpochMillis,
                ),
            )
        }
    }

    override suspend fun joinWithInvitation(token: String): AppResult<RoomDetail> = authed { user ->
        val invitation = backend.invitations.values.firstOrNull { it.token == token }
        val room = invitation?.let { backend.rooms[it.roomId] }
        when (val checked = Invitations.checkJoin(invitation?.let(backend::invitationState), room?.let { roomState(it, user) }, backend.now())) {
            is RuleResult.Refused -> fail(checked.reason)
            is RuleResult.Ok -> {
                if (checked.value == JoinDecision.JOIN_AS_NEW_MEMBER) {
                    room!!.members[user.id] = MemberRole.MEMBER
                    invitation!!.usesRemaining -= 1
                }
                AppResult.Success(roomDetail(room!!, user))
            }
        }
    }

    // ---- Challenges -------------------------------------------------------------------------------

    override suspend fun createChallenge(roomId: String, spec: ChallengeSpec): AppResult<Challenge> = authed { user ->
        val room = backend.visibleRoom(user.id, roomId) ?: return@authed notFound()
        if (room.ownerUserId != user.id) return@authed fail(ErrorCode.FORBIDDEN, "Only the room owner can create a challenge.")
        val valid = when (val checked = Validation.challengeSpec(spec, backend.catalog)) {
            is RuleResult.Ok -> checked.value
            is RuleResult.Refused -> return@authed fail(checked.reason)
        }
        val existing = backend.challengesOf(room.id)
        when (val checked = RosterRules.checkNewChallenge(existing.map { it.lifecycle.state })) {
            is RuleResult.Refused -> return@authed fail(checked.reason)
            is RuleResult.Ok -> Unit
        }
        val challenge = ChallengeRecord(
            id = backend.nextId("challenge"),
            roomId = room.id,
            roundNumber = existing.size + 1,
            prompt = backend.catalog.prompts.first { it.id == valid.promptId },
            lifecycle = ChallengeLifecycle.created(valid),
        )
        backend.challenges[challenge.id] = challenge
        AppResult.Success(challengeModel(challenge))
    }

    override suspend fun startChallenge(challengeId: String): AppResult<Challenge> = authed { user ->
        val (challenge, room) = visibleChallenge(user.id, challengeId) ?: return@authed notFound()
        if (room.ownerUserId != user.id) return@authed fail(ErrorCode.FORBIDDEN, "Only the room owner can start the challenge.")
        when (val checked = ChallengeRules.start(challenge.lifecycle, room.members.size, backend.now())) {
            is RuleResult.Refused -> fail(checked.reason)
            is RuleResult.Ok -> {
                challenge.lifecycle = checked.value
                challenge.roster = room.members.keys.toList()
                challenge.rosterRoles = LinkedHashMap(room.members)
                AppResult.Success(challengeModel(challenge))
            }
        }
    }

    override suspend fun cancelChallenge(challengeId: String): AppResult<Challenge> = authed { user ->
        val (challenge, room) = visibleChallenge(user.id, challengeId) ?: return@authed notFound()
        if (room.ownerUserId != user.id) return@authed fail(ErrorCode.FORBIDDEN, "Only the room owner can cancel the challenge.")
        when (val checked = ChallengeRules.cancel(challenge.lifecycle, backend.now())) {
            is RuleResult.Refused -> fail(checked.reason)
            is RuleResult.Ok -> {
                challenge.lifecycle = checked.value
                backend.purgeChallengeMedia(challenge.id)
                AppResult.Success(challengeModel(challenge))
            }
        }
    }

    override suspend fun challenge(challengeId: String): AppResult<ChallengeView> = authed { user ->
        val (challenge, room) = visibleChallenge(user.id, challengeId) ?: return@authed notFound()
        AppResult.Success(challengeView(challenge, room, user.id))
    }

    override suspend fun roomHistory(roomId: String, cursor: String?): AppResult<Page<Challenge>> = authed { user ->
        val room = backend.visibleRoom(user.id, roomId) ?: return@authed notFound()
        val newestFirst = backend.challengesOf(room.id).asReversed()
        page(newestFirst, cursor) { challengeModel(it) }
    }

    /**
     * Challenges the caller played (was on the frozen roster) in rooms the caller still belongs to,
     * newest first. Rooms the caller has left, and challenges that never started, are excluded.
     */
    override suspend fun myHistory(cursor: String?): AppResult<Page<HistoryEntry>> = authed { user ->
        val entries = backend.challenges.values
            .filter { challenge ->
                val room = backend.rooms.getValue(challenge.roomId)
                !room.deleted && user.id in room.members && user.id in challenge.roster
            }
            .reversed()
        page(entries, cursor) { challenge ->
            HistoryEntry(
                challenge = challengeModel(challenge),
                roomName = backend.rooms.getValue(challenge.roomId).name,
                mySubmissionId = ownSubmission(challenge.id, user.id)?.id,
                result = resultOf(challenge),
            )
        }
    }

    // ---- Submissions, ballots, media ---------------------------------------------------------------

    override suspend fun submitPhoto(
        challengeId: String,
        photo: PreparedPhoto,
        onProgress: (Float) -> Unit,
    ): AppResult<Submission> {
        onProgress(0f)
        val result = authed { user ->
            val (challenge, _) = visibleChallenge(user.id, challengeId) ?: return@authed notFound()
            photoProblem(photo)?.let { return@authed it }
            val lifecycle = challenge.lifecycle
            val eligibility = Eligibility.checkSubmission(
                userId = user.id,
                roster = challenge.roster.toSet(),
                state = lifecycle.state,
                submissionDeadlineEpochMillis = lifecycle.submissionDeadlineEpochMillis,
                nowEpochMillis = backend.now(),
            )
            if (eligibility is RuleResult.Refused) return@authed fail(eligibility.reason)

            val thumbnailId = backend.nextId("media")
            val fullId = backend.nextId("media")
            backend.media[thumbnailId] = photo.thumbnailJpeg.copyOf()
            backend.media[fullId] = photo.fullJpeg.copyOf()
            val existing = backend.submissionsOf(challenge.id).firstOrNull { it.authorUserId == user.id }
            val record = if (existing != null) {
                // Replacement (or resubmission after a withdrawal): same row, new media, old media purged.
                backend.purgeMedia(existing)
                existing.status = SubmissionStatus.ACTIVE
                existing.thumbnailMediaId = thumbnailId
                existing.fullMediaId = fullId
                existing.width = photo.width
                existing.height = photo.height
                existing
            } else {
                SubmissionRecord(
                    id = backend.nextId("submission"),
                    challengeId = challenge.id,
                    authorUserId = user.id,
                    status = SubmissionStatus.ACTIVE,
                    thumbnailMediaId = thumbnailId,
                    fullMediaId = fullId,
                    width = photo.width,
                    height = photo.height,
                ).also { backend.submissions[it.id] = it }
            }
            AppResult.Success(submissionModel(record, user.id))
        }
        if (result is AppResult.Success) onProgress(1f)
        return result
    }

    override suspend fun withdrawSubmission(challengeId: String): AppResult<Unit> = authed { user ->
        val (challenge, _) = visibleChallenge(user.id, challengeId) ?: return@authed notFound()
        val own = backend.submissionsOf(challenge.id)
            .firstOrNull { it.authorUserId == user.id && it.status == SubmissionStatus.ACTIVE }
        val lifecycle = challenge.lifecycle
        val checked = Eligibility.checkWithdrawal(
            userId = user.id,
            roster = challenge.roster.toSet(),
            state = lifecycle.state,
            submissionDeadlineEpochMillis = lifecycle.submissionDeadlineEpochMillis,
            nowEpochMillis = backend.now(),
            hasActiveSubmission = own != null,
        )
        if (checked is RuleResult.Refused) return@authed fail(checked.reason)
        backend.purgeMedia(own!!)
        own.status = SubmissionStatus.WITHDRAWN
        AppResult.Success(Unit)
    }

    /** Author deletes a photo after the challenge: COMPLETED or ARCHIVED only; the tally is unchanged. */
    override suspend fun deleteSubmission(submissionId: String): AppResult<Unit> = authed { user ->
        val submission = backend.submissions[submissionId]?.takeIf { it.authorUserId == user.id }
            ?: return@authed notFound()
        val state = backend.challenges.getValue(submission.challengeId).lifecycle.state
        if (state != ChallengeState.COMPLETED && state != ChallengeState.ARCHIVED) {
            return@authed fail(ErrorCode.INVALID_TRANSITION, "Photos can be deleted only after the challenge has completed.")
        }
        backend.purgeMedia(submission)
        submission.status = SubmissionStatus.REMOVED
        AppResult.Success(Unit)
    }

    override suspend fun castBallot(challengeId: String, submissionId: String): AppResult<Unit> = authed { user ->
        val (challenge, room) = visibleChallenge(user.id, challengeId) ?: return@authed notFound()
        val target = backend.submissions[submissionId]?.takeIf { it.challengeId == challenge.id }
        val now = backend.now()
        val attempt = BallotAttempt(
            voterUserId = user.id,
            roster = challenge.roster.toSet(),
            currentMembers = room.members.keys.toSet(),
            state = challenge.lifecycle.state,
            votingDeadlineEpochMillis = challenge.lifecycle.votingDeadlineEpochMillis,
            nowEpochMillis = now,
            target = target?.let { SubmissionRef(it.id, it.authorUserId, it.status) },
            blocks = backend.blockGraph(),
            existingBallotSubmissionId = backend.ballotsOf(challenge.id).firstOrNull { it.voterUserId == user.id }?.submissionId,
        )
        when (val checked = Eligibility.checkBallot(attempt)) {
            is RuleResult.Refused -> fail(checked.reason)
            is RuleResult.Ok -> {
                if (checked.value == BallotDecision.RECORD_NEW) {
                    backend.ballots += BallotRecord(challenge.id, user.id, submissionId, now)
                }
                AppResult.Success(Unit)
            }
        }
    }

    override fun mediaUrl(mediaId: String): String = "$MEDIA_URL_PREFIX$mediaId"

    // ---- Safety -----------------------------------------------------------------------------------

    override suspend fun report(target: ReportTarget, reason: ReportReason, details: String?): AppResult<Unit> = authed { user ->
        if (details != null && details.codePointCount(0, details.length) > backend.limits.reportDetailsMaxLength) {
            return@authed fail(ErrorCode.VALIDATION, "details must be at most ${backend.limits.reportDetailsMaxLength} characters.")
        }
        val targetKey: String
        var reportedSubmission: SubmissionRecord? = null
        when (target) {
            is ReportTarget.Submission -> {
                val submission = backend.submissions[target.submissionId] ?: return@authed notFound()
                val (challenge, _) = visibleChallenge(user.id, submission.challengeId) ?: return@authed notFound()
                val visible = Eligibility.visibleSubmissions(
                    viewerUserId = user.id,
                    state = challenge.lifecycle.state,
                    submissions = listOf(SubmissionRef(submission.id, submission.authorUserId, submission.status)),
                    blocks = backend.blockGraph(),
                )
                if (visible.isEmpty()) return@authed notFound()
                if (submission.authorUserId == user.id) return@authed fail(ErrorCode.VALIDATION, "You cannot report your own photo.")
                targetKey = "SUBMISSION:${submission.id}"
                reportedSubmission = submission
            }
            is ReportTarget.User -> {
                if (target.userId == user.id) return@authed fail(ErrorCode.VALIDATION, "You cannot report yourself.")
                val other = backend.users[target.userId]?.takeIf { !it.deleted } ?: return@authed notFound()
                if (!backend.sharesRoom(user.id, other.id)) return@authed notFound()
                targetKey = "USER:${other.id}"
            }
        }
        // One open report per reporter and target: a repeat is an idempotent success.
        if (backend.reports.any { it.reporterUserId == user.id && it.targetKey == targetKey }) {
            return@authed AppResult.Success(Unit)
        }
        val now = backend.now()
        val dayStart = Math.floorDiv(now, Durations.DAY_MILLIS) * Durations.DAY_MILLIS
        val today = backend.reports.count { it.reporterUserId == user.id && it.createdAtEpochMillis >= dayStart }
        if (today >= backend.limits.reportsPerUserPerDay) {
            return@authed rateLimited(dayStart + Durations.DAY_MILLIS - now, "Report quota reached for today.")
        }
        backend.reports += ReportRecord(user.id, targetKey, reason, details, now)
        val submission = reportedSubmission
        if (submission != null && submission.status == SubmissionStatus.ACTIVE) {
            val reporters = backend.reports.filter { it.targetKey == targetKey }.map { it.reporterUserId }.toSet()
            if (reporters.size >= backend.limits.autoHideDistinctReporters) submission.status = SubmissionStatus.HIDDEN
        }
        AppResult.Success(Unit)
    }

    override suspend fun blockedUsers(): AppResult<List<BlockedUser>> = authed { user ->
        val blocked = backend.blockEdges
            .filter { it.first == user.id }
            .mapNotNull { (_, blockedId) -> backend.users[blockedId]?.takeIf { !it.deleted } }
            .map { BlockedUser(it.id, it.displayName) }
        AppResult.Success(blocked)
    }

    override suspend fun setBlocked(userId: String, blocked: Boolean): AppResult<Unit> = authed { user ->
        if (userId == user.id) return@authed fail(ErrorCode.VALIDATION, "You cannot block yourself.")
        if (!blocked) {
            backend.blockEdges.remove(user.id to userId)
            return@authed AppResult.Success(Unit)
        }
        val other = backend.users[userId]?.takeIf { !it.deleted } ?: return@authed notFound()
        if (!backend.sharesRoom(user.id, other.id)) return@authed notFound()
        backend.blockEdges += user.id to other.id
        AppResult.Success(Unit)
    }

    // ---- Helpers ----------------------------------------------------------------------------------

    /** Runs [block] under the backend lock after catching every challenge up to the current time. */
    private inline fun <T> locked(block: () -> AppResult<T>): AppResult<T> = synchronized(backend.lock) {
        backend.advanceAll()
        block()
    }

    /** Like [locked], but first resolves the session; an absent or invalid session is UNAUTHENTICATED. */
    private inline fun <T> authed(block: (UserRecord) -> AppResult<T>): AppResult<T> = locked {
        val user = backend.authenticate(sessionToken)
            ?: return@locked fail(ErrorCode.UNAUTHENTICATED, "Sign in again to continue.")
        block(user)
    }

    private fun visibleChallenge(userId: String, challengeId: String): Pair<ChallengeRecord, RoomRecord>? {
        val challenge = backend.challenges[challengeId] ?: return null
        val room = backend.visibleRoom(userId, challenge.roomId) ?: return null
        return challenge to room
    }

    private fun roomState(room: RoomRecord, caller: UserRecord) = InvitationRoomState(
        deleted = room.deleted,
        memberCount = room.members.size,
        capacity = room.capacity,
        callerIsMember = caller.id in room.members,
    )

    private fun photoProblem(photo: PreparedPhoto): AppResult.Failure? {
        val limits = backend.limits
        val total = photo.fullJpeg.size.toLong() + photo.thumbnailJpeg.size.toLong()
        if (total > limits.uploadBodyMaxBytes || photo.fullJpeg.size > limits.fullImageMaxBytes ||
            photo.thumbnailJpeg.size > limits.thumbnailMaxBytes
        ) {
            return fail(ErrorCode.PAYLOAD_TOO_LARGE, "The photo is too large.")
        }
        if (photo.fullJpeg.isEmpty() || photo.thumbnailJpeg.isEmpty() || photo.width <= 0 || photo.height <= 0 ||
            minOf(photo.width, photo.height) < limits.minShortEdgePixels ||
            photo.width.toLong() * photo.height.toLong() > limits.maxPixels
        ) {
            return fail(ErrorCode.UNSUPPORTED_MEDIA, "This photo cannot be used.")
        }
        return null
    }

    private fun profile(user: UserRecord) = UserProfile(user.id, user.displayName, user.recoveryCode != null)

    private fun roomDetail(room: RoomRecord, viewer: UserRecord) = RoomDetail(
        id = room.id,
        name = room.name,
        capacity = room.capacity,
        members = room.members.map { (id, role) -> RoomMember(id, backend.displayNameOf(id), role) },
        viewerRole = room.members.getValue(viewer.id),
        viewerUserId = viewer.id,
    )

    private fun challengeModel(challenge: ChallengeRecord): Challenge {
        val lifecycle = challenge.lifecycle
        return Challenge(
            id = challenge.id,
            roomId = challenge.roomId,
            roundNumber = challenge.roundNumber,
            prompt = challenge.prompt,
            state = lifecycle.state,
            outcome = lifecycle.outcome,
            submissionDeadlineEpochMillis = lifecycle.submissionDeadlineEpochMillis,
            votingDeadlineEpochMillis = lifecycle.votingDeadlineEpochMillis,
            serverTimeEpochMillis = backend.now(),
        )
    }

    private fun resultOf(challenge: ChallengeRecord) = when (challenge.lifecycle.state) {
        ChallengeState.COMPLETED, ChallengeState.ARCHIVED -> challenge.lifecycle.result
        else -> null
    }

    private fun ownSubmission(challengeId: String, userId: String): SubmissionRecord? =
        backend.submissionsOf(challengeId)
            .firstOrNull { it.authorUserId == userId && it.status != SubmissionStatus.WITHDRAWN }

    private fun submissionModel(record: SubmissionRecord, viewerId: String) = Submission(
        id = record.id,
        challengeId = record.challengeId,
        authorUserId = record.authorUserId,
        authorDisplayName = backend.displayNameOf(record.authorUserId),
        status = record.status,
        thumbnailMediaId = record.thumbnailMediaId,
        fullMediaId = record.fullMediaId,
        width = record.width,
        height = record.height,
        isMine = record.authorUserId == viewerId,
    )

    private fun challengeView(challenge: ChallengeRecord, room: RoomRecord, viewerId: String): ChallengeView {
        val lifecycle = challenge.lifecycle
        val now = backend.now()
        // Before start the prospective roster is the current membership; afterwards it is frozen.
        val roster: List<RoomMember> = if (lifecycle.state == ChallengeState.CREATED) {
            room.members.map { (id, role) -> RoomMember(id, backend.displayNameOf(id), role) }
        } else {
            challenge.roster.map { id ->
                RoomMember(id, backend.displayNameOf(id), challenge.rosterRoles[id] ?: MemberRole.MEMBER)
            }
        }
        val rosterIds = roster.map { it.userId }.toSet()
        val records = backend.submissionsOf(challenge.id)
        val visibleIds = Eligibility.visibleSubmissions(
            viewerUserId = viewerId,
            state = lifecycle.state,
            submissions = records.map { SubmissionRef(it.id, it.authorUserId, it.status) },
            blocks = backend.blockGraph(),
        ).map { it.id }.toSet()
        val myBallot = backend.ballotsOf(challenge.id).firstOrNull { it.voterUserId == viewerId }
        val isRosterMember = viewerId in rosterIds
        val canSubmit = Eligibility.checkSubmission(
            userId = viewerId,
            roster = challenge.roster.toSet(),
            state = lifecycle.state,
            submissionDeadlineEpochMillis = lifecycle.submissionDeadlineEpochMillis,
            nowEpochMillis = now,
        ) is RuleResult.Ok
        val canVote = viewerId in challenge.roster && viewerId in room.members &&
            ChallengeRules.isVotingWindowOpen(lifecycle, now) && myBallot == null
        return ChallengeView(
            challenge = challengeModel(challenge),
            roster = roster,
            submissions = records.filter { it.id in visibleIds }.map { submissionModel(it, viewerId) },
            myStatus = MyStatus(
                isRosterMember = isRosterMember,
                mySubmissionId = ownSubmission(challenge.id, viewerId)?.id,
                myBallotSubmissionId = myBallot?.submissionId,
                canSubmit = canSubmit,
                canVote = canVote,
            ),
            result = resultOf(challenge),
        )
    }

    private inline fun <E, T> page(items: List<E>, cursor: String?, map: (E) -> T): AppResult<Page<T>> {
        val offset = if (cursor == null) 0 else cursor.toIntOrNull()?.takeIf { it >= 0 }
            ?: return fail(ErrorCode.VALIDATION, "cursor is not valid.")
        val size = backend.limits.pageSize
        val slice = items.drop(offset).take(size).map(map)
        val next = if (offset + size < items.size) (offset + size).toString() else null
        return AppResult.Success(Page(slice, next))
    }

    private fun fail(refusal: DomainRefusal): AppResult.Failure = AppResult.Failure(refusal.toError())

    private fun fail(code: ErrorCode, message: String): AppResult.Failure =
        AppResult.Failure(PhotoDuelError(code = code, message = message, retryable = false))

    private fun notFound(): AppResult.Failure = fail(ErrorCode.NOT_FOUND, "Not found.")

    private fun rateLimited(retryAfterMillis: Long, message: String): AppResult.Failure =
        AppResult.Failure(
            PhotoDuelError(
                code = ErrorCode.RATE_LIMITED,
                message = message,
                retryable = true,
                retryAfterSeconds = ((retryAfterMillis + 999) / 1000).toInt(),
            ),
        )

    companion object {
        const val MEDIA_URL_PREFIX = "memory://media/"
    }
}
