package com.agency.photoduel.data.remote

import com.agency.photoduel.domain.model.ChallengeState
import com.agency.photoduel.domain.model.CreatedInvitation
import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.InvitationPreview
import com.agency.photoduel.domain.model.InvitationTtl
import com.agency.photoduel.domain.model.MemberRole
import com.agency.photoduel.domain.model.RoomDetail
import com.agency.photoduel.domain.model.RoomMember
import com.agency.photoduel.domain.model.RoomSummary
import org.junit.Assert.assertEquals
import org.junit.Test

/** docs/api-contract.md section 4: rooms and invitations. */
internal class RoomRoutesTest : RemoteTestBase() {

    private val roomDetail = RoomDetail(
        id = "r1",
        name = "Sunday walkers",
        capacity = 8,
        members = listOf(RoomMember("u1", "Ada", MemberRole.OWNER), RoomMember("u2", "Grace", MemberRole.MEMBER)),
        viewerRole = MemberRole.OWNER,
        viewerUserId = "u1",
    )

    @Test
    fun myRoomsListsSummariesWithANullableActiveState() = blocking {
        enqueueJson(
            200,
            """{"rooms":[
                {"id":"r1","name":"Sunday walkers","memberCount":3,"capacity":8,"activeChallengeState":"OPEN_FOR_SUBMISSIONS"},
                {"id":"r2","name":"Lunch club","memberCount":2,"capacity":4,"activeChallengeState":null}
            ]}""",
        )

        val rooms = service.myRooms().success()

        assertEquals(
            listOf(
                RoomSummary("r1", "Sunday walkers", 3, 8, ChallengeState.OPEN_FOR_SUBMISSIONS),
                RoomSummary("r2", "Lunch club", 2, 4, null),
            ),
            rooms,
        )
        val request = takeRequest()
        request.assertRoute("GET", "/v1/rooms")
        request.assertAuthenticated()
    }

    @Test
    fun createRoomPostsTheNameAndReadsTheRoomDetail() = blocking {
        enqueueJson(201, Fixtures.ROOM_DETAIL)

        assertEquals(roomDetail, service.createRoom("Sunday walkers").success())

        val request = takeRequest()
        request.assertRoute("POST", "/v1/rooms")
        request.assertAuthenticated()
        request.assertJsonBody("""{"name":"Sunday walkers"}""")
    }

    @Test
    fun roomIsAnAuthenticatedGet() = blocking {
        enqueueJson(200, Fixtures.ROOM_DETAIL)

        assertEquals(roomDetail, service.room("r1").success())

        val request = takeRequest()
        request.assertRoute("GET", "/v1/rooms/r1")
        request.assertAuthenticated()
    }

    @Test
    fun leaveRoomPostsAnEmptyObject() = blocking {
        enqueueNoContent()

        service.leaveRoom("r1").success()

        val request = takeRequest()
        request.assertRoute("POST", "/v1/rooms/r1/leave")
        request.assertAuthenticated()
        request.assertJsonBody("{}")
    }

    @Test
    fun leaveRoomDuringAChallengeIsRosterFrozen() = blocking {
        enqueueError(409, "ROSTER_FROZEN", "Membership is frozen while a challenge is active")

        val error = service.leaveRoom("r1").failure()

        assertEquals(ErrorCode.ROSTER_FROZEN, error.code)
        assertEquals("Membership is frozen while a challenge is active", error.message)
    }

    @Test
    fun removeMemberIsABodylessDelete() = blocking {
        enqueueNoContent()

        service.removeMember("r1", "u2").success()

        val request = takeRequest()
        request.assertRoute("DELETE", "/v1/rooms/r1/members/u2")
        request.assertAuthenticated()
        request.assertNoBody()
    }

    @Test
    fun deleteRoomIsABodylessDelete() = blocking {
        enqueueNoContent()

        service.deleteRoom("r1").success()

        val request = takeRequest()
        request.assertRoute("DELETE", "/v1/rooms/r1")
        request.assertAuthenticated()
        request.assertNoBody()
    }

