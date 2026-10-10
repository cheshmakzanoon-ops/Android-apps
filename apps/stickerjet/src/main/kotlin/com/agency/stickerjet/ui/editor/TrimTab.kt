package com.agency.stickerjet.ui.editor

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.agency.stickerjet.R
import com.agency.stickerjet.domain.ArgbFrame
import com.agency.stickerjet.domain.StickerLimits
import com.agency.stickerjet.domain.StickerProject
import com.agency.stickerjet.ui.common.StickerFormat
import com.agency.stickerjet.ui.common.toImageBitmap
import com.agency.stickerjet.ui.components.LabeledSlider
import com.agency.stickerjet.ui.components.StatusPanel
import com.agency.stickerjet.ui.components.StatusKind
import com.agency.stickerjet.ui.theme.Spacing
import com.agency.stickerjet.ui.theme.StickerTheme

/**
 * Trim tab: two handles inside the source, a duration label, and a loop of the trimmed window. The
 * sliders report drafts while dragging and commit one undo step on release.
 */
@Composable
fun TrimTab(
    project: StickerProject,
    loop: LoopState<ArgbFrame>,
    onStartDrag: (Float) -> Unit,
    onEndDrag: (Float) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val trim = project.trim
    val sourceMs = project.source.durationMs
    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(horizontal = Spacing.gutter, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            text = stringResource(R.string.trim_length_label, StickerFormat.seconds(trim.durationMs)),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(
                R.string.trim_limits_hint,
                StickerFormat.seconds(StickerLimits.MIN_STICKER_MS),
                StickerFormat.seconds(StickerLimits.MAX_STICKER_MS),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TrimLoopBox(loop)
        LabeledSlider(
            label = stringResource(R.string.trim_start),
            valueText = stringResource(R.string.trim_seconds_value, StickerFormat.seconds(trim.startMs)),
            value = trim.startMs.toFloat(),
            valueRange = TrimEditor.startRange(sourceMs),
            onValueChange = onStartDrag,
            onValueChangeFinished = onFinished,
        )
        LabeledSlider(
            label = stringResource(R.string.trim_end),
            valueText = stringResource(R.string.trim_seconds_value, StickerFormat.seconds(trim.endMs)),
            value = trim.endMs.toFloat(),
            valueRange = TrimEditor.endRange(sourceMs),
            onValueChange = onEndDrag,
            onValueChangeFinished = onFinished,
        )
        Text(
            text = stringResource(R.string.trim_source_hint, StickerFormat.seconds(sourceMs)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
        )
    }
}

/** Shows the trim loop in a square, or a status message while it is loading or unavailable. */
@Composable
private fun TrimLoopBox(loop: LoopState<ArgbFrame>) {
    when (loop) {
        is LoopState.Ready -> {
            val images: List<ImageBitmap> = remember(loop.frames) { loop.frames.map { it.toImageBitmap() } }
            val reduced = StickerTheme.reducedMotion
            var playing by remember { mutableStateOf(!reduced) }
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                LoopPlayer(
                    images = images,
                    durationsMs = loop.durationsMs,
                    playing = playing,
                    contentDescription = stringResource(R.string.trim_loop_cd),
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                )
                PlayPauseButton(playing = playing, onToggle = { playing = !playing })
            }
        }
        LoopState.Failed -> StatusPanel(
            kind = StatusKind.WARNING,
            title = stringResource(R.string.trim_loop_failed_title),
            body = stringResource(R.string.trim_loop_failed_body),
        )
        LoopState.Idle, LoopState.Loading -> StatusPanel(
            kind = StatusKind.EMPTY,
            title = stringResource(R.string.trim_loop_loading),
            body = "",
        )
    }
}
