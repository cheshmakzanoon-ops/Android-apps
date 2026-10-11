package com.agency.photoduel.domain.rules

import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.InvitationTtl
import com.agency.photoduel.domain.model.MemberRole

/** Pure facts about one invitation, as stored by the server. */
data class InvitationState(
    val id: String,
    val roomId: String,
    val createdByUserId: String,
    val expiresAtEpochMillis: Long,
    val revoked: Boolean,
    val usesRemaining: Int,
)

/** Pure facts about the invitation's room, from the point of view of the caller. */
data class InvitationRoomState(
    val deleted: Boolean,
    val memberCount: Int,
    val capacity: Int,
    val callerIsMember: Boolean,
)

/** Internal reason an invitation is unavailable. Clients always see the same code and message. */
enum class UnavailableReason { UNKNOWN_TOKEN, REVOKED, EXPIRED, EXHAUSTED, ROOM_DELETED }

/** Why an invitation action was refused. */
sealed class InvitationRefusal(override val code: ErrorCode, override val message: String) : DomainRefusal {

    /**
     * Unknown, revoked, expired, exhausted or room deleted (404 INVITE_UNAVAILABLE). The message is
     * identical for every [reason] so a token holder cannot tell the cases apart.
     */
    data class Unavailable(val reason: UnavailableReason) :
        InvitationRefusal(ErrorCode.INVITE_UNAVAILABLE, UNAVAILABLE_MESSAGE)

    /** The room has no free seat (409 ROOM_FULL). */
    data class RoomFull(val memberCount: Int, val capacity: Int) :
        InvitationRefusal(ErrorCode.ROOM_FULL, "This room is full.")

    /** The room already has the maximum number of active invitations (409 CONFLICT). */
    data class TooManyActive(val activeCount: Int, val max: Int) :
        InvitationRefusal(ErrorCode.CONFLICT, "This room already has $max active invitations.")

    /** Only the creator or the room owner may revoke an invitation (403 FORBIDDEN). */
    data object NotAllowedToRevoke :
        InvitationRefusal(ErrorCode.FORBIDDEN, "Only the person who created this invitation or the owner can revoke it.")

    companion object {
        const val UNAVAILABLE_MESSAGE = "This invitation is no longer available."
    }
}

/** What an allowed join does. */
enum class JoinDecision {
    /** Add the caller to the room and consume one use. */
    JOIN_AS_NEW_MEMBER,

    /** The caller is already a member: return the room, consume nothing (idempotent). */
    ALREADY_MEMBER,
}

/**
 * Invitation validity (architecture sections 6 and 10, api-contract.md section 4). Expiry and capacity
 * are always decided against the supplied server-clock value, never the device clock.
 */
object Invitations {
    const val MAX_ACTIVE_PER_ROOM = 5

    /** Expiry instant for an invitation created at [createdAtEpochMillis]. */
    fun expiresAt(createdAtEpochMillis: Long, ttl: InvitationTtl): Long =
        createdAtEpochMillis + Durations.hours(ttl.hours)

    /** Initial number of uses: up to the room capacity (architecture section 10). */
    fun initialUses(capacity: Int): Int = capacity

    /** Expired at and after `expiresAt`: the invitation is valid only while `now < expiresAt`. */
    fun isExpired(invitation: InvitationState, nowEpochMillis: Long): Boolean =
        nowEpochMillis >= invitation.expiresAtEpochMillis

    /** Counts toward the per-room limit: not revoked, not expired and with uses remaining. */
    fun isActive(invitation: InvitationState, nowEpochMillis: Long): Boolean =
        !invitation.revoked && !isExpired(invitation, nowEpochMillis) && invitation.usesRemaining > 0

    /**
     * Creating an invitation (caller membership is checked by the caller of this rule): the room must
     * have a free seat and fewer than [MAX_ACTIVE_PER_ROOM] active invitations.
     */
    fun checkCreate(
        memberCount: Int,
        capacity: Int,
        activeInvitationCount: Int,
    ): RuleResult<Unit, InvitationRefusal> {
        if (memberCount >= capacity) return RuleResult.Refused(InvitationRefusal.RoomFull(memberCount, capacity))
        if (activeInvitationCount >= MAX_ACTIVE_PER_ROOM) {
            return RuleResult.Refused(InvitationRefusal.TooManyActive(activeInvitationCount, MAX_ACTIVE_PER_ROOM))
        }
        return RuleResult.Ok(Unit)
    }

    /**
     * Joining by token. Order of checks:
     * 1. unknown token or deleted room: INVITE_UNAVAILABLE;
     * 2. the caller is already a member: [JoinDecision.ALREADY_MEMBER] (idempotent; no use consumed);
     * 3. revoked, expired (`now >= expiresAt`) or no uses left: INVITE_UNAVAILABLE;
     * 4. room full: ROOM_FULL;
     * 5. otherwise [JoinDecision.JOIN_AS_NEW_MEMBER].
     *
     * Idempotency comes before the validity checks on purpose: a retried join whose first attempt used
     * the last seat or the last use, or that crosses the expiry instant, must still return the room.
     * Returning a room to someone who is already in it reveals nothing new.
     */
    fun checkJoin(
        invitation: InvitationState?,
        room: InvitationRoomState?,
        nowEpochMillis: Long,
    ): RuleResult<JoinDecision, InvitationRefusal> {
        if (invitation == null) return unavailable(UnavailableReason.UNKNOWN_TOKEN)
        if (room == null || room.deleted) return unavailable(UnavailableReason.ROOM_DELETED)
        if (room.callerIsMember) return RuleResult.Ok(JoinDecision.ALREADY_MEMBER)
        validity(invitation, nowEpochMillis)?.let { return unavailable(it) }
        if (room.memberCount >= room.capacity) {
            return RuleResult.Refused(InvitationRefusal.RoomFull(room.memberCount, room.capacity))
        }
        return RuleResult.Ok(JoinDecision.JOIN_AS_NEW_MEMBER)
    }

    /** Previewing by token follows the same rules as joining, without changing anything. */
    fun checkPreview(
        invitation: InvitationState?,
        room: InvitationRoomState?,
        nowEpochMillis: Long,
    ): RuleResult<JoinDecision, InvitationRefusal> = checkJoin(invitation, room, nowEpochMillis)

    /**
     * Revoking: any member may revoke an invitation they created; the owner may revoke any invitation.
     * Revoking an already revoked invitation is allowed (idempotent).
     */
    fun checkRevoke(
        invitation: InvitationState,
        actorUserId: String,
        actorRole: MemberRole,
    ): RuleResult<Unit, InvitationRefusal> =
        if (actorRole == MemberRole.OWNER || invitation.createdByUserId == actorUserId) {
            RuleResult.Ok(Unit)
        } else {
            RuleResult.Refused(InvitationRefusal.NotAllowedToRevoke)
        }

    private fun validity(invitation: InvitationState, nowEpochMillis: Long): UnavailableReason? = when {
        invitation.revoked -> UnavailableReason.REVOKED
        isExpired(invitation, nowEpochMillis) -> UnavailableReason.EXPIRED
        invitation.usesRemaining <= 0 -> UnavailableReason.EXHAUSTED
        else -> null
    }

    private fun unavailable(reason: UnavailableReason): RuleResult<Nothing, InvitationRefusal> =
        RuleResult.Refused(InvitationRefusal.Unavailable(reason))
}
