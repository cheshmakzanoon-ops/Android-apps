package com.agency.stickerjet.ui.importing

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.agency.stickerjet.R
import com.agency.stickerjet.domain.ImportRejection
import com.agency.stickerjet.domain.StickerLimits
import com.agency.stickerjet.ui.common.StickerFormat
import com.agency.stickerjet.ui.ports.ImportFailureKind

/** Why a clip was rejected, in words that say what to do next. Limits come from [StickerLimits]. */
@Composable
fun importRejectionMessage(reason: ImportRejection): String = when (reason) {
    ImportRejection.NOT_A_VIDEO -> stringResource(R.string.import_reject_not_video)
    ImportRejection.UNREADABLE -> stringResource(R.string.import_reject_unreadable)
    ImportRejection.TOO_SHORT -> stringResource(
        R.string.import_reject_too_short,
        StickerFormat.seconds(StickerLimits.MIN_STICKER_MS),
    )
    ImportRejection.TOO_LONG -> stringResource(R.string.import_reject_too_long, StickerLimits.MAX_SOURCE_MS / MS_PER_SECOND)
    ImportRejection.TOO_LARGE -> stringResource(R.string.import_reject_too_large, StickerLimits.MAX_SOURCE_BYTES / BYTES_PER_MB)
}

/** Why an import failed for a reason other than the clip itself. */
@Composable
fun importFailureMessage(cause: ImportFailureKind): String = when (cause) {
    ImportFailureKind.ACCESS_REVOKED -> stringResource(R.string.import_fail_access)
    ImportFailureKind.LOW_STORAGE -> stringResource(R.string.import_fail_storage)
    ImportFailureKind.IO_ERROR -> stringResource(R.string.import_fail_io)
}

private const val MS_PER_SECOND: Long = 1_000L
private const val BYTES_PER_MB: Long = 1024L * 1024L
