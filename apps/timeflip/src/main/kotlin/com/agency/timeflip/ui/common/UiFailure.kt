package com.agency.timeflip.ui.common

import com.agency.timeflip.domain.error.TimeFlipError
import com.agency.timeflip.domain.error.TimeFlipException

/**
 * A failure the screens can show. Unexpected exceptions are never shown with their message,
 * because platform messages can contain file paths or URIs.
 */
sealed interface UiFailure {
    data class Known(val error: TimeFlipError) : UiFailure

    data object Unexpected : UiFailure
}

fun Throwable.toUiFailure(): UiFailure = when (this) {
    is TimeFlipException -> UiFailure.Known(error)
    else -> UiFailure.Unexpected
}
