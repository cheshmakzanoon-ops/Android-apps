package com.agency.timeflip.data

import android.database.sqlite.SQLiteException
import com.agency.timeflip.domain.error.ErrorCategory
import com.agency.timeflip.domain.error.TimeFlipError
import com.agency.timeflip.domain.error.TimeFlipException
import java.io.IOException

/**
 * True when an IO or SQLite failure means the device is out of space. Matches the SQLITE_FULL
 * result code and the ENOSPC errno text that the platform reports for a full filesystem.
 * Messages are checked, never logged, so no file names leave this function.
 */
internal fun Throwable.isDiskFull(): Boolean = when (this) {
    is SQLiteException -> message.orEmpty().let { it.contains("SQLITE_FULL") || it.contains("disk is full") }
    is IOException -> message.orEmpty().let { it.contains("ENOSPC") || it.contains("No space left", ignoreCase = true) }
    else -> false
}

internal fun corruptRow(detail: String, cause: Throwable? = null): TimeFlipException =
    TimeFlipException(TimeFlipError(ErrorCategory.INVALID_PROJECT, detail), cause)

internal fun storageFull(detail: String, cause: Throwable? = null): TimeFlipException =
    TimeFlipException(TimeFlipError(ErrorCategory.STORAGE_FULL, detail), cause)
