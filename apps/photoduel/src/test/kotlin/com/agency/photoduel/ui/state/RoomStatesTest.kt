package com.agency.photoduel.ui.state

import com.agency.photoduel.domain.model.ChallengeSpec
import com.agency.photoduel.domain.model.ChallengeState
import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.InvitationTtl
import com.agency.photoduel.domain.model.MemberRole
import com.agency.photoduel.domain.testing.FakeServerClock
import com.agency.photoduel.domain.testing.InMemoryPhotoDuelService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeStateTest {
    private val harness = TestHarness()
    private val network = NetworkStatus()

    @After
    fun tearDown() = harness.close()

    @Test
    fun roomsComeFromTheService() {
        val friends = Friends.create(count = 2)
        val home = HomeState(harness.scope, friends.clients[1], network)
        harness.settle()
        val rooms = home.state.value.valueOrNull!!
        assertEquals(listOf("Sunday walkers"), rooms.map { it.name })
        assertEquals(2, rooms.single().memberCount)
        assertNull(rooms.single().activeChallengeState)
    }

    @Test
    fun aNewUserHasNoRooms() {
        val service = InMemoryPhotoDuelService(FakeServerClock())
        runBlocking { service.createGuest("Solo").value() }
        val home = HomeState(harness.scope, service, network)
        harness.settle()
        assertEquals(emptyList<Any>(), home.state.value.valueOrNull)
    }

    @Test
    fun thePasteDialogAcceptsOnlyInvitations() {
        val service = InMemoryPhotoDuelService(FakeServerClock())
        val home = HomeState(harness.scope, service, network)
        home.openJoinDialog()
        assertTrue(home.join.value.open)
        home.onJoinTextChanged("not a link")
        assertNull(home.submitJoinLink())
        assertTrue(home.join.value.invalid)
        home.onJoinTextChanged("photoduel://invite/abcdefghijklmnopqrstuvwxyz")
        assertFalse(home.join.value.invalid)
        assertEquals("abcdefghijklmnopqrstuvwxyz", home.submitJoinLink())
        assertFalse(home.join.value.open)
        assertEquals("", home.join.value.text)
    }

    @Test
    fun closingTheDialogForgetsTheText() {
        val home = HomeState(harness.scope, InMemoryPhotoDuelService(FakeServerClock()), network)
        home.openJoinDialog()
        home.onJoinTextChanged("photoduel://invite/abc")
        home.closeJoinDialog()
        assertEquals(JoinLinkUi(), home.join.value)
    }
}

class CreateRoomStateTest {
    private val harness = TestHarness()
    private val network = NetworkStatus()
    private val service = InMemoryPhotoDuelService(FakeServerClock())

    @After
    fun tearDown() = harness.close()

    @Test
    fun anEmptyNameIsRefusedLocally() {
        runBlocking { service.createGuest("Ada").value() }
        val state = CreateRoomState(harness.scope, service, network)
        state.submit()
        assertEquals(NameProblem.EMPTY, state.state.value.problem)
        state.onNameChanged("x")
        assertNull(state.state.value.problem)
    }

    @Test
    fun aRoomIsCreatedAndReported() {
        runBlocking { service.createGuest("Ada").value() }
        val state = CreateRoomState(harness.scope, service, network)
        state.onNameChanged(" Sunday walkers ")
        state.submit()
        harness.settle()
        val id = state.state.value.createdRoomId
        assertNotNull(id)
        assertEquals("Sunday walkers", runBlocking { service.room(id!!).value().name })
        state.consumeCreated()
        assertNull(state.state.value.createdRoomId)
    }

    @Test
    fun theDailyQuotaIsShownAsAnError() {
        runBlocking { service.createGuest("Ada").value() }
        val state = CreateRoomState(harness.scope, service, network)
        repeat(6) {
            state.onNameChanged("Room $it")
            state.submit()
            harness.settle()
        }
        assertEquals(ErrorCode.RATE_LIMITED, state.state.value.error?.code)
    }
}

