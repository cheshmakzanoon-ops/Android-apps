package com.agency.photoduel.domain.rules

import com.agency.photoduel.domain.model.ChallengeState
import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.MemberRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RosterRulesTest {

    private val frozen = listOf(ChallengeState.OPEN_FOR_SUBMISSIONS, ChallengeState.VOTING)
    private val notFrozen = listOf(ChallengeState.CREATED, ChallengeState.COMPLETED, ChallengeState.ARCHIVED, ChallengeState.CANCELLED)

    @Test
    fun rosterIsFrozenOnlyDuringSubmissionsAndVoting() {
        for (state in frozen) {
            val reason = RosterRules.checkLeave(MemberRole.MEMBER, listOf(ChallengeState.COMPLETED, state)).refusalOrNull()
            assertEquals(RosterRefusal.RosterFrozen(state), reason)
            assertEquals(ErrorCode.ROSTER_FROZEN, reason!!.code)
            assertEquals(
                RosterRefusal.RosterFrozen(state),
                RosterRules.checkRemove(MemberRole.OWNER, MemberRole.MEMBER, listOf(state)).refusalOrNull(),
            )
        }
        for (state in notFrozen) {
            assertEquals(RuleResult.Ok(Unit), RosterRules.checkLeave(MemberRole.MEMBER, listOf(state)))
            assertEquals(RuleResult.Ok(Unit), RosterRules.checkRemove(MemberRole.OWNER, MemberRole.MEMBER, listOf(state)))
        }
        assertEquals(RuleResult.Ok(Unit), RosterRules.checkLeave(MemberRole.MEMBER, emptyList()))
    }

    @Test
    fun ownerCannotLeave() {
        for (states in listOf(emptyList(), frozen, notFrozen)) {
            val reason = RosterRules.checkLeave(MemberRole.OWNER, states).refusalOrNull()
            assertEquals(RosterRefusal.OwnerCannotLeave, reason)
            assertEquals(ErrorCode.FORBIDDEN, reason!!.code)
        }
    }

    @Test
    fun onlyTheOwnerRemovesAndTheOwnerCannotBeRemoved() {
        assertEquals(RosterRefusal.NotOwner, RosterRules.checkRemove(MemberRole.MEMBER, MemberRole.MEMBER, emptyList()).refusalOrNull())
        assertEquals(RosterRefusal.NotAMember, RosterRules.checkRemove(MemberRole.OWNER, null, emptyList()).refusalOrNull())
        assertEquals(ErrorCode.NOT_FOUND, RosterRefusal.NotAMember.code)
        assertEquals(RosterRefusal.OwnerCannotBeRemoved, RosterRules.checkRemove(MemberRole.OWNER, MemberRole.OWNER, emptyList()).refusalOrNull())
    }

    @Test
    fun capacityMustBeTwoToEight() {
        for (capacity in 2..8) assertEquals(RuleResult.Ok(capacity), RosterRules.checkCapacity(capacity))
        for (capacity in listOf(Int.MIN_VALUE, -1, 0, 1, 9, 100)) {
            val reason = RosterRules.checkCapacity(capacity).refusalOrNull()
            assertEquals(RosterRefusal.CapacityOutOfRange(capacity), reason)
            assertEquals(ErrorCode.VALIDATION, reason!!.code)
        }
        assertEquals(8, RosterRules.DEFAULT_CAPACITY)
        assertEquals(3, RosterRules.MIN_MEMBERS_TO_START)
    }

    @Test
    fun seatIsFreeOnlyBelowCapacity() {
        assertEquals(RuleResult.Ok(Unit), RosterRules.checkHasSeat(7, 8))
        assertEquals(RosterRefusal.RoomFull(8, 8), RosterRules.checkHasSeat(8, 8).refusalOrNull())
        assertEquals(ErrorCode.ROOM_FULL, RosterRules.checkHasSeat(2, 2).refusalOrNull()!!.code)
    }

    @Test
    fun rematchIsAllowedOnlyAfterCompletedArchivedOrCancelled() {
        for (history in listOf(
            emptyList(),
            listOf(ChallengeState.COMPLETED),
            listOf(ChallengeState.CANCELLED),
            listOf(ChallengeState.ARCHIVED),
            listOf(ChallengeState.ARCHIVED, ChallengeState.COMPLETED, ChallengeState.CANCELLED),
        )) {
            assertEquals(history.toString(), RuleResult.Ok(Unit), RosterRules.checkNewChallenge(history))
        }
        for (active in listOf(ChallengeState.CREATED, ChallengeState.OPEN_FOR_SUBMISSIONS, ChallengeState.VOTING)) {
            val reason = RosterRules.checkNewChallenge(listOf(ChallengeState.COMPLETED, active)).refusalOrNull()
            assertEquals(RosterRefusal.ActiveChallengeExists(active), reason)
            assertEquals(ErrorCode.CONFLICT, reason!!.code)
        }
    }

    @Test
    fun frozenStatesAreASubsetOfActiveStates() {
        assertTrue(ChallengeRules.ACTIVE_STATES.containsAll(RosterRules.FROZEN_STATES))
    }
}
