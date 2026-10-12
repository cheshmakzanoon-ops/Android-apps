package com.agency.photoduel.ui.state

import com.agency.photoduel.domain.model.AppResult
import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.PhotoDuelError
import com.agency.photoduel.domain.model.UserProfile
import com.agency.photoduel.domain.port.PhotoDuelService
import com.agency.photoduel.domain.testing.FakeServerClock
import com.agency.photoduel.domain.testing.InMemoryPhotoDuelService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppSessionStateTest {
    private val harness = TestHarness()
    private val network = NetworkStatus()
    private val service = InMemoryPhotoDuelService(FakeServerClock())

    @After
    fun tearDown() = harness.close()

    private fun session(
        onboardingCompleted: Boolean = true,
        serverConfigured: Boolean = true,
        using: PhotoDuelService = service,
    ) = AppSessionState(harness.scope, using, network, serverConfigured, onboardingCompleted).also { it.start() }

    /** currentUser() answers from a script; everything else goes to the in-memory service. */
    private class ScriptedUser(
        delegate: PhotoDuelService,
        private val answers: MutableList<AppResult<UserProfile>>,
    ) : PhotoDuelService by delegate {
        var calls = 0
        override suspend fun currentUser(): AppResult<UserProfile> {
            calls++
            return if (answers.size > 1) answers.removeAt(0) else answers.first()
        }
    }

    @Test
    fun firstLaunchShowsOnboardingAndChecksNothing() {
        val state = session(onboardingCompleted = false)
        harness.settle()
        assertEquals(AppPhase.ONBOARDING, state.state.value.phase)
    }

    @Test
    fun finishingOnboardingChecksTheSession() {
        val state = session(onboardingCompleted = false)
        state.onboardingFinished()
        assertEquals(AppPhase.CHECKING_SESSION, state.state.value.phase)
        harness.settle()
        // No stored session: the in-memory service answers UNAUTHENTICATED.
        assertEquals(AppPhase.SIGNED_OUT, state.state.value.phase)
    }

    @Test
    fun withoutAServerTheSetupStateIsShownAndNoCallIsMade() {
        val scripted = ScriptedUser(service, mutableListOf(AppResult.Failure(offline())))
        val state = session(serverConfigured = false, using = scripted)
        harness.settle()
        assertEquals(AppPhase.SERVER_NOT_CONFIGURED, state.state.value.phase)
        state.retry()
        harness.settle()
        assertEquals(AppPhase.SERVER_NOT_CONFIGURED, state.state.value.phase)
        assertEquals(0, scripted.calls)
    }

    @Test
    fun aStoredSessionSignsStraightIn() {
        val user = runBlocking { service.createGuest("Ada").value().user }
        val state = session()
        harness.settle()
        assertEquals(AppPhase.SIGNED_IN, state.state.value.phase)
        assertEquals(user, state.state.value.user)
    }

    @Test
    fun aLostConnectionKeepsRetryingUntilTheServerAnswers() {
        val user = UserProfile("u1", "Ada", hasRecoveryCode = false)
        val scripted = ScriptedUser(service, mutableListOf(AppResult.Failure(offline()), AppResult.Success(user)))
        val state = session(using = scripted)
        harness.scheduler.runCurrent()
        assertEquals(AppPhase.CONNECTION_PROBLEM, state.state.value.phase)
        harness.advance(Loader.DEFAULT_RETRY_DELAYS_MILLIS.first())
        assertEquals(AppPhase.SIGNED_IN, state.state.value.phase)
        assertEquals(2, scripted.calls)
        assertNull(state.state.value.error)
    }

    @Test
    fun anotherFailureIsShownWithItsErrorAndCanBeRetried() {
        val broken = PhotoDuelError(ErrorCode.SERVER, "boom", retryable = true)
        val user = UserProfile("u1", "Ada", hasRecoveryCode = false)
        val scripted = ScriptedUser(service, mutableListOf(AppResult.Failure(broken), AppResult.Success(user)))
        val state = session(using = scripted)
        harness.settle()
        assertEquals(AppPhase.SESSION_ERROR, state.state.value.phase)
        assertEquals(broken, state.state.value.error)
        state.retry()
        harness.settle()
        assertEquals(AppPhase.SIGNED_IN, state.state.value.phase)
    }

    @Test
    fun aRejectedSessionSignsOutOnlyWhenSignedIn() {
        val state = session()
        harness.settle()
        assertEquals(AppPhase.SIGNED_OUT, state.state.value.phase)
        state.onSessionRejected()
        assertEquals(AppPhase.SIGNED_OUT, state.state.value.phase)

        state.onSignedIn(UserProfile("u1", "Ada", hasRecoveryCode = false))
        state.onSessionRejected()
        assertEquals(AppPhase.SIGNED_OUT, state.state.value.phase)
        assertNull(state.state.value.user)
    }

    @Test
    fun onboardingCannotBeReplayedOnceFinished() {
        val state = session()
        harness.settle()
        state.onboardingFinished()
        assertEquals(AppPhase.SIGNED_OUT, state.state.value.phase)
    }

    @Test
    fun signOutCallsTheServiceAndEndsTheSessionEvenIfTheCallFails() {
        runBlocking { service.createGuest("Ada") }
        val state = session()
        harness.settle()
        assertEquals(AppPhase.SIGNED_IN, state.state.value.phase)
        state.signOut()
        harness.settle()
        assertEquals(AppPhase.SIGNED_OUT, state.state.value.phase)
        assertNull(service.sessionToken)

        // Signing out again with no session: the call fails (401) and the local state is still signed out.
        state.onSignedIn(UserProfile("u1", "Ada", hasRecoveryCode = false))
        state.signOut()
        harness.settle()
        assertEquals(AppPhase.SIGNED_OUT, state.state.value.phase)
    }

    @Test
    fun profileChangesApplyOnlyWhileSignedIn() {
        val state = session()
        harness.settle()
        state.onProfileChanged(UserProfile("u1", "Ada", hasRecoveryCode = false))
        assertNull(state.state.value.user)
        state.onSignedIn(UserProfile("u1", "Ada", hasRecoveryCode = false))
        state.onProfileChanged(UserProfile("u1", "Ada L", hasRecoveryCode = true))
        assertEquals("Ada L", state.state.value.user?.displayName)
    }

    @Test
    fun anInvitationWaitsUntilItIsConsumed() {
        val state = session(onboardingCompleted = false)
        state.inviteReceived("token-1")
        assertEquals("token-1", state.state.value.pendingInviteToken)
        state.onboardingFinished()
        harness.settle()
        assertEquals("token-1", state.state.value.pendingInviteToken)
        state.inviteConsumed()
        assertNull(state.state.value.pendingInviteToken)
    }

    @Test
    fun accountDeletionAndSignOutFromProfileEndTheSession() {
        val state = session()
        harness.settle()
        state.onSignedIn(UserProfile("u1", "Ada", hasRecoveryCode = false))
        state.onAccountDeleted()
        assertEquals(AppPhase.SIGNED_OUT, state.state.value.phase)
        state.onSignedIn(UserProfile("u1", "Ada", hasRecoveryCode = false))
        state.onSignedOut()
        assertEquals(AppPhase.SIGNED_OUT, state.state.value.phase)
        assertNull(state.state.value.user)
    }
}
