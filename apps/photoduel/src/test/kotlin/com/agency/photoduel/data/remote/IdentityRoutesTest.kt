package com.agency.photoduel.data.remote

import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.RecoveryCode
import com.agency.photoduel.domain.model.Session
import com.agency.photoduel.domain.model.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/api-contract.md sections 1 and 2: health and identity routes. */
internal class IdentityRoutesTest : RemoteTestBase() {

    @Test
    fun healthIsAnAnonymousGet() = blocking {
        enqueueJson(200, """{"status":"ok","version":"1.0.0","database":"ok"}""")

        val health = service.health().success()

        assertEquals(HealthDto("ok", "1.0.0", "ok"), health)
        val request = takeRequest()
        request.assertRoute("GET", "/v1/health")
        request.assertAnonymous()
    }

    @Test
    fun createGuestPostsTheDisplayNameWithoutATokenAndStoresTheNewSession() = blocking {
        store.token = "an-older-token"
        enqueueJson(201, Fixtures.SESSION)

        val session = service.createGuest("Ada").success()

        assertEquals(Session(UserProfile("u1", "Ada", false), TEST_TOKEN, 1234), session)
        val request = takeRequest()
        request.assertRoute("POST", "/v1/identity/guest")
        request.assertAnonymous()
        request.assertJsonBody("""{"displayName":"Ada"}""")
        assertEquals(listOf<String?>(TEST_TOKEN), store.writes)
    }

