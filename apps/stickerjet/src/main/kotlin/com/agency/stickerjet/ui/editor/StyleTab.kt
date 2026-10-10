package com.agency.stickerjet.ui.editor

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.consume
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import com.agency.stickerjet.R
import com.agency.stickerjet.domain.CaptionPosition
import com.agency.stickerjet.domain.StickerProject
import com.agency.stickerjet.domain.Transform
import com.agency.stickerjet.ui.common.StickerFormat
import com.agency.stickerjet.ui.common.toImageBitmap
import com.agency.stickerjet.ui.components.LabeledSlider
import com.agency.stickerjet.ui.components.StickerChoiceRow
import com.agency.stickerjet.ui.components.StickerSecondaryButton
import com.agency.stickerjet.ui.theme.Spacing

/**
 * Style tab: placement (drag, size, quarter-turn rotation), the preview background, and the caption.
 * A drag accumulates from the placement at the start of the gesture, so the sticker follows the finger
 * even while the state is being redrawn.
 */
@Composable
fun StyleTab(
    state: EditorUiState,
    project: StickerProject,
    onPlacementDrag: (scale: Float, offsetX: Float, offsetY: Float) -> Unit,
    onPlacementFinished: () -> Unit,
    onRotate: () -> Unit,
    onResetPlacement: () -> Unit,
    onCaptionCommit: (String) -> Unit,
    onCaptionPosition: (CaptionPosition) -> Unit,
    onCaptionColor: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val transform = project.transform
    val latestTransform by rememberUpdatedState(transform)
    val latestDrag by rememberUpdatedState(onPlacementDrag)
    val latestFinish by rememberUpdatedState(onPlacementFinished)
    var stageWidth by remember { mutableFloatStateOf(1f) }
    var stageHeight by remember { mutableFloatStateOf(1f) }
    var darkBackground by rememberSaveable { mutableStateOf(false) }
    val frame = state.still
    val still: ImageBitmap? = remember(frame) { frame?.toImageBitmap() }

    val gestures = Modifier.pointerInput(Unit) {
        var base: Transform? = null
        var totalX = 0f
        var totalY = 0f
        detectDragGestures(
            onDragStart = {
                base = latestTransform
                totalX = 0f
                totalY = 0f
            },
            onDrag = { change, amount ->
                change.consume()
                val start = base ?: return@detectDragGestures
                totalX += amount.x
                totalY += amount.y
                latestDrag(
                    start.scale,
                    start.offsetX + totalX / stageWidth,
                    start.offsetY + totalY / stageHeight,
                )
            },
            onDragEnd = {
                base = null
                latestFinish()
            },
            onDragCancel = {
                base = null
                latestFinish()
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        StickerStage(
            still = still,
            transform = transform,
            caption = project.caption,
            darkBackground = darkBackground,
            contentDescription = stringResource(R.string.style_stage_cd),
            gestures = gestures,
            onStageSize = { w, h ->
                stageWidth = w
                stageHeight = h
            },
        )
        Text(
            text = stringResource(if (still == null) R.string.style_still_missing else R.string.style_drag_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StickerChoiceRow(
            options = listOf(stringResource(R.string.style_bg_light), stringResource(R.string.style_bg_dark)),
            selectedIndex = if (darkBackground) 1 else 0,
            onSelect = { darkBackground = it == 1 },
        )
        LabeledSlider(
            label = stringResource(R.string.style_size),
            valueText = stringResource(R.string.style_size_value, StickerFormat.factor(transform.scale)),
            value = transform.scale,
            valueRange = PlacementLimits.MIN_SCALE..PlacementLimits.MAX_SCALE,
            onValueChange = { scale ->
                val t = latestTransform
                latestDrag(scale, t.offsetX, t.offsetY)
            },
            onValueChangeFinished = onPlacementFinished,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            StickerSecondaryButton(text = stringResource(R.string.style_rotate), onClick = onRotate)
            StickerSecondaryButton(text = stringResource(R.string.style_reset_placement), onClick = onResetPlacement)
        }
        CaptionControls(
            caption = project.caption,
            onCommitText = onCaptionCommit,
            onPosition = onCaptionPosition,
            onColor = onCaptionColor,
        )
    }
}
