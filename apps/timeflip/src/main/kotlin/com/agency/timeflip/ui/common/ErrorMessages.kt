package com.agency.timeflip.ui.common

import androidx.annotation.StringRes
import com.agency.timeflip.R
import com.agency.timeflip.domain.error.ErrorCategory

/** Plain-language message for each error category. Every category has one, so none is shown raw. */
@StringRes
fun ErrorCategory.messageRes(): Int = when (this) {
    ErrorCategory.UNSUPPORTED_FORMAT -> R.string.error_unsupported_format_message
    ErrorCategory.DECODE_FAILED -> R.string.error_decode_failed_message
    ErrorCategory.SOURCE_MISSING -> R.string.error_source_missing_message
    ErrorCategory.PERMISSION_DENIED -> R.string.error_permission_denied_message
    ErrorCategory.STORAGE_FULL -> R.string.error_storage_full_message
    ErrorCategory.ENCODER_UNAVAILABLE -> R.string.error_encoder_unavailable_message
    ErrorCategory.EXPORT_FAILED -> R.string.error_export_failed_message
    ErrorCategory.INVALID_PROJECT -> R.string.error_invalid_project_message
    ErrorCategory.NOT_FOUND -> R.string.error_not_found_message
}

/** The clear next step that goes with [messageRes]. */
@StringRes
fun ErrorCategory.nextStepRes(): Int = when (this) {
    ErrorCategory.UNSUPPORTED_FORMAT -> R.string.error_unsupported_format_next
    ErrorCategory.DECODE_FAILED -> R.string.error_decode_failed_next
    ErrorCategory.SOURCE_MISSING -> R.string.error_source_missing_next
    ErrorCategory.PERMISSION_DENIED -> R.string.error_permission_denied_next
    ErrorCategory.STORAGE_FULL -> R.string.error_storage_full_next
    ErrorCategory.ENCODER_UNAVAILABLE -> R.string.error_encoder_unavailable_next
    ErrorCategory.EXPORT_FAILED -> R.string.error_export_failed_next
    ErrorCategory.INVALID_PROJECT -> R.string.error_invalid_project_next
    ErrorCategory.NOT_FOUND -> R.string.error_not_found_next
}
