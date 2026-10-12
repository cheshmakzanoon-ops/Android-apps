package com.agency.photoduel.ui.state

import com.agency.photoduel.domain.model.AppResult
import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.PhotoDuelError
import com.agency.photoduel.domain.model.RecoveryCode
import com.agency.photoduel.domain.port.PhotoDuelService
import com.agency.photoduel.domain.testing.FakeServerClock
import com.agency.photoduel.domain.testing.InMemoryPhotoDuelService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingStateTest {

    @Test
    fun pagesMoveForwardAndBackWithinBounds() {
        var finished = 0
        val state = OnboardingState { finished++ }
        assertEquals(OnboardingUi(page = 0, pageCount = 3), state.state.value)
        assertTrue(state.state.value.isFirst)
        state.previous()
        assertEquals(0, state.state.value.page)
        state.next()
        state.next()
        assertTrue(state.state.value.isLast)
        assertEquals(0, finished)
        state.previous()
        assertEquals(1, state.state.value.page)
    }

    @Test
    fun finishingOnTheLastPageCallsBackOnce() {
        var finished = 0
        val state = OnboardingState { finished++ }
        repeat(3) { state.next() }
        assertEquals(1, finished)
        state.next()
        state.skip()
        assertEquals(1, finished)
    }

    @Test
    fun skipFinishesFromAnyPage() {
        var finished = 0
        val state = OnboardingState { finished++ }
        state.skip()
        assertEquals(1, finished)
    }

    @Test(expected = IllegalArgumentException::class)
    fun atLeastOnePageIsRequired() {
        OnboardingState(pageCount = 0) {}
    }
}

class SignInStateTest {
    private val harness = TestHarness()
    private val network = NetworkStatus()
    private val clock = FakeServerClock()
    private val service = InMemoryPhotoDuelService(clock)

    @After
    fun tearDown() = harness.close()

    @Test
    fun aGuestNameIsCheckedBeforeAnyCall() {
        val state = SignInState(harness.scope, service, network)
        state.onNameChanged("   ")
        state.submitGuest()
        assertEquals(NameProblem.EMPTY, state.state.value.nameProblem)
        state.onNameChanged("Ada!")
        assertNull(state.state.value.nameProblem)
        state.submitGuest()
        assertEquals(NameProblem.ILLEGAL_CHARACTER, state.state.value.nameProblem)
        harness.settle()
        assertNull(service.sessionToken)
    }

    @Test
    fun aGuestIsCreatedWithTheCleanName() {
        val state = SignInState(harness.scope, service, network)
        state.onNameChanged("  Ada ")
        state.submitGuest()
        assertTrue(state.state.value.submitting)
        harness.settle()
        assertEquals("Ada", state.state.value.completedUser?.displayName)
        assertFalse(state.state.value.submitting)
        state.consumeCompleted()
        assertNull(state.state.value.completedUser)
    }

    @Test
    fun aMalformedRecoveryCodeIsRefusedLocally() {
        val state = SignInState(harness.scope, service, network)
        state.setMode(SignInMode.RESTORE)
        state.onCodeChanged("ABCD")
        state.submitRestore()
        assertTrue(state.state.value.codeInvalid)
        state.onCodeChanged("ABCDE")
        assertFalse(state.state.value.codeInvalid)
    }

    @Test
    fun anUnknownRecoveryCodeShowsTheServerError() {
        val state = SignInState(harness.scope, service, network)
        state.setMode(SignInMode.RESTORE)
        state.onCodeChanged("ABCD-EFGH-JKMN-PQRS")
        state.submitRestore()
        harness.settle()
        assertEquals(ErrorCode.UNAUTHENTICATED, state.state.value.error?.code)
        assertNull(state.state.value.completedUser)
    }

    @Test
    fun restoreShowsAFreshCodeOnceAndThenFinishes() {
        val (original, oldCode) = runBlocking {
            val ada = service.createGuest("Ada").value().user
            val code = service.issueRecoveryCode().value()
            service.logout().value()
            ada to code
        }
        val device = service.newClient()
        val state = SignInState(harness.scope, device, network)
        state.setMode(SignInMode.RESTORE)
        state.onCodeChanged(oldCode.code.lowercase())
        state.submitRestore()
        harness.settle()

        val notice = state.state.value.notice
        assertTrue(notice is RestoreNotice.NewCode)
        val newCode = (notice as RestoreNotice.NewCode).code
        assertNotEquals(oldCode.code, newCode)
        // Sign-in waits until the user has seen the code.
        assertNull(state.state.value.completedUser)
        state.acknowledgeNotice()
        assertEquals(original.id, state.state.value.completedUser?.id)
        assertNull(state.state.value.notice)
        assertEquals("", state.state.value.recoveryCode)
    }

    @Test
    fun restoreSaysSoWhenNoNewCodeCouldBeIssued() {
        val user = runBlocking {
            val ada = service.createGuest("Ada").value().user
            service.issueRecoveryCode().value()
            ada
        }
        val code = runBlocking { service.issueRecoveryCode().value() }
        val failingIssue = object : PhotoDuelService by service.newClient() {
            override suspend fun issueRecoveryCode(): AppResult<RecoveryCode> =
                AppResult.Failure(PhotoDuelError(ErrorCode.SERVER, "later", retryable = true))
        }
        val state = SignInState(harness.scope, failingIssue, network)
        state.onCodeChanged(code.code)
        state.submitRestore()
        harness.settle()
        assertEquals(RestoreNotice.CodeNotIssued, state.state.value.notice)
        state.acknowledgeNotice()
        assertEquals(user.id, state.state.value.completedUser?.id)
    }

    @Test
    fun switchingModeClearsTheError() {
        val state = SignInState(harness.scope, service, network)
        state.onCodeChanged("ABCD-EFGH-JKMN-PQRS")
        state.submitRestore()
        harness.settle()
        assertEquals(ErrorCode.UNAUTHENTICATED, state.state.value.error?.code)
        state.setMode(SignInMode.GUEST)
        assertNull(state.state.value.error)
        assertEquals(SignInMode.GUEST, state.state.value.mode)
    }

    @Test
    fun acknowledgingWithoutAPendingRestoreDoesNothing() {
        val state = SignInState(harness.scope, service, network)
        state.acknowledgeNotice()
        assertNull(state.state.value.completedUser)
    }
}
