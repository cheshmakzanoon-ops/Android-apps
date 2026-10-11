package com.agency.photoduel.domain.rules

import com.agency.photoduel.domain.model.ChallengeState
import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.MemberRole

/** Why a membership or room-level change was refused. */
sealed class RosterRefusal(override val code: ErrorCode, override val message: String) : DomainRefusal {

    /** A challenge is OPEN_FOR_SUBMISSIONS or VOTING, so the roster is frozen (409 ROSTER_FROZEN). */
    data class RosterFrozen(val activeState: ChallengeState) :
        RosterRefusal(ErrorCode.ROSTER_FROZEN, "Membership cannot change while a challenge is running.")

    /** The owner deletes the room instead of leaving it (403 FORBIDDEN). */
    data object OwnerCannotLeave :
        RosterRefusal(ErrorCode.FORBIDDEN, "The owner cannot leave the room. Delete the room instead.")

    /** The owner can never be removed (403 FORBIDDEN). */
    data object OwnerCannotBeRemoved :
        RosterRefusal(ErrorCode.FORBIDDEN, "The owner cannot be removed.")

    /** An owner-only action was attempted by another member (403 FORBIDDEN). */
    data object NotOwner :
        RosterRefusal(ErrorCode.FORBIDDEN, "Only the room owner can do this.")

    /** The target user is not a member of the room (404 NOT_FOUND). */
    data object NotAMember :
        RosterRefusal(ErrorCode.NOT_FOUND, "That person is not in this room.")

    /** The room has no free seat (409 ROOM_FULL). */
    data class RoomFull(val memberCount: Int, val capacity: Int) :
        RosterRefusal(ErrorCode.ROOM_FULL, "This room is full.")

    /** A capacity outside 2..8 was requested (400 VALIDATION). */
    data class CapacityOutOfRange(val capacity: Int) :
        RosterRefusal(
            ErrorCode.VALIDATION,
            "Room capacity must be between ${RosterRules.MIN_CAPACITY} and ${RosterRules.MAX_CAPACITY}.",
        )

    /** The room already has a CREATED, OPEN_FOR_SUBMISSIONS or VOTING challenge (409 CONFLICT). */
    data class ActiveChallengeExists(val activeState: ChallengeState) :
        RosterRefusal(ErrorCode.CONFLICT, "This room already has an active challenge.")
}

/**
 * Room membership rules (architecture sections 6 and 10, api-contract.md section 4).
 *
 * The challenge states passed in must already be caught up to the current server time with
 * [ChallengeRules.advance]; otherwise a challenge whose voting deadline has passed would still freeze
 * the roster or block a rematch.
 */
object RosterRules {
    const val MIN_CAPACITY = 2
    const val MAX_CAPACITY = 8
    const val DEFAULT_CAPACITY = MAX_CAPACITY
    const val MIN_MEMBERS_TO_START = 3

    /** While a challenge is in one of these states, nobody may leave or be removed. */
    val FROZEN_STATES: Set<ChallengeState> = setOf(ChallengeState.OPEN_FOR_SUBMISSIONS, ChallengeState.VOTING)

    /** The first challenge state that freezes the roster, or null when membership may change. */
    fun frozenBy(challengeStates: Collection<ChallengeState>): ChallengeState? =
        challengeStates.firstOrNull { it in FROZEN_STATES }

    fun isRosterFrozen(challengeStates: Collection<ChallengeState>): Boolean = frozenBy(challengeStates) != null

    /** Capacity must be within 2..8 members, owner included. */
    fun checkCapacity(capacity: Int): RuleResult<Int, RosterRefusal> =
        if (capacity in MIN_CAPACITY..MAX_CAPACITY) {
            RuleResult.Ok(capacity)
        } else {
            RuleResult.Refused(RosterRefusal.CapacityOutOfRange(capacity))
        }

    /** A room with [memberCount] members has a free seat when `memberCount < capacity`. */
    fun checkHasSeat(memberCount: Int, capacity: Int): RuleResult<Unit, RosterRefusal> =
        if (memberCount < capacity) RuleResult.Ok(Unit) else RuleResult.Refused(RosterRefusal.RoomFull(memberCount, capacity))

    /**
     * Leaving: the owner cannot leave (checked first, because it never becomes allowed), and nobody may
     * leave while the roster is frozen.
     */
    fun checkLeave(
        leaverRole: MemberRole,
        challengeStates: Collection<ChallengeState>,
    ): RuleResult<Unit, RosterRefusal> {
        if (leaverRole == MemberRole.OWNER) return RuleResult.Refused(RosterRefusal.OwnerCannotLeave)
        frozenBy(challengeStates)?.let { return RuleResult.Refused(RosterRefusal.RosterFrozen(it)) }
        return RuleResult.Ok(Unit)
    }

    /**
     * Removing a member: only the owner may remove, the target must be a member ([targetRole] null means
     * not a member), the owner cannot be removed, and the roster must not be frozen.
     */
    fun checkRemove(
        actorRole: MemberRole,
        targetRole: MemberRole?,
        challengeStates: Collection<ChallengeState>,
    ): RuleResult<Unit, RosterRefusal> {
        if (actorRole != MemberRole.OWNER) return RuleResult.Refused(RosterRefusal.NotOwner)
        if (targetRole == null) return RuleResult.Refused(RosterRefusal.NotAMember)
        if (targetRole == MemberRole.OWNER) return RuleResult.Refused(RosterRefusal.OwnerCannotBeRemoved)
        frozenBy(challengeStates)?.let { return RuleResult.Refused(RosterRefusal.RosterFrozen(it)) }
        return RuleResult.Ok(Unit)
    }

    /**
     * At most one active challenge per room. A new challenge (including a rematch) is allowed only when
     * every earlier challenge is COMPLETED, ARCHIVED or CANCELLED.
     */
    fun checkNewChallenge(existingStates: Collection<ChallengeState>): RuleResult<Unit, RosterRefusal> {
        val active = existingStates.firstOrNull { it in ChallengeRules.ACTIVE_STATES }
        return if (active == null) RuleResult.Ok(Unit) else RuleResult.Refused(RosterRefusal.ActiveChallengeExists(active))
    }
}
