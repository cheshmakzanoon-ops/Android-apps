package com.agency.photoduel.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Wire shapes shared by several routes of docs/api-contract.md. Field names match the JSON exactly.
// Response DTOs give optional or nullable fields a default so that `null` and an absent key read the same.

/** The two Json configurations of the data layer. */
internal object RemoteJson {
    /** Responses: a newer server may add fields; they are ignored. */
    val response: Json = Json { ignoreUnknownKeys = true }

    /**
     * Requests: the server rejects unknown fields (`VALIDATION`), so optional values that are null are
     * left out rather than sent as `null`, and every non-null value is written even when it equals a default.
     */
    val request: Json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    /** Body of routes that take no payload. The server accepts an absent body or `{}`. */
    const val EMPTY_OBJECT: String = "{}"
}

/** `{"error":{"code":"<CODE>","message":"…","retryAfterSeconds":<int optional>}}` (section "Common rules"). */
@Serializable
internal data class ErrorEnvelopeDto(val error: ErrorBodyDto? = null)

@Serializable
internal data class ErrorBodyDto(
    val code: String? = null,
    val message: String? = null,
    val retryAfterSeconds: Int? = null,
)

/** `GET /v1/health`: `{"status":"ok","version":"<semver>","database":"ok"}`. */
@Serializable
data class HealthDto(val status: String, val version: String, val database: String)

/** Paged list: `{"items":[…],"nextCursor":"…"|null}`. */
@Serializable
internal data class PageDto<T>(val items: List<T>, val nextCursor: String? = null)
