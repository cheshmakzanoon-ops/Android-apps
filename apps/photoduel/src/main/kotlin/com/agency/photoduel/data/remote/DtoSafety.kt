package com.agency.photoduel.data.remote

import kotlinx.serialization.Serializable

// docs/api-contract.md section 9 (Safety).

/**
 * `POST /v1/reports` request:
 * `{"targetType":"SUBMISSION"|"USER","targetId":"…","reason":"…","details":"…" optional}`.
 * A null [details] is left out of the JSON (see [RemoteJson.request]).
 */
@Serializable
internal data class ReportRequestDto(
    val targetType: String,
    val targetId: String,
    val reason: String,
    val details: String? = null,
)

/** `{"userId","displayName"}`. */
@Serializable
internal data class BlockedUserDto(val userId: String, val displayName: String)

/** `GET /v1/blocks` response: `{"blocked":[…]}`. */
@Serializable
internal data class BlockedListDto(val blocked: List<BlockedUserDto>)
