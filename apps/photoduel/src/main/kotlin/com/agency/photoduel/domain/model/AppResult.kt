package com.agency.photoduel.domain.model

/** Result of every service call. Expected business and network failures are values, not exceptions. */
sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val error: PhotoDuelError) : AppResult<Nothing>
}

/** One entry per error code in docs/api-contract.md section 3, plus client-only codes. */
enum class ErrorCode {
    VALIDATION, UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, INVITE_UNAVAILABLE,
    CONFLICT, INVALID_TRANSITION, ALREADY_VOTED, ROSTER_FROZEN, ROOM_FULL,
    DEADLINE_PASSED, PAYLOAD_TOO_LARGE, UNSUPPORTED_MEDIA, INVALID_BALLOT,
    RATE_LIMITED, SERVER,
    NETWORK, TIMEOUT, UNKNOWN
}

data class PhotoDuelError(
    val code: ErrorCode,
    val message: String,
    val retryable: Boolean,
    val retryAfterSeconds: Int? = null,
)
