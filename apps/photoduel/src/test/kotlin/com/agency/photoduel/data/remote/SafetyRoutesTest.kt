package com.agency.photoduel.data.remote

import com.agency.photoduel.domain.model.BlockedUser
import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.ReportReason
import com.agency.photoduel.domain.model.ReportTarget
import org.junit.Assert.assertEquals
import org.junit.Test

/** docs/api-contract.md section 9: reports and blocks. */
internal class SafetyRoutesTest : RemoteTestBase() {

    @Test
    fun reportingASubmissionSendsTypeIdReasonAndDetails() = blocking {
        enqueueJson(201, """{"id":"rep-1"}""")

        service.report(ReportTarget.Submission("s1"), ReportReason.NOT_A_PHOTO_OF_PROMPT, "Not the prompt").success()

        val request = takeRequest()
        request.assertRoute("POST", "/v1/reports")
        request.assertAuthenticated()
        request.assertJsonBody(
            """{"targetType":"SUBMISSION","targetId":"s1","reason":"NOT_A_PHOTO_OF_PROMPT","details":"Not the prompt"}""",
        )
    }

    @Test
    fun reportingAUserWithoutDetailsLeavesTheFieldOut() = blocking {
        enqueueJson(200, "{}")

        service.report(ReportTarget.User("u2"), ReportReason.HARASSMENT, null).success()

        takeRequest().assertJsonBody("""{"targetType":"USER","targetId":"u2","reason":"HARASSMENT"}""")
    }

    @Test
    fun reportingOwnContentIs422ValidationBecauseTheCodeDecides() = blocking {
        enqueueError(422, "VALIDATION", "You cannot report your own content")

        assertEquals(ErrorCode.VALIDATION, service.report(ReportTarget.User("u1"), ReportReason.SPAM, null).failure().code)
    }

    @Test
    fun blockedUsersReadsTheList() = blocking {
        enqueueJson(200, """{"blocked":[{"userId":"u2","displayName":"Grace"},{"userId":"u3","displayName":"Deleted player"}]}""")

        assertEquals(
            listOf(BlockedUser("u2", "Grace"), BlockedUser("u3", "Deleted player")),
            service.blockedUsers().success(),
        )
        val request = takeRequest()
        request.assertRoute("GET", "/v1/blocks")
        request.assertAuthenticated()
    }

    @Test
    fun blockingIsABodylessPut() = blocking {
        enqueueNoContent()

        service.setBlocked("u2", blocked = true).success()

        val request = takeRequest()
        request.assertRoute("PUT", "/v1/blocks/u2")
        request.assertAuthenticated()
        request.assertNoBody()
    }

    @Test
    fun unblockingIsABodylessDelete() = blocking {
        enqueueNoContent()

        service.setBlocked("u2", blocked = false).success()

        val request = takeRequest()
        request.assertRoute("DELETE", "/v1/blocks/u2")
        request.assertAuthenticated()
        request.assertNoBody()
    }

    @Test
    fun blockingSomeoneWithoutASharedRoomIsNotFound() = blocking {
        enqueueError(404, "NOT_FOUND", "Not found")

        assertEquals(ErrorCode.NOT_FOUND, service.setBlocked("u9", blocked = true).failure().code)
    }
}
