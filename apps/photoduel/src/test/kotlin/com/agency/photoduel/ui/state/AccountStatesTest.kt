package com.agency.photoduel.ui.state

import com.agency.photoduel.domain.model.ChallengeState
import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.ReportReason
import com.agency.photoduel.domain.model.ReportTarget
import com.agency.photoduel.domain.model.UserProfile
import com.agency.photoduel.domain.testing.InMemoryLimits
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryStateTest {
    private val harness = TestHarness()
    private val network = NetworkStatus()

    @After
    fun tearDown() = harness.close()

    /** [rounds] cancelled challenges played by the whole room, with a page size of 2. */
    private fun played(rounds: Int): Friends {
        val friends = Friends.create(count = 3, limits = InMemoryLimits(pageSize = 2))
        repeat(rounds) {
            val id = friends.startChallenge()
            runBlocking { friends.owner.cancelChallenge(id).value() }
        }
        return friends
    }

    @Test
    fun historyLoadsNewestFirstAndPagesOn() {
        val friends = played(rounds = 3)
        val state = HistoryState(harness.scope, friends.clients[1], network)
        harness.settle()
        val first = state.state.value.valueOrNull!!
        assertEquals(listOf(3, 2), first.entries.map { it.challenge.roundNumber })
        assertNotNull(first.nextCursor)
        assertEquals("Sunday walkers", first.entries.first().roomName)

        state.loadMore()
        assertTrue(state.state.value.valueOrNull!!.loadingMore)
        harness.settle()
        val second = state.state.value.valueOrNull!!
        assertEquals(listOf(3, 2, 1), second.entries.map { it.challenge.roundNumber })
        assertNull(second.nextCursor)
        assertFalse(second.loadingMore)
        assertTrue(second.entries.all { it.challenge.state == ChallengeState.CANCELLED })

        // Nothing more to load.
        state.loadMore()
        harness.settle()
        assertEquals(3, state.state.value.valueOrNull!!.entries.size)
    }

    @Test
    fun anEmptyHistoryLoads() {
        val friends = Friends.create(count = 1)
        val state = HistoryState(harness.scope, friends.owner, network)
        harness.settle()
        assertEquals(HistoryPage(emptyList(), null), state.state.value.valueOrNull)
    }
}

class ProfileStateTest {
    private val harness = TestHarness()
    private val network = NetworkStatus()
    private val friends = Friends.create(count = 2)
    private val changes = mutableListOf<UserProfile>()

    @After
    fun tearDown() = harness.close()

    private fun state(index: Int = 0) =
        ProfileState(harness.scope, friends.clients[index], network) { changes += it }.also { harness.settle() }

    @Test
    fun theProfileLoadsAndIsReportedToTheApp() {
        val state = state()
        assertEquals("Ada", state.profile.value.valueOrNull?.displayName)
        assertEquals(listOf("Ada"), changes.map { it.displayName })
    }

    @Test
    fun renameIsCheckedThenSaved() {
        val state = state()
        state.startRename()
        assertTrue(state.ui.value.renaming)
        assertEquals("Ada", state.ui.value.renameText)
        state.onRenameTextChanged("A".repeat(Inputs.DISPLAY_NAME_MAX + 1))
        state.saveRename()
        assertEquals(NameProblem.TOO_LONG, state.ui.value.renameProblem)
        state.onRenameTextChanged(" Ada L ")
        state.saveRename()
        harness.settle()
        assertFalse(state.ui.value.renaming)
        assertEquals("Ada L", state.profile.value.valueOrNull?.displayName)
        assertEquals("Ada L", changes.last().displayName)
    }

    @Test
    fun cancellingARenameKeepsTheName() {
        val state = state()
        state.startRename()
        state.onRenameTextChanged("Zed")
        state.cancelRename()
        assertFalse(state.ui.value.renaming)
        assertEquals("Ada", state.profile.value.valueOrNull?.displayName)
    }

    @Test
    fun theFirstRecoveryCodeIsIssuedAtOnceAndShownOnce() {
        val state = state()
        assertEquals(false, state.profile.value.valueOrNull?.hasRecoveryCode)
        state.requestRecoveryCode()
        harness.settle()
        val code = state.ui.value.recoveryCode
        assertNotNull(code)
        assertEquals(code, Inputs.recoveryCode(code!!))
        assertEquals(true, state.profile.value.valueOrNull?.hasRecoveryCode)
        state.dismissRecoveryCode()
        assertNull(state.ui.value.recoveryCode)
    }