class RoomDetailStateTest {
    private val harness = TestHarness()
    private val network = NetworkStatus()

    @After
    fun tearDown() = harness.close()

    @Test
    fun theOwnerSeesTheRoomWithoutAChallenge() {
        val friends = Friends.create(count = 3)
        val state = RoomDetailState(harness.scope, friends.owner, network, friends.roomId)
        harness.settle()
        val data = state.data.value.valueOrNull!!
        assertTrue(data.isOwner)
        assertEquals(listOf("Ada", "Ben", "Cy"), data.room.members.map { it.displayName })
        assertNull(data.activeChallenge)
        assertNull(data.latestChallenge)
    }

    @Test
    fun aMemberIsNotTheOwner() {
        val friends = Friends.create(count = 2)
        val state = RoomDetailState(harness.scope, friends.clients[1], network, friends.roomId)
        harness.settle()
        assertFalse(state.data.value.valueOrNull!!.isOwner)
    }

    @Test
    fun anInvitationIsCreatedWithTheChosenLifetimeAndCanBeRevoked() {
        val friends = Friends.create(count = 1)
        val state = RoomDetailState(harness.scope, friends.owner, network, friends.roomId)
        harness.settle()
        assertEquals(InvitationTtl.THREE_DAYS, state.ui.value.ttl)
        state.setTtl(InvitationTtl.ONE_DAY)
        state.createInvitation()
        harness.settle()
        val invitation = state.ui.value.invitation!!
        assertEquals(friends.clock.nowEpochMillis() + 24L * 3_600_000L, invitation.expiresAtEpochMillis)

        state.revokeInvitation()
        harness.settle()
        assertNull(state.ui.value.invitation)
        val stranger = friends.owner.newClient()
        runBlocking { stranger.createGuest("Zed").value() }
        val preview = runBlocking { stranger.previewInvitation(invitation.token) }
        assertEquals(ErrorCode.INVITE_UNAVAILABLE, preview.error().code)
    }

    @Test
    fun startingWithTooFewMembersShowsTheServerRefusal() {
        val friends = Friends.create(count = 2)
        val challenge = runBlocking { friends.owner.createChallenge(friends.roomId, ChallengeSpec("p-001", 24, 6)).value() }
        val state = RoomDetailState(harness.scope, friends.owner, network, friends.roomId)
        harness.settle()
        assertEquals(challenge.id, state.data.value.valueOrNull!!.activeChallenge?.id)
        state.startChallenge(challenge.id)
        harness.settle()
        assertEquals(ErrorCode.CONFLICT, state.ui.value.error?.code)
        assertFalse(state.ui.value.busy)
        state.dismissError()
        assertNull(state.ui.value.error)
    }

    @Test
    fun startingWithEnoughMembersRefreshesTheRoom() {
        val friends = Friends.create(count = 3)
        val challenge = runBlocking { friends.owner.createChallenge(friends.roomId, ChallengeSpec("p-001", 24, 6)).value() }
        val state = RoomDetailState(harness.scope, friends.owner, network, friends.roomId)
        harness.settle()
        state.startChallenge(challenge.id)
        harness.settle()
        assertEquals(ChallengeState.OPEN_FOR_SUBMISSIONS, state.data.value.valueOrNull!!.activeChallenge?.state)
    }

    @Test
    fun leavingDuringARunningChallengeIsRefused() {
        val friends = Friends.create(count = 3)
        friends.startChallenge()
        val state = RoomDetailState(harness.scope, friends.clients[2], network, friends.roomId)
        harness.settle()
        state.ask(RoomConfirm.Leave)
        assertEquals(RoomConfirm.Leave, state.ui.value.confirm)
        state.leave()
        assertNull(state.ui.value.confirm)
        harness.settle()
        assertEquals(ErrorCode.ROSTER_FROZEN, state.ui.value.error?.code)
        assertFalse(state.ui.value.finished)
    }

