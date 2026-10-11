package com.agency.photoduel.domain.testing

import com.agency.photoduel.domain.model.ChallengeState
import com.agency.photoduel.domain.model.MemberRole
import com.agency.photoduel.domain.model.Prompt
import com.agency.photoduel.domain.model.PromptCatalog
import com.agency.photoduel.domain.model.ReportReason
import com.agency.photoduel.domain.model.SubmissionStatus
import com.agency.photoduel.domain.port.ServerClock
import com.agency.photoduel.domain.rules.BallotRef
import com.agency.photoduel.domain.rules.BlockGraph
import com.agency.photoduel.domain.rules.ChallengeEvent
import com.agency.photoduel.domain.rules.ChallengeLifecycle
import com.agency.photoduel.domain.rules.ChallengeRules
import com.agency.photoduel.domain.rules.Durations
import com.agency.photoduel.domain.rules.InvitationState
import com.agency.photoduel.domain.rules.SubmissionRef
import com.agency.photoduel.domain.rules.TallyInput
import com.agency.photoduel.domain.rules.Validation

/** Server constants used by the in-memory backend (architecture section 10). */
data class InMemoryLimits(
    val roomsPerUserPerDay: Int = 5,
    val reportsPerUserPerDay: Int = 20,
    val reportDetailsMaxLength: Int = 280,
    val autoHideDistinctReporters: Int = 3,
    val sessionLifetimeMillis: Long = Durations.days(90),
    val uploadBodyMaxBytes: Int = 3 * 1024 * 1024,
    val fullImageMaxBytes: Int = 2 * 1024 * 1024,
    val thumbnailMaxBytes: Int = 300 * 1024,
    val minShortEdgePixels: Int = 200,
    val maxPixels: Long = 40_000_000L,
    val pageSize: Int = 20,
)

/**
 * Shared state of the in-memory PhotoDuel server. Several [InMemoryPhotoDuelService] clients (one per
 * simulated account or device) can share one backend, which is how multi-account tests are written.
 *
 * Determinism: ids and tokens come from one counter, recovery codes from a seeded generator, and time
 * only from [clock]. All access is serialized on [lock]. Every service call first catches every
 * challenge up to the current clock value with [ChallengeRules.advance], exactly like the server's lazy
 * per-request evaluation.
 *
 * Test-only. Never wire it into the production composition root.
 */
