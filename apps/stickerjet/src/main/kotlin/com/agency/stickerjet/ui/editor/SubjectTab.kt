package com.agency.stickerjet.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.agency.stickerjet.R
import com.agency.stickerjet.domain.ArgbFrame
import com.agency.stickerjet.domain.BackgroundMode
import com.agency.stickerjet.domain.NormalizedPoint
import com.agency.stickerjet.domain.StickerProject
import com.agency.stickerjet.domain.StrokeMode
import com.agency.stickerjet.ui.common.checkerboard
import com.agency.stickerjet.ui.common.toImageBitmap
import com.agency.stickerjet.ui.components.StickerChoiceRow
import com.agency.stickerjet.ui.components.StickerSecondaryButton
import com.agency.stickerjet.ui.theme.Spacing
import com.agency.stickerjet.ui.theme.StickerTheme
import com.agency.stickerjet.ui.theme.StickerTileShape
import com.agency.stickerjet.ui.theme.TouchTarget

/**
 * Subject tab: the background switch, person detection status, the matte preview and the manual brush.
 * Brush controls are enabled only when background removal is on and detection has run, because
 * strokes only affect the mask that removal uses.
 */
@Composable
fun SubjectTab(
    state: EditorUiState,
    project: StickerProject,
    onSetBackground: (BackgroundMode) -> Unit,
    onDetect: () -> Unit,
    onStroke: (StrokeMode, List<NormalizedPoint>, Float) -> Unit,
    onResetMask: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val removing = project.background == BackgroundMode.REMOVE_BACKGROUND
    val detected = state.detection == SubjectDetection.FOUND || state.detection == SubjectDetection.NO_PERSON
    val brushEnabled = removing && detected
    var modeIndex by rememberSaveable { mutableStateOf(0) }
    var sizeIndex by rememberSaveable { mutableStateOf(1) }
    val mode = if (modeIndex == 0) StrokeMode.RESTORE else StrokeMode.ERASE
    val brush = BrushSize.entries[sizeIndex]

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TouchTarget.min)
                .toggleable(
                    value = removing,
                    role = Role.Switch,
                    onValueChange = { on ->
                        onSetBackground(if (on) BackgroundMode.REMOVE_BACKGROUND else BackgroundMode.ORIGINAL)
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.subject_remove_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.subject_remove_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = removing, onCheckedChange = null)
        }

        DetectionStatus(state.detection, onDetect)

        SubjectStage(state = state, removing = removing, mode = mode, brush = brush, brushEnabled = brushEnabled, onStroke = onStroke)

        Text(
            text = stringResource(R.string.subject_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StickerChoiceRow(
            options = listOf(stringResource(R.string.subject_mode_restore), stringResource(R.string.subject_mode_erase)),
            selectedIndex = modeIndex,
            onSelect = { modeIndex = it },
            enabled = brushEnabled,
        )
        StickerChoiceRow(
            options = listOf(
                stringResource(R.string.subject_size_small),
                stringResource(R.string.subject_size_medium),
                stringResource(R.string.subject_size_large),
            ),
            selectedIndex = sizeIndex,
            onSelect = { sizeIndex = it },
            enabled = brushEnabled,
        )
        StickerSecondaryButton(
            text = stringResource(R.string.subject_reset),
            onClick = onResetMask,
            enabled = brushEnabled && project.maskStrokes.isNotEmpty(),
        )
    }
}

/** Shows the matte (or the original frame when removal is off), with the brush surface laid over it. */
@Composable
private fun SubjectStage(
    state: EditorUiState,
    removing: Boolean,
    mode: StrokeMode,
    brush: BrushSize,
    brushEnabled: Boolean,
    onStroke: (StrokeMode, List<NormalizedPoint>, Float) -> Unit,
) {
    val still = state.still
    val matte = state.matte
    val shown: ArgbFrame? = if (removing && matte != null) matte else still
    if (shown == null) {
        Text(
            text = stringResource(if (state.stillFailed) R.string.subject_still_failed else R.string.subject_still_loading),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val image: ImageBitmap = remember(shown) { shown.toImageBitmap() }
    val colors = StickerTheme.extra
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(shown.width.toFloat() / shown.height.toFloat())
            .clip(StickerTileShape)
            .checkerboard(colors.checkerA, colors.checkerB, CHECKER_CELL),
    ) {
        Image(
            bitmap = image,
            contentDescription = stringResource(
                if (removing) R.string.subject_matte_cd else R.string.subject_original_cd,
            ),
            modifier = Modifier.matchParentSize(),
        )
        BrushSurface(
            mode = mode,
            brush = brush,
            enabled = brushEnabled,
            onStroke = onStroke,
            modifier = Modifier.matchParentSize(),
        )
    }
}

/** One line that says what detection found, with a retry when it failed. Nothing is shown while idle. */
@Composable
private fun DetectionStatus(detection: SubjectDetection, onDetect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    when (detection) {
        SubjectDetection.IDLE -> Unit
        SubjectDetection.RUNNING -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp, color = colors.primary)
            Text(
                text = stringResource(R.string.subject_segmenting),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onBackground,
            )
        }
        SubjectDetection.FOUND -> Text(
            text = stringResource(R.string.subject_found),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onBackground,
        )
        SubjectDetection.NO_PERSON -> Text(
            text = stringResource(R.string.subject_no_person),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onBackground,
        )
        SubjectDetection.FAILED -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                text = stringResource(R.string.subject_failed),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onBackground,
            )
            StickerSecondaryButton(text = stringResource(R.string.subject_retry), onClick = onDetect)
        }
    }
}

private val CHECKER_CELL = 12.dp
