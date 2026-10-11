package com.agency.photoduel.engine.pure

import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.PhotoDuelError

/**
 * Every way photo preparation can fail on purpose. Each entry carries the API error code the UI
 * should treat it as, a message that is safe to show (no file names, no URIs, no pixel data), and
 * whether trying again can help.
 */
enum class PhotoPreparationFailure(val code: ErrorCode, val userMessage: String, val retryable: Boolean) {
    /** The chosen item could not be opened or read. Choosing it again may work. */
    SOURCE_UNREADABLE(ErrorCode.UNKNOWN, "The photo could not be read. Please choose it again.", true),

    /** Not JPEG, PNG or WebP. */
    UNSUPPORTED_FORMAT(ErrorCode.UNSUPPORTED_MEDIA, "Only JPEG, PNG and WebP photos can be used.", false),

    /** The bytes carry a supported signature but no image could be decoded from them. */
    DECODE_FAILED(ErrorCode.UNSUPPORTED_MEDIA, "This photo could not be opened. It may be damaged.", false),

    /** More than 40 megapixels. */
    TOO_MANY_PIXELS(ErrorCode.UNSUPPORTED_MEDIA, "This photo is too large. The limit is 40 megapixels.", false),

    /** Short edge under 200 px. */
    TOO_SMALL(ErrorCode.UNSUPPORTED_MEDIA, "This photo is too small. Its short side must be at least 200 pixels.", false),

    /** The source file is larger than the pipeline will read. */
    SOURCE_TOO_LARGE(ErrorCode.PAYLOAD_TOO_LARGE, "This photo file is too large to prepare.", false),

    /** The result still exceeds an upload limit after using the lowest quality. */
    OUTPUT_TOO_LARGE(ErrorCode.PAYLOAD_TOO_LARGE, "This photo is too large to upload even after shrinking it.", false),

    /** The device ran out of memory while decoding, even at the smallest allowed sampling. */
    OUT_OF_MEMORY(ErrorCode.UNKNOWN, "Not enough memory to prepare this photo. Close other apps and try again.", true),

    /** The platform JPEG encoder failed. */
    ENCODE_FAILED(ErrorCode.UNKNOWN, "The photo could not be prepared. Please try again.", true),
    ;

    fun toError(): PhotoDuelError = PhotoDuelError(code = code, message = userMessage, retryable = retryable)

    companion object {
        fun from(violation: UploadViolation): PhotoPreparationFailure = when (violation) {
            UploadViolation.UNSUPPORTED_FORMAT -> UNSUPPORTED_FORMAT
            UploadViolation.INVALID_DIMENSIONS -> DECODE_FAILED
            UploadViolation.TOO_MANY_PIXELS -> TOO_MANY_PIXELS
            UploadViolation.SHORT_EDGE_TOO_SMALL -> TOO_SMALL
            UploadViolation.BODY_TOO_LARGE,
            UploadViolation.FULL_TOO_LARGE,
            UploadViolation.THUMBNAIL_TOO_LARGE,
            -> OUTPUT_TOO_LARGE
        }
    }
}

/**
 * Thrown by `PhotoProcessing.prepare` when a photo cannot be prepared. [error] is ready for the UI.
 * Cancellation is never wrapped: a cancelled `prepare` throws `CancellationException`.
 * The message and the cause never contain a URI, a file name or image bytes.
 */
class PhotoPreparationException(
    val failure: PhotoPreparationFailure,
    cause: Throwable? = null,
) : Exception(failure.userMessage, cause) {
    val error: PhotoDuelError get() = failure.toError()
}