    @Test
    fun replacingARecoveryCodeAsksFirst() {
        val state = state()
        state.requestRecoveryCode()
        harness.settle()
        val first = state.ui.value.recoveryCode
        state.dismissRecoveryCode()

        state.requestRecoveryCode()
        assertTrue(state.ui.value.confirmRecoveryReplace)
        state.dismissReplaceConfirm()
        assertFalse(state.ui.value.confirmRecoveryReplace)
        assertNull(state.ui.value.recoveryCode)

        state.requestRecoveryCode()
        state.issueRecoveryCode()
        harness.settle()
        assertFalse(state.ui.value.confirmRecoveryReplace)
        assertNotEquals(first, state.ui.value.recoveryCode)
    }

    @Test
    fun deletingNeedsTheTypedWord() {
        val state = state(index = 1)
        state.startDelete()
        state.onDeleteTextChanged("delete")
        assertFalse(state.ui.value.deleteConfirmed)
        state.confirmDelete()
        harness.settle()
        assertFalse(state.ui.value.accountDeleted)

        state.onDeleteTextChanged("DELETE")
        assertTrue(state.ui.value.deleteConfirmed)
        state.confirmDelete()
        harness.settle()
        assertTrue(state.ui.value.accountDeleted)
        assertFalse(state.ui.value.deleting)
        assertEquals(ErrorCode.UNAUTHENTICATED, runBlocking { friends.clients[1].currentUser() }.error().code)
    }

    @Test
    fun cancellingDeleteClearsTheField() {
        val state = state()
        state.startDelete()
        state.onDeleteTextChanged("DEL")
        state.cancelDelete()
        assertFalse(state.ui.value.deleting)
        assertEquals("", state.ui.value.deleteText)
    }

    @Test
    fun signOutEndsTheSession() {
        val state = state()
        state.signOut()
        harness.settle()
        assertTrue(state.ui.value.signedOut)
        assertNull(friends.owner.sessionToken)
    }
}

class SafetyStatesTest {
    private val harness = TestHarness()
    private val network = NetworkStatus()
    private val friends = Friends.create(count = 3)

    @After
    fun tearDown() = harness.close()

    @Test
    fun aReportNeedsAReasonAndIsSentOnce() {
        val ben = friends.users[1]
        val state = ReportState(harness.scope, friends.owner, network, ReportTarget.User(ben.id))
        assertFalse(state.ui.value.canSend)
        state.send()
        harness.settle()
        assertFalse(state.ui.value.sent)

        state.selectReason(ReportReason.HARASSMENT)
        state.onDetailsChanged("x".repeat(400))
        assertEquals(Inputs.REPORT_DETAILS_MAX, state.ui.value.details.length)
        state.send()
        assertTrue(state.ui.value.submitting)
        harness.settle()
        assertTrue(state.ui.value.sent)
        assertFalse(state.ui.value.canSend)
    }

    @Test
    fun reportingYourselfShowsTheServerRefusal() {
        val state = ReportState(harness.scope, friends.owner, network, ReportTarget.User(friends.users[0].id))
        state.selectReason(ReportReason.OTHER)
        state.send()
        harness.settle()
        assertEquals(ErrorCode.VALIDATION, state.ui.value.error?.code)
        assertFalse(state.ui.value.sent)
    }

    @Test
    fun aPhotoCanBeReportedDuringVoting() {
        val id = friends.startChallenge()
        val s = friends.submit(id, 1, 2)
        friends.closeSubmissions()
        val state = ReportState(harness.scope, friends.owner, network, ReportTarget.Submission(s[0]))
        state.selectReason(ReportReason.NOT_A_PHOTO_OF_PROMPT)
        state.send()
        harness.settle()
        assertTrue(state.ui.value.sent)
    }

    @Test
    fun blockingThenUnblockingFromTheList() {
        val ben = friends.users[1]
        val block = BlockState(harness.scope, friends.owner, network, ben.id)
        block.confirm()
        harness.settle()
        assertTrue(block.ui.value.done)
        block.confirm()
        harness.settle()

        val list = BlockedUsersState(harness.scope, friends.owner, network)
        harness.settle()
        assertEquals(listOf("Ben"), list.users.value.valueOrNull!!.map { it.displayName })

        list.unblock(ben.id)
        assertTrue(ben.id in list.ui.value.unblocking)
        harness.settle()
        assertEquals(emptyList<Any>(), list.users.value.valueOrNull)
        assertTrue(list.ui.value.unblocking.isEmpty())
        assertEquals(emptyList<Any>(), runBlocking { friends.owner.blockedUsers().value() })
    }

    @Test
    fun blockingSomeoneOutsideYourRoomsFails() {
        val stranger = friends.owner.newClient()
        val zed = runBlocking { stranger.createGuest("Zed").value().user }
        val block = BlockState(harness.scope, friends.owner, network, zed.id)
        block.confirm()
        harness.settle()
        assertEquals(ErrorCode.NOT_FOUND, block.ui.value.error?.code)
        assertFalse(block.ui.value.done)
    }
}
