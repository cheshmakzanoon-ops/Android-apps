package com.agency.stickerjet.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.agency.stickerjet.R
import com.agency.stickerjet.domain.StickerLimits
import com.agency.stickerjet.ui.ports.ExportFailureKind

/** The reason an export failed, in words the user can act on. Each reason implies a way forward. */
@Composable
fun exportFailureMessage(reason: ExportFailureKind): String = when (reason) {
    ExportFailureKind.SOURCE_MISSING -> stringResource(R.string.export_fail_source_missing)
    ExportFailureKind.TOO_LARGE -> stringResource(R.string.export_fail_too_large, StickerLimits.MAX_EXPORT_BYTES / BYTES_PER_KB)
    ExportFailureKind.LOW_STORAGE -> stringResource(R.string.export_fail_low_storage)
    ExportFailureKind.SEGMENTATION_FAILED -> stringResource(R.string.export_fail_segmentation)
    ExportFailureKind.INTERNAL -> stringResource(R.string.export_fail_internal)
}

private const val BYTES_PER_KB: Long = 1024L
