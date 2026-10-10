package com.agency.timeflip.domain.error

/**
 * Stable error categories. The UI maps each category to user-facing text; [debugDetail] is for
 * developer logs only and must never contain file names, URIs or image content.
 */
enum class ErrorCategory {
    UNSUPPORTED_FORMAT,
    DECODE_FAILED,
    SOURCE_MISSING,
    PERMISSION_DENIED,
    STORAGE_FULL,
    ENCODER_UNAVAILABLE,
    EXPORT_FAILED,
    INVALID_PROJECT,
    NOT_FOUND,
}

data class TimeFlipError(
    val category: ErrorCategory,
    val debugDetail: String? = null,
) {
    /** True when the user can retry the same action without changing any input. */
    val retryable: Boolean
        get() = when (category) {
            ErrorCategory.UNSUPPORTED_FORMAT,
            ErrorCategory.INVALID_PROJECT,
            ErrorCategory.NOT_FOUND -> false
            else -> true
        }
}

/** Thrown by ports when an operation fails with a known category. */
class TimeFlipException(val error: TimeFlipError, cause: Throwable? = null) :
    RuntimeException(error.category.name, cause)