    @Test
    fun aMemberCanLeaveAnIdleRoom() {
        val friends = Friends.create(count = 3)
        val state = RoomDetailState(harness.scope, friends.clients[2], network, friends.roomId)
        harness.settle()
        state.leave()
        harness.settle()
        assertTrue(state.ui.value.finished)
    }

    @Test
    fun theOwnerRemovesAMemberAndTheRosterUpdates() {
        val friends = Friends.create(count = 3)
        val state = RoomDetailState(harness.scope, friends.owner, network, friends.roomId)
        harness.settle()
        val cy = friends.users[2]
        state.ask(RoomConfirm.RemoveMember(cy.id, cy.displayName))
        state.removeMember(cy.id)
        harness.settle()
        assertEquals(listOf("Ada", "Ben"), state.data.value.valueOrNull!!.room.members.map { it.displayName })
    }

    @Test
    fun theOwnerDeletesTheRoom() {
        val friends = Friends.create(count = 2)
        val state = RoomDetailState(harness.scope, friends.owner, network, friends.roomId)
        harness.settle()
        state.deleteRoom()
        harness.settle()
        assertTrue(state.ui.value.finished)
        assertEquals(ErrorCode.NOT_FOUND, runBlocking { friends.clients[1].room(friends.roomId) }.error().code)
    }

    @Test
    fun cancellingAChallengeLeavesItAsTheLatest() {
        val friends = Friends.create(count = 3)
        val id = friends.startChallenge()
        val state = RoomDetailState(harness.scope, friends.owner, network, friends.roomId)
        harness.settle()
        state.cancelChallenge(id)
        harness.settle()
        val data = state.data.value.valueOrNull!!
        assertNull(data.activeChallenge)
        assertEquals(ChallengeState.CANCELLED, data.latestChallenge?.state)
        assertEquals(MemberRole.OWNER, data.room.viewerRole)
    }
}

class InvitationStateTest {
    private val harness = TestHarness()
    private val network = NetworkStatus()

    @After
    fun tearDown() = harness.close()

    @Test
    fun anUnknownTokenIsAnInvalidLink() {
        val friends = Friends.create(count = 1)
        val stranger = friends.owner.newClient()
        runBlocking { stranger.createGuest("Zed").value() }
        val state = InvitationState(harness.scope, stranger, network, "no-such-token-000000000")
        harness.settle()
        val failed = state.state.value as Loadable.Failed
        assertTrue(InvitationState.isInvalidLink(failed.error))
        assertFalse(failed.reconnecting)
    }

    @Test
    fun aValidTokenPreviewsThenJoins() {
        val friends = Friends.create(count = 2)
        val token = runBlocking { friends.owner.createInvitation(friends.roomId, InvitationTtl.ONE_WEEK).value().token }
        val stranger = friends.owner.newClient()
        runBlocking { stranger.createGuest("Zed").value() }
        val state = InvitationState(harness.scope, stranger, network, token)
        harness.settle()
        val preview = state.state.value.valueOrNull!!
        assertEquals("Sunday walkers", preview.roomName)
        assertEquals("Ada", preview.ownerDisplayName)
        assertEquals(2, preview.memberCount)

        state.join()
        assertTrue(state.ui.value.joining)
        harness.settle()
        assertEquals(friends.roomId, state.ui.value.joinedRoomId)
        state.consumeJoined()
        assertNull(state.ui.value.joinedRoomId)
    }

    @Test
    fun onlyUnavailableAndNotFoundCountAsInvalidLinks() {
        for (code in ErrorCode.entries) {
            val expected = code == ErrorCode.INVITE_UNAVAILABLE || code == ErrorCode.NOT_FOUND
            assertEquals(code.name, expected, InvitationState.isInvalidLink(com.agency.photoduel.domain.model.PhotoDuelError(code, "", false)))
        }
    }
}
