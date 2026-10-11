package com.agency.photoduel.domain.rules

import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.InvitationTtl
import com.agency.photoduel.domain.model.MemberRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InvitationsTest {

    private val createdAt = 1_767_225_600_000L
    private val expiresAt = Invitations.expiresAt(createdAt, InvitationTtl.THREE_DAYS)
    private val invitation = InvitationState(
        id = "inv-1",
        roomId = "room-1",
        createdByUserId = "owner",
        expiresAtEpochMillis = expiresAt,
        revoked = false,
        usesRemaining = 8,
    )
    private val room = InvitationRoomState(deleted = false, memberCount = 3, capacity = 8, callerIsMember = false)

    private fun join(
        inv: InvitationState? = invitation,
        r: InvitationRoomState? = room,
        now: Long = expiresAt - 1,
    ) = Invitations.checkJoin(inv, r, now)

    @Test
    fun expiryIsCreationPlusTtl() {
        for (ttl in InvitationTtl.entries) {
            assertEquals(createdAt + ttl.hours * Durations.HOUR_MILLIS, Invitations.expiresAt(createdAt, ttl))
        }
        assertEquals(createdAt + 72 * Durations.HOUR_MILLIS, expiresAt)
    }

    @Test
    fun invitationExpiresAtExactlyExpiresAt() {
        assertEquals(RuleResult.Ok(JoinDecision.JOIN_AS_NEW_MEMBER), join(now = expiresAt - 1))
        assertEquals(InvitationRefusal.Unavailable(UnavailableReason.EXPIRED), join(now = expiresAt).refusalOrNull())
        assertEquals(InvitationRefusal.Unavailable(UnavailableReason.EXPIRED), join(now = expiresAt + 1).refusalOrNull())
        assertFalse(Invitations.isExpired(invitation, expiresAt - 1))
        assertTrue(Invitations.isExpired(invitation, expiresAt))
    }

    @Test
    fun everyUnavailableCaseSharesOneCodeAndOneMessage() {
        val refusals = listOf(
            join(inv = null),
            join(inv = invitation.copy(revoked = true)),
            join(now = expiresAt),
            join(inv = invitation.copy(usesRemaining = 0)),
            join(r = room.copy(deleted = true)),
            join(r = null),
        ).map { it.refusalOrNull() as InvitationRefusal.Unavailable }
        assertEquals(
            listOf(
                UnavailableReason.UNKNOWN_TOKEN, UnavailableReason.REVOKED, UnavailableReason.EXPIRED,
                UnavailableReason.EXHAUSTED, UnavailableReason.ROOM_DELETED, UnavailableReason.ROOM_DELETED,
            ),
            refusals.map { it.reason },
        )
        assertEquals(setOf(ErrorCode.INVITE_UNAVAILABLE), refusals.map { it.code }.toSet())
        assertEquals(setOf(InvitationRefusal.UNAVAILABLE_MESSAGE), refusals.map { it.message }.toSet())
    }

    @Test
    fun fullRoomIsRoomFull() {
        val full = room.copy(memberCount = 8)
        assertEquals(InvitationRefusal.RoomFull(8, 8), join(r = full).refusalOrNull())
        assertEquals(ErrorCode.ROOM_FULL, join(r = full).refusalOrNull()!!.code)
        assertEquals(RuleResult.Ok(JoinDecision.JOIN_AS_NEW_MEMBER), join(r = room.copy(memberCount = 7)))
    }

    @Test
    fun existingMemberJoinIsIdempotentEvenWhenFullExpiredExhaustedOrRevoked() {
        val member = room.copy(callerIsMember = true, memberCount = 8)
        assertEquals(RuleResult.Ok(JoinDecision.ALREADY_MEMBER), join(r = member))
        assertEquals(RuleResult.Ok(JoinDecision.ALREADY_MEMBER), join(r = member, now = expiresAt + 1))
        assertEquals(RuleResult.Ok(JoinDecision.ALREADY_MEMBER), join(inv = invitation.copy(usesRemaining = 0, revoked = true), r = member))
    }

    @Test
    fun deletedRoomIsUnavailableEvenForMembers() {
        assertEquals(
            InvitationRefusal.Unavailable(UnavailableReason.ROOM_DELETED),
            join(r = room.copy(deleted = true, callerIsMember = true)).refusalOrNull(),
        )
    }

    @Test
    fun previewFollowsTheJoinRules() {
        assertEquals(join(), Invitations.checkPreview(invitation, room, expiresAt - 1))
        assertEquals(join(now = expiresAt), Invitations.checkPreview(invitation, room, expiresAt))
    }

    @Test
    fun createNeedsAFreeSeatAndFewerThanFiveActiveInvitations() {
        assertEquals(RuleResult.Ok(Unit), Invitations.checkCreate(memberCount = 3, capacity = 8, activeInvitationCount = 4))
        assertEquals(InvitationRefusal.TooManyActive(5, 5), Invitations.checkCreate(3, 8, 5).refusalOrNull())
        assertEquals(ErrorCode.CONFLICT, Invitations.checkCreate(3, 8, 5).refusalOrNull()!!.code)
        assertEquals(InvitationRefusal.RoomFull(8, 8), Invitations.checkCreate(8, 8, 0).refusalOrNull())
    }

    @Test
    fun activeMeansNotRevokedNotExpiredAndUsesRemaining() {
        assertTrue(Invitations.isActive(invitation, expiresAt - 1))
        assertFalse(Invitations.isActive(invitation, expiresAt))
        assertFalse(Invitations.isActive(invitation.copy(revoked = true), createdAt))
        assertFalse(Invitations.isActive(invitation.copy(usesRemaining = 0), createdAt))
    }

    @Test
    fun revokeByCreatorOrOwnerOnly() {
        assertEquals(RuleResult.Ok(Unit), Invitations.checkRevoke(invitation, "owner", MemberRole.MEMBER))
        assertEquals(RuleResult.Ok(Unit), Invitations.checkRevoke(invitation.copy(createdByUserId = "m"), "boss", MemberRole.OWNER))
        val reason = Invitations.checkRevoke(invitation.copy(createdByUserId = "m"), "other", MemberRole.MEMBER).refusalOrNull()
        assertEquals(InvitationRefusal.NotAllowedToRevoke, reason)
        assertEquals(ErrorCode.FORBIDDEN, reason!!.code)
    }
}