    @Test
    fun createGuestFailureStoresNothing() = blocking {
        enqueueError(429, "RATE_LIMITED", "Too many requests, try again later", retryAfterSeconds = 1800)

        val error = service.createGuest("Ada").failure()

        assertEquals(ErrorCode.RATE_LIMITED, error.code)
        assertEquals(1800, error.retryAfterSeconds)
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun restorePostsTheCodeWithoutATokenStoresTheSessionAndKeepsTheRotatedCodeOnce() = blocking {
        store.token = null
        enqueueJson(
            200,
            """{"user":{"id":"u1","displayName":"Ada","hasRecoveryCode":true},
               "session":{"token":"$TEST_TOKEN","expiresAtEpochMillis":99},
               "recoveryCode":"WXYZ-0123-4567-89AB"}""",
        )

        val session = service.restoreWithRecoveryCode("abcd-efgh-jkmn-pqrs").success()

        assertEquals(Session(UserProfile("u1", "Ada", true), TEST_TOKEN, 99), session)
        val request = takeRequest()
        request.assertRoute("POST", "/v1/identity/restore")
        request.assertAnonymous()
        request.assertJsonBody("""{"recoveryCode":"abcd-efgh-jkmn-pqrs"}""")
        assertEquals(listOf<String?>(TEST_TOKEN), store.writes)
        assertEquals(RecoveryCode("WXYZ-0123-4567-89AB"), service.takeRotatedRecoveryCode())
        assertNull(service.takeRotatedRecoveryCode())
    }

    @Test
    fun restoreWithAnInvalidCodeIsUnauthenticatedWithTheReasonInTheMessage() = blocking {
        enqueueError(401, "UNAUTHENTICATED", "INVALID_RECOVERY_CODE: the recovery code is not valid")

        val error = service.restoreWithRecoveryCode("AAAA-BBBB-CCCC-DDDD").failure()

        assertEquals(ErrorCode.UNAUTHENTICATED, error.code)
        assertTrue(error.message.contains("INVALID_RECOVERY_CODE"))
        assertFalse(error.retryable)
        assertTrue(store.writes.isEmpty())
        assertNull(service.takeRotatedRecoveryCode())
    }

    @Test
    fun issueRecoveryCodePostsAnEmptyObjectWithTheToken() = blocking {
        enqueueJson(201, """{"recoveryCode":"ABCD-EFGH-JKMN-PQRS"}""")

        assertEquals(RecoveryCode("ABCD-EFGH-JKMN-PQRS"), service.issueRecoveryCode().success())

        val request = takeRequest()
        request.assertRoute("POST", "/v1/identity/recovery-code")
        request.assertAuthenticated()
        request.assertJsonBody("{}")
    }

    @Test
    fun currentUserIsAnAuthenticatedGetOfMe() = blocking {
        enqueueJson(200, Fixtures.USER)

        assertEquals(UserProfile("u1", "Ada", false), service.currentUser().success())

        val request = takeRequest()
        request.assertRoute("GET", "/v1/me")
        request.assertAuthenticated()
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun renameSelfPatchesMeWithTheDisplayName() = blocking {
        enqueueJson(200, """{"id":"u1","displayName":"Grace","hasRecoveryCode":true}""")

        assertEquals(UserProfile("u1", "Grace", true), service.renameSelf("Grace").success())

        val request = takeRequest()
        request.assertRoute("PATCH", "/v1/me")
        request.assertAuthenticated()
        request.assertJsonBody("""{"displayName":"Grace"}""")
    }

    @Test
    fun logoutPostsAnEmptyObjectAndClearsTheStoredSession() = blocking {
        enqueueNoContent()

        service.logout().success()

        val request = takeRequest()
        request.assertRoute("POST", "/v1/identity/logout")
        request.assertAuthenticated()
        request.assertJsonBody("{}")
        assertEquals(listOf<String?>(null), store.writes)
        assertNull(store.token)
    }

    @Test
    fun aFailedLogoutKeepsTheStoredSession() = blocking {
        enqueueError(500, "SERVER", "boom")

        assertEquals(ErrorCode.SERVER, service.logout().failure().code)

        assertTrue(store.writes.isEmpty())
        assertEquals(TEST_TOKEN, store.token)
    }

    @Test
    fun deleteAccountSendsTheConfirmationInADeleteBodyAndClearsTheStoredSession() = blocking {
        enqueueNoContent()

        service.deleteAccount().success()

        val request = takeRequest()
        request.assertRoute("DELETE", "/v1/me")
        request.assertAuthenticated()
        request.assertJsonBody("""{"confirm":"DELETE"}""")
        assertEquals(listOf<String?>(null), store.writes)
    }

    @Test
    fun anAuthenticatedCallWithoutAStoredSessionFailsWithoutSendingAnything() = blocking {
        store.token = null

        val error = service.currentUser().failure()

        assertEquals(ErrorCode.UNAUTHENTICATED, error.code)
        assertFalse(error.retryable)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aSessionStoreThatCannotBeReadGivesUnknownWithoutSendingAnything() = blocking {
        store.failReads = true

        val error = service.myRooms().failure()

        assertEquals(ErrorCode.UNKNOWN, error.code)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theTokenIsReadFromTheSessionStoreForEveryRequest() = blocking {
        enqueueJson(200, Fixtures.USER)
        enqueueJson(200, Fixtures.USER)

        service.currentUser().success()
        store.token = "rotated-token-not-real"
        service.currentUser().success()

        assertEquals("Bearer $TEST_TOKEN", takeRequest().getHeader("Authorization"))
        assertEquals("Bearer rotated-token-not-real", takeRequest().getHeader("Authorization"))
        assertEquals(2, store.reads.get())
    }

    @Test
    fun dtosThatHoldCredentialsNeverPrintThem() {
        val dto = RemoteJson.response.decodeFromString(SessionResponseDto.serializer(), Fixtures.SESSION)
        assertFalse(dto.toString().contains(TEST_TOKEN))
        assertFalse(RestoreRequestDto("ABCD-EFGH-JKMN-PQRS").toString().contains("ABCD"))
        assertFalse(RecoveryCodeDto("ABCD-EFGH-JKMN-PQRS").toString().contains("ABCD"))
        assertFalse(CreatedInvitationDto("i1", "tok-abc", 1, "photoduel://invite/tok-abc").toString().contains("tok-abc"))
    }
}
