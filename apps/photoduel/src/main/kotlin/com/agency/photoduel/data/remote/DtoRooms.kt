package com.agency.photoduel.data.remote

import kotlinx.serialization.Serializable

// docs/api-contract.md section 4 (Rooms and invitations). Enum values travel as their contract names
// (strings) and are converted in DtoMappers.kt, so an unexpected value is reported as an unreadable
// response instead of a crash.

/** `RoomSummary`: `{"id","name","memberCount","capacity","activeChallengeState":"…"|null}`. */
@Serializable
internal data class RoomSummaryDto(
    val id: String,
    val name: String,
    val memberCount: Int,
    val capacity: Int,
    val activeChallengeState: String? = null,
)

/** `GET /v1/rooms` response: `{"rooms":[RoomSummary]}`. */
@Serializable
internal data class RoomsResponseDto(val rooms: List<RoomSummaryDto>)

/** `POST /v1/rooms` request: `{"name":"Sunday walkers"}`. */
@Serializable
internal data class CreateRoomRequestDto(val name: String)

/** Member of a room or of a frozen roster: `{"userId","displayName","role"}`. */
@Serializable
internal data class RoomMemberDto(val userId: String, val displayName: String, val role: String)

/** `RoomDetail`: `{"id","name","capacity","viewerUserId","viewerRole","members":[…]}`. */
@Serializable
internal data class RoomDetailDto(
    val id: String,
    val name: String,
    val capacity: Int,
    val viewerUserId: String,
    val viewerRole: String,
    val members: List<RoomMemberDto>,
)

/** `POST /v1/rooms/{roomId}/invitations` request: `{"ttlHours":72}`. */
@Serializable
internal data class CreateInvitationRequestDto(val ttlHours: Int)

/** `POST /v1/rooms/{roomId}/invitations` response `201`. The token is returned once. */
@Serializable
internal data class CreatedInvitationDto(
    val id: String,
    val token: String,
    val expiresAtEpochMillis: Long,
    val url: String,
) {
    override fun toString(): String =
        "CreatedInvitationDto(id=$id, token=${ApiErrors.REDACTED}, expiresAtEpochMillis=$expiresAtEpochMillis, url=${ApiErrors.REDACTED})"
}

/** One entry of `GET /v1/rooms/{roomId}/invitations`. Never includes a token. */
@Serializable
data class InvitationSummaryDto(
    val id: String,
    val expiresAtEpochMillis: Long,
    val usesRemaining: Int,
    val createdByUserId: String,
)

/** `GET /v1/rooms/{roomId}/invitations` response: `{"invitations":[…]}`. */
@Serializable
internal data class InvitationListResponseDto(val invitations: List<InvitationSummaryDto>)

/** `InvitationPreview`: `{"roomName","ownerDisplayName","memberCount","capacity","expiresAtEpochMillis"}`. */
@Serializable
internal data class InvitationPreviewDto(
    val roomName: String,
    val ownerDisplayName: String,
    val memberCount: Int,
    val capacity: Int,
    val expiresAtEpochMillis: Long,
)
