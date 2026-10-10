package com.agency.stickerjet.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import com.agency.stickerjet.R
import com.agency.stickerjet.domain.BackgroundMode
import com.agency.stickerjet.domain.StickerLimits
import com.agency.stickerjet.domain.StickerProject
import com.agency.stickerjet.ui.common.StickerFormat
import com.agency.stickerjet.ui.components.LoadingPanel
import com.agency.stickerjet.ui.components.StatusKind
import com.agency.stickerjet.ui.components.StatusPanel
import com.agency.stickerjet.ui.components.StickerPrimaryButton
import com.agency.stickerjet.ui.components.StickerSecondaryButton
import com.agency.stickerjet.ui.theme.PillShape
import com.agency.stickerjet.ui.theme.Spacing
import com.agency.stickerjet.ui.theme.StickerShapes
import com.agency.stickerjet.ui.theme.StickerTheme
import java.io.File
import kotlin.math.roundToInt

/**
 * Preview tab: the rendered loop (the same pipeline as export, at preview size), then the export
 * controls. A failed export shows its reason and keeps the draft, and a finished export offers Share
 * and a "Saved in library" badge.
 */
@Composable
fun PreviewTab(
    state: EditorUiState,
    project: StickerProject,
    onRefresh: () -> Unit,
    onStartExport: () -> Unit,
    onCancelExport: () -> Unit,
    onDismissExport: () -> Unit,
    onShare: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        when (val loop = state.previewLoop) {
            is LoopState.Ready -> PreviewLoop(loop = loop, removing = project.background == BackgroundMode.REMOVE_BACKGROUND)
            LoopState.Failed -> StatusPanel(
                kind = StatusKind.WARNING,
                title = stringResource(R.string.preview_failed_title),
                body = stringResource(R.string.preview_failed_body),
                primaryLabel = stringResource(R.string.action_retry),
                onPrimary = onRefresh,
            )
            LoopState.Idle, LoopState.Loading -> LoadingPanel(message = stringResource(R.string.preview_rendering))
        }
        ExportSection(
            state = state,
            onStart = onStartExport,
            onCancel = onCancelExport,
            onDismiss = onDismissExport,
            onShare = onShare,
        )
    }
}

/** The looping animation, with pause and a summary of its frames and length. */
@Composable
private fun PreviewLoop(loop: LoopState.Ready<Bitmap>, removing: Boolean) {
    val images: List<ImageBitmap> = remember(loop.frames) { loop.frames.map { it.asImageBitmap() } }
    var playing by remember(loop.frames) { mutableStateOf(!StickerTheme.reducedMotion) }
    val loopMs = loop.durationsMs.sum().toLong()
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        LoopPlayer(
            images = images,
            durationsMs = loop.durationsMs,
            playing = playing,
            contentDescription = stringResource(R.string.preview_loop_cd),
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        PlayPauseButton(playing = playing, onToggle = { playing = !playing })
        Text(
            text = stringResource(R.string.preview_frames_summary, loop.totalFrames, StickerFormat.seconds(loopMs)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (removing) {
            Text(
                text = stringResource(R.string.preview_subject_count, loop.subjectFrames, loop.totalFrames),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Export controls for every export state. The draft is never removed by a failure. */
@Composable
private fun ExportSection(
    state: EditorUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onShare: (File) -> Unit,
) {
    when (val export = state.export) {
        ExportState.Idle -> IdleExport(sourceMissing = state.sourceMissing, onStart = onStart)
        is ExportState.Running -> LoadingPanel(
            message = stringResource(R.string.preview_exporting, (export.progress * PERCENT).roundToInt()),
            progress = export.progress,
            cancelLabel = stringResource(R.string.preview_export_cancel),
            onCancel = onCancel,
        )
        is ExportState.Done -> ExportDoneCard(export = export, onShare = onShare, onDismiss = onDismiss)
        is ExportState.Failed -> StatusPanel(
            kind = StatusKind.ERROR,
            title = stringResource(R.string.export_failed_title),
            body = stringResource(R.string.export_failed_body, exportFailureMessage(export.reason)),
            primaryLabel = stringResource(R.string.action_retry),
            onPrimary = onStart,
            secondaryLabel = stringResource(R.string.preview_keep_editing),
            onSecondary = onDismiss,
        )
    }
}

@Composable
private fun IdleExport(sourceMissing: Boolean, onStart: () -> Unit) {
    if (sourceMissing) {
        StatusPanel(
            kind = StatusKind.WARNING,
            title = stringResource(R.string.export_source_missing_title),
            body = stringResource(R.string.export_source_missing_body),
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = stringResource(R.string.preview_export_hint, StickerLimits.MAX_EXPORT_BYTES / BYTES_PER_KB),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StickerPrimaryButton(
            text = stringResource(R.string.preview_export),
            onClick = onStart,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Success card: the sticker is saved, and it can be shared as a file. */
@Composable
private fun ExportDoneCard(export: ExportState.Done, onShare: (File) -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val extra = StickerTheme.extra
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(StickerShapes.large)
            .background(colors.surfaceContainer)
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.preview_export_done_title),
            style = MaterialTheme.typography.titleLarge,
            color = colors.onSurface,
        )
        Box(
            modifier = Modifier
                .clip(PillShape)
                .background(extra.accent)
                .padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
        ) {
            Text(
                text = stringResource(R.string.preview_saved),
                style = MaterialTheme.typography.labelMedium,
                color = extra.onAccent,
            )
        }
        Text(
            text = stringResource(
                R.string.preview_export_meta,
                StickerFormat.kilobytes(export.record.sizeBytes),
                export.totalFrames,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        StickerPrimaryButton(
            text = stringResource(R.string.preview_share),
            onClick = { onShare(export.file) },
            modifier = Modifier.fillMaxWidth(),
        )
        StickerSecondaryButton(
            text = stringResource(R.string.preview_keep_editing),
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val PERCENT: Float = 100f
private const val BYTES_PER_KB: Long = 1024L
