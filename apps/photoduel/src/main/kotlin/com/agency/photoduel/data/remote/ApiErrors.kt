package com.agency.photoduel.data.remote

import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.PhotoDuelError
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/**
 * Maps HTTP failures to [PhotoDuelError] exactly as docs/api-contract.md section 3 states.
 *
 * The `code` in the error body decides. That is what tells `404 NOT_FOUND` from `404 INVITE_UNAVAILABLE`,
 * or `409 ALREADY_VOTED` from `409 CONFLICT`. When the body has no code this layer knows (a proxy page,
 * an empty body, a newer server), the HTTP status decides, using the first code the table lists for it.
 *
 * No message produced here contains the bearer token, a request URL or an exception message: transport
 * failures use fixed text and server messages are passed through [redact] first.
 */
internal object ApiErrors {

    /** The server codes of section 3. Client-only codes (NETWORK, TIMEOUT, UNKNOWN) never come from a body. */
    private val SERVER_CODES: Map<String, ErrorCode> = listOf(
        ErrorCode.VALIDATION, ErrorCode.UNAUTHENTICATED, ErrorCode.FORBIDDEN, ErrorCode.NOT_FOUND,
        ErrorCode.INVITE_UNAVAILABLE, ErrorCode.CONFLICT, ErrorCode.INVALID_TRANSITION, ErrorCode.ALREADY_VOTED,
        ErrorCode.ROSTER_FROZEN, ErrorCode.ROOM_FULL, ErrorCode.DEADLINE_PASSED, ErrorCode.PAYLOAD_TOO_LARGE,
        ErrorCode.UNSUPPORTED_MEDIA, ErrorCode.INVALID_BALLOT, ErrorCode.RATE_LIMITED, ErrorCode.SERVER,
    ).associateBy { it.name }

    const val REDACTED = "[redacted]"

    fun forStatus(status: Int): ErrorCode = when (status) {
        400 -> ErrorCode.VALIDATION
        401 -> ErrorCode.UNAUTHENTICATED
        403 -> ErrorCode.FORBIDDEN
        404 -> ErrorCode.NOT_FOUND
        409 -> ErrorCode.CONFLICT
        410 -> ErrorCode.DEADLINE_PASSED
        413 -> ErrorCode.PAYLOAD_TOO_LARGE
        415 -> ErrorCode.UNSUPPORTED_MEDIA
        422 -> ErrorCode.INVALID_BALLOT
        429 -> ErrorCode.RATE_LIMITED
        in 500..599 -> ErrorCode.SERVER
        else -> ErrorCode.UNKNOWN
    }

    /** Rate limits, server failures and transport failures may succeed later. Everything else needs a change first. */
    fun isRetryable(code: ErrorCode): Boolean = when (code) {
        ErrorCode.RATE_LIMITED, ErrorCode.SERVER, ErrorCode.NETWORK, ErrorCode.TIMEOUT -> true
        else -> false
    }

    /**
     * Error for a non-2xx response. [body] is the raw response text, [retryAfterHeader] the `Retry-After`
     * header, and [secrets] values that must never appear in the message (the bearer token).
     */
    fun fromResponse(status: Int, body: String, retryAfterHeader: String?, secrets: List<String?>): PhotoDuelError {
        val parsed: ErrorBodyDto? = try {
            RemoteJson.response.decodeFromString(ErrorEnvelopeDto.serializer(), body).error
        } catch (e: IllegalArgumentException) {
            null
        }
        val code = parsed?.code?.let(SERVER_CODES::get) ?: forStatus(status)
        val serverMessage = parsed?.message?.trim().orEmpty()
        val message = if (serverMessage.isEmpty()) defaultMessage(code) else redact(serverMessage, secrets)
        val retryAfter = parsed?.retryAfterSeconds?.takeIf { it >= 0 }
            ?: retryAfterHeader?.trim()?.toIntOrNull()?.takeIf { it >= 0 }
        return PhotoDuelError(
            code = code,
            message = message,
            retryable = isRetryable(code),
            retryAfterSeconds = retryAfter,
        )
    }

    /** Error for a request that never produced an HTTP response. */
    fun transport(e: IOException): PhotoDuelError =
        if (e is SocketTimeoutException || (e is InterruptedIOException && e.message == "timeout")) {
            PhotoDuelError(ErrorCode.TIMEOUT, TIMEOUT_MESSAGE, retryable = true)
        } else {
            PhotoDuelError(ErrorCode.NETWORK, NETWORK_MESSAGE, retryable = true)
        }

    /** A 2xx response whose body does not match the contract. */
    fun unreadable(): PhotoDuelError = PhotoDuelError(ErrorCode.UNKNOWN, UNREADABLE_MESSAGE, retryable = false)

    /** An authenticated call while no session is stored. Nothing is sent. */
    fun notSignedIn(): PhotoDuelError = PhotoDuelError(ErrorCode.UNAUTHENTICATED, NOT_SIGNED_IN_MESSAGE, retryable = false)

    /** The session store failed (for example, the device keystore is unavailable). */
    fun sessionStorage(): PhotoDuelError = PhotoDuelError(ErrorCode.UNKNOWN, SESSION_STORAGE_MESSAGE, retryable = false)

    fun redact(message: String, secrets: List<String?>): String =
        secrets.filterNotNull().filter { it.isNotEmpty() }.fold(message) { text, secret -> text.replace(secret, REDACTED) }

    fun defaultMessage(code: ErrorCode): String = when (code) {
        ErrorCode.VALIDATION -> "The request was not valid."
        ErrorCode.UNAUTHENTICATED -> "The session is no longer valid. Sign in again."
        ErrorCode.FORBIDDEN -> "This action is not allowed."
        ErrorCode.NOT_FOUND -> "Not found."
        ErrorCode.INVITE_UNAVAILABLE -> "This invitation is no longer available."
        ErrorCode.CONFLICT -> "The request conflicts with the current state."
        ErrorCode.INVALID_TRANSITION -> "The challenge is not in a state that allows this."
        ErrorCode.ALREADY_VOTED -> "You have already voted in this challenge."
        ErrorCode.ROSTER_FROZEN -> "Membership cannot change while a challenge is running."
        ErrorCode.ROOM_FULL -> "The room is full."
        ErrorCode.DEADLINE_PASSED -> "The deadline has passed."
        ErrorCode.PAYLOAD_TOO_LARGE -> "The upload is too large."
        ErrorCode.UNSUPPORTED_MEDIA -> "This image cannot be used."
        ErrorCode.INVALID_BALLOT -> "That photo cannot receive this vote."
        ErrorCode.RATE_LIMITED -> "Too many requests. Try again later."
        ErrorCode.SERVER -> "The server had a problem. Try again later."
        ErrorCode.NETWORK -> NETWORK_MESSAGE
        ErrorCode.TIMEOUT -> TIMEOUT_MESSAGE
        ErrorCode.UNKNOWN -> "Something went wrong."
    }

    const val NETWORK_MESSAGE = "The server could not be reached. Check the connection and try again."
    const val TIMEOUT_MESSAGE = "The server took too long to answer. Try again."
    const val UNREADABLE_MESSAGE = "The server sent a response this app cannot read."
    const val NOT_SIGNED_IN_MESSAGE = "Not signed in."
    const val SESSION_STORAGE_MESSAGE = "The saved session could not be read or written on this device."
}