    @Test
    fun createInvitationSendsTheTtlInHoursAndReadsTheOneTimeToken() = blocking {
        enqueueJson(
            201,
            """{"id":"i1","token":"AbC-dEf_123","expiresAtEpochMillis":777,"url":"photoduel://invite/AbC-dEf_123"}""",
        )

        val created = service.createInvitation("r1", InvitationTtl.ONE_WEEK).success()

        assertEquals(CreatedInvitation("i1", "AbC-dEf_123", 777), created)
        val request = takeRequest()
        request.assertRoute("POST", "/v1/rooms/r1/invitations")
        request.assertAuthenticated()
        request.assertJsonBody("""{"ttlHours":168}""")
    }

    @Test
    fun invitationsListsActiveInvitationsWithoutTokens() = blocking {
        enqueueJson(
            200,
            """{"invitations":[{"id":"i1","expiresAtEpochMillis":777,"usesRemaining":5,"createdByUserId":"u1"}]}""",
        )

        val list = service.invitations("r1").success()

        assertEquals(listOf(InvitationSummaryDto("i1", 777, 5, "u1")), list)
        val request = takeRequest()
        request.assertRoute("GET", "/v1/rooms/r1/invitations")
        request.assertAuthenticated()
    }

    @Test
    fun revokeInvitationIsABodylessDelete() = blocking {
        enqueueNoContent()

        service.revokeInvitation("r1", "i1").success()

        val request = takeRequest()
        request.assertRoute("DELETE", "/v1/rooms/r1/invitations/i1")
        request.assertAuthenticated()
        request.assertNoBody()
    }

    @Test
    fun previewInvitationIsAnAuthenticatedGetOfTheToken() = blocking {
        enqueueJson(
            200,
            """{"roomName":"Sunday walkers","ownerDisplayName":"Ada","memberCount":3,"capacity":8,"expiresAtEpochMillis":777}""",
        )

        val preview = service.previewInvitation("AbC-dEf_123").success()

        assertEquals(InvitationPreview("Sunday walkers", "Ada", 3, 8, 777), preview)
        val request = takeRequest()
        request.assertRoute("GET", "/v1/invitations/AbC-dEf_123")
        request.assertAuthenticated()
    }

    @Test
    fun aTokenCannotEscapeItsPathSegment() = blocking {
        enqueueError(404, "INVITE_UNAVAILABLE")

        service.previewInvitation("../rooms?x=1").failure()

        assertEquals("/v1/invitations/..%2Frooms%3Fx=1", takeRequest().path)
    }

    @Test
    fun dotOrEmptySegmentsAreRefusedLocallyBecauseUrlNormalisationWouldChangeTheRoute() = blocking {
        assertEquals(ErrorCode.INVITE_UNAVAILABLE, service.previewInvitation("..").failure().code)
        assertEquals(ErrorCode.INVITE_UNAVAILABLE, service.joinWithInvitation(".").failure().code)
        assertEquals(ErrorCode.NOT_FOUND, service.room("").failure().code)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun joinWithInvitationPostsAnEmptyObjectAndReadsTheRoom() = blocking {
        enqueueJson(200, Fixtures.ROOM_DETAIL)

        assertEquals(roomDetail, service.joinWithInvitation("AbC-dEf_123").success())

        val request = takeRequest()
        request.assertRoute("POST", "/v1/invitations/AbC-dEf_123/join")
        request.assertAuthenticated()
        request.assertJsonBody("{}")
    }

    @Test
    fun joiningAFullRoomIsRoomFull() = blocking {
        enqueueError(409, "ROOM_FULL", "The room is full")

        assertEquals(ErrorCode.ROOM_FULL, service.joinWithInvitation("AbC-dEf_123").failure().code)
    }
}