class InMemoryBackend(
    val clock: ServerClock,
    val catalog: PromptCatalog = DEFAULT_CATALOG,
    val limits: InMemoryLimits = InMemoryLimits(),
    recoveryCodeSeed: Long = DEFAULT_RECOVERY_CODE_SEED,
) {
    internal val lock = Any()

    internal val users = LinkedHashMap<String, UserRecord>()
    internal val sessions = LinkedHashMap<String, SessionRecord>()
    internal val rooms = LinkedHashMap<String, RoomRecord>()
    internal val invitations = LinkedHashMap<String, InvitationRecord>()
    internal val challenges = LinkedHashMap<String, ChallengeRecord>()
    internal val submissions = LinkedHashMap<String, SubmissionRecord>()
    internal val ballots = ArrayList<BallotRecord>()
    internal val blockEdges = LinkedHashSet<Pair<String, String>>()
    internal val reports = ArrayList<ReportRecord>()
    internal val roomCreations = ArrayList<Pair<String, Long>>()
    internal val media = LinkedHashMap<String, ByteArray>()

    private var sequence = 0L
    private val recoveryCodeRandom = java.util.Random(recoveryCodeSeed)

    internal fun now(): Long = clock.nowEpochMillis()

    internal fun nextId(prefix: String): String = "$prefix-${++sequence}"

    /** A fresh canonical (16 symbols, no hyphens) Crockford base32 recovery code, unique among users. */
    internal fun nextRecoveryCode(): String {
        while (true) {
            val code = buildString(Validation.RECOVERY_CODE_LENGTH) {
                repeat(Validation.RECOVERY_CODE_LENGTH) {
                    append(Validation.CROCKFORD_ALPHABET[recoveryCodeRandom.nextInt(Validation.CROCKFORD_ALPHABET.length)])
                }
            }
            if (users.values.none { it.recoveryCode == code }) return code
        }
    }

    /** True while the bytes of [mediaId] are stored (not yet purged). For tests. */
    fun isMediaStored(mediaId: String): Boolean = synchronized(lock) { mediaId in media }

    // ---- Authentication -------------------------------------------------------------------------

    /** Resolves a bearer token to its user, extending the session (90 days after last use). */
    internal fun authenticate(token: String?): UserRecord? {
        val session = token?.let { sessions[it] } ?: return null
        val now = now()
        if (session.revoked || now >= session.expiresAtEpochMillis) return null
        val user = users[session.userId] ?: return null
        if (user.deleted) return null
        session.expiresAtEpochMillis = now + limits.sessionLifetimeMillis
        return user
    }

    internal fun openSession(user: UserRecord): SessionRecord {
        val session = SessionRecord(nextId("session-token"), user.id, now() + limits.sessionLifetimeMillis)
        sessions[session.token] = session
        return session
    }

    // ---- Lookups ----------------------------------------------------------------------------------

    internal fun displayNameOf(userId: String?): String {
        val user = userId?.let { users[it] }
        return if (user == null || user.deleted) DELETED_PLAYER_NAME else user.displayName
    }

    /** The room if [userId] is a member and it is not deleted; otherwise null (reported as NOT_FOUND). */
    internal fun visibleRoom(userId: String, roomId: String): RoomRecord? =
        rooms[roomId]?.takeIf { !it.deleted && userId in it.members }

    internal fun challengesOf(roomId: String): List<ChallengeRecord> = challenges.values.filter { it.roomId == roomId }

    internal fun submissionsOf(challengeId: String): List<SubmissionRecord> =
        submissions.values.filter { it.challengeId == challengeId }

    internal fun ballotsOf(challengeId: String): List<BallotRecord> = ballots.filter { it.challengeId == challengeId }

    internal fun blockGraph(): BlockGraph = BlockGraph.of(blockEdges)

    internal fun sharesRoom(first: String, second: String): Boolean =
        rooms.values.any { !it.deleted && first in it.members && second in it.members }

    internal fun invitationState(record: InvitationRecord): InvitationState = InvitationState(
        id = record.id,
        roomId = record.roomId,
        createdByUserId = record.createdByUserId,
        expiresAtEpochMillis = record.expiresAtEpochMillis,
        revoked = record.revoked,
        usesRemaining = record.usesRemaining,
    )

    /** Tally inputs as of now: frozen roster, current members, submissions, ballots in order, blocks. */
    internal fun tallyInput(challenge: ChallengeRecord): TallyInput {
        val room = rooms.getValue(challenge.roomId)
        return TallyInput(
            roster = challenge.roster.toSet(),
            activeMembers = if (room.deleted) emptySet() else room.members.keys.toSet(),
            submissions = submissionsOf(challenge.id).map { SubmissionRef(it.id, it.authorUserId, it.status) },
            ballots = ballotsOf(challenge.id).map { BallotRef(it.voterUserId, it.submissionId) },
            blocks = blockGraph(),
        )
    }

    // ---- Time-driven transitions and purging -------------------------------------------------------

    /** Catches every challenge up to the current clock value. Called at the start of every service call. */
    internal fun advanceAll() {
        val now = now()
        for (challenge in challenges.values) {
            val advanced = ChallengeRules.advance(challenge.lifecycle, now) { tallyInput(challenge) }
            if (advanced.applied.isEmpty()) continue
            challenge.lifecycle = advanced.lifecycle
            if (ChallengeEvent.ARCHIVE in advanced.applied) purgeChallengeMedia(challenge.id)
        }
    }

    internal fun purgeMedia(submission: SubmissionRecord) {
        media.remove(submission.thumbnailMediaId)
        media.remove(submission.fullMediaId)
    }

    internal fun purgeChallengeMedia(challengeId: String) {
        submissionsOf(challengeId).forEach(::purgeMedia)
    }

    internal fun purgeRoomMedia(roomId: String) {
        challengesOf(roomId).forEach { purgeChallengeMedia(it.id) }
    }

    /** States of the room's challenges after [advanceAll]. */
    internal fun challengeStatesOf(roomId: String): List<ChallengeState> = challengesOf(roomId).map { it.lifecycle.state }

    companion object {
        const val DELETED_PLAYER_NAME = "Deleted player"
        const val DEFAULT_RECOVERY_CODE_SEED = 20_261_010L

        /**
         * Small built-in catalogue for tests. The production catalogue is
         * `assets/source/prompts/prompts.v1.json`; pass it as [catalog] to use it instead.
         */
        val DEFAULT_CATALOG: PromptCatalog = PromptCatalog(
            version = "1.0.0",
            prompts = listOf(
                Prompt("p-001", "Morning light", "Catch the first light of the day."),
                Prompt("p-002", "Something red", "Find something red where you are."),
                Prompt("p-003", "Reflections", "A reflection in water, glass or metal."),
                Prompt("p-004", "Tiny world", "Get close to something small."),
                Prompt("p-005", "Long shadows", "Let a shadow be the subject."),
            ),
        )
    }
}

internal class UserRecord(val id: String, var displayName: String) {
    var deleted: Boolean = false

    /** Canonical recovery code (16 symbols, no hyphens). The real server stores only an scrypt hash. */
    var recoveryCode: String? = null
}

internal class SessionRecord(val token: String, val userId: String, var expiresAtEpochMillis: Long) {
    var revoked: Boolean = false
}

internal class RoomRecord(
    val id: String,
    var name: String,
    val ownerUserId: String,
    val capacity: Int,
) {
    /** Members in join order; the owner is first. */
    val members: LinkedHashMap<String, MemberRole> = LinkedHashMap()
    var deleted: Boolean = false
}

internal class InvitationRecord(
    val id: String,
    val roomId: String,
    val token: String,
    val createdByUserId: String,
    val expiresAtEpochMillis: Long,
    var revoked: Boolean,
    var usesRemaining: Int,
)

internal class ChallengeRecord(
    val id: String,
    val roomId: String,
    val roundNumber: Int,
    val prompt: Prompt,
    var lifecycle: ChallengeLifecycle,
) {
    /** Frozen at start, in room join order. Empty until the challenge starts. */
    var roster: List<String> = emptyList()
    var rosterRoles: Map<String, MemberRole> = emptyMap()
}

internal class SubmissionRecord(
    val id: String,
    val challengeId: String,
    var authorUserId: String?,
    var status: SubmissionStatus,
    var thumbnailMediaId: String,
    var fullMediaId: String,
    var width: Int,
    var height: Int,
)

internal class BallotRecord(
    val challengeId: String,
    var voterUserId: String?,
    val submissionId: String,
    val castAtEpochMillis: Long,
)

internal class ReportRecord(
    val reporterUserId: String,
    /** "SUBMISSION:<id>" or "USER:<id>". */
    val targetKey: String,
    val reason: ReportReason,
    val details: String?,
    val createdAtEpochMillis: Long,
)
