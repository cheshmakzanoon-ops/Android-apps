@file:OptIn(ExperimentalMaterial3Api::class)

package com.agency.timeflip.ui.screens.editor

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import com.agency.timeflip.domain.model.OutputPreset
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agency.timeflip.R
import com.agency.timeflip.domain.model.AlignmentTransform
import com.agency.timeflip.domain.model.ImagePair
import com.agency.timeflip.domain.model.PixelSize
import com.agency.timeflip.domain.model.PhotoId
import com.agency.timeflip.domain.model.ProjectId
import com.agency.timeflip.domain.model.SourcePhoto
import com.agency.timeflip.domain.model.TimeFlipProject
import com.agency.timeflip.domain.rules.ProjectRules
import com.agency.timeflip.domain.model.PhotoSlot
import com.agency.timeflip.domain.model.Easing
import com.agency.timeflip.domain.model.TransitionKind
import com.agency.timeflip.ui.BitmapLoader
import com.agency.timeflip.ui.TimeFlipTheme
import com.agency.timeflip.ui.UiDependencies
import com.agency.timeflip.ui.common.BackTopBar
import com.agency.timeflip.ui.common.FailurePanel
import com.agency.timeflip.ui.common.PREVIEW_MAX_EDGE
import com.agency.timeflip.ui.common.frameIndexAt
import com.agency.timeflip.ui.common.previewFrame
import com.agency.timeflip.ui.common.progressAt
import com.agency.timeflip.ui.common.rememberPhotoBitmap
import com.agency.timeflip.ui.components.BottomActionBar
import com.agency.timeflip.ui.components.PrimaryButton
import com.agency.timeflip.ui.components.SecondaryButton
import com.agency.timeflip.ui.components.TextActionButton
import com.agency.timeflip.ui.navigation.editorFactory
import com.agency.timeflip.ui.theme.TimeFlipColor
import com.agency.timeflip.ui.theme.TimeFlipSize
import com.agency.timeflip.ui.theme.TimeFlipSpacing
import com.agency.timeflip.ui.theme.TimeFlipType

@Composable
fun EditorRoute(
    deps: UiDependencies,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onAdjustAlignment: () -> Unit,
    onChangePhotos: () -> Unit,
) {
    val vm: EditorViewModel = viewModel<EditorViewModel>(factory = editorFactory(deps))
    val state by vm.state.collectAsStateWithLifecycle()

    // The alignment may have been changed on the manual screen, so re-read the project on every resume.
    LifecycleResumeEffect(Unit) {
        vm.reload()
        onPauseOrDispose { }
    }

    // Playback clock: advances only while playing, one step per display frame.
    LaunchedEffect(state.playing) {
        if (state.playing) {
            var last = withFrameNanos { it }
            while (true) {
                val frameTime = withFrameNanos { it }
                vm.onFrameTick((frameTime - last) / NANOS_PER_MILLI)
                last = frameTime
            }
        }
    }

    val project = state.project
    val before = rememberPhotoBitmap(project?.pair?.before?.id, deps.bitmaps, PREVIEW_MAX_EDGE)
    val after = rememberPhotoBitmap(project?.pair?.after?.id, deps.bitmaps, PREVIEW_MAX_EDGE)

    EditorScreenContent(
        state = state,
        before = before,
        after = after,
        onBack = onBack,
        onTogglePlayback = vm::togglePlayback,
        onReplay = vm::replay,
        onScrub = vm::scrubTo,
        onKindSelected = vm::selectKind,
        onEasingSelected = vm::selectEasing,
        onReversedChange = vm::setReversed,
        onDurationPreview = vm::previewDurationMs,
        onDurationCommit = vm::commitDuration,
        onPresetSelected = vm::selectPreset,
        onExport = onExport,
        onAdjustAlignment = onAdjustAlignment,
        onChangePhotos = onChangePhotos,
    )
}

/** Stateless editor. Previews and UI tests use it with sample state. */
@Composable
fun EditorScreenContent(
    state: EditorUiState,
    before: Bitmap?,
    after: Bitmap?,
    onBack: () -> Unit,
    onTogglePlayback: () -> Unit,
    onReplay: () -> Unit,
    onScrub: (Int) -> Unit,
    onKindSelected: (TransitionKind) -> Unit,
    onEasingSelected: (Easing) -> Unit,
    onReversedChange: (Boolean) -> Unit,
    onDurationPreview: (Int) -> Unit,
    onDurationCommit: () -> Unit,
    onPresetSelected: (com.agency.timeflip.domain.model.OutputPreset) -> Unit,
    onExport: () -> Unit,
    onAdjustAlignment: () -> Unit,
    onChangePhotos: () -> Unit,
) {
    val project = state.project
    Scaffold(
        topBar = { BackTopBar(title = stringResource(R.string.editor_title), onBack = onBack) },
        bottomBar = {
            BottomActionBar {
                PrimaryButton(
                    text = stringResource(R.string.editor_export),
                    onClick = onExport,
                    enabled = project != null && !state.loading && state.loadFailure == null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        containerColor = TimeFlipColor.Background,
    ) { padding ->
        val failure = state.loadFailure
        when {
            failure != null -> Column(modifier = Modifier.padding(padding).padding(TimeFlipSpacing.Space16)) {
                FailurePanel(failure = failure)
            }
            project == null -> Column(
                modifier = Modifier.padding(padding).fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = TimeFlipColor.Accent)
                Spacer(Modifier.height(TimeFlipSpacing.Space8))
                Text(
                    text = stringResource(R.string.editor_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TimeFlipColor.OnSurfaceVariant,
                )
            }
            else -> EditorBody(
                state = state,
                project = project,
                before = before,
                after = after,
                padding = padding,
                onTogglePlayback = onTogglePlayback,
                onReplay = onReplay,
                onScrub = onScrub,
                onKindSelected = onKindSelected,
                onEasingSelected = onEasingSelected,
                onReversedChange = onReversedChange,
                onDurationPreview = onDurationPreview,
                onDurationCommit = onDurationCommit,
                onPresetSelected = onPresetSelected,
                onAdjustAlignment = onAdjustAlignment,
                onChangePhotos = onChangePhotos,
            )
        }
    }
}

@Composable
private fun EditorBody(
    state: EditorUiState,
    project: TimeFlipProject,
    before: Bitmap?,
    after: Bitmap?,
    padding: androidx.compose.foundation.layout.PaddingValues,
    onTogglePlayback: () -> Unit,
    onReplay: () -> Unit,
    onScrub: (Int) -> Unit,
    onKindSelected: (TransitionKind) -> Unit,
    onEasingSelected: (Easing) -> Unit,
    onReversedChange: (Boolean) -> Unit,
    onDurationPreview: (Int) -> Unit,
    onDurationCommit: () -> Unit,
    onPresetSelected: (com.agency.timeflip.domain.model.OutputPreset) -> Unit,
    onAdjustAlignment: () -> Unit,
    onChangePhotos: () -> Unit,
) {
    val timeline = project.timeline()
    val preview: PixelSize = project.exportProfile.preset.previewFrame()
    val total = timeline.totalDurationMs
    val position = state.positionMs.coerceIn(0, total)
    val progress = timeline.progressAt(position)
    val frameIndex = timeline.frameIndexAt(position)

    Column(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize()
            .padding(horizontal = TimeFlipSpacing.Space16),
    ) {
        Text(
            text = stringResource(R.string.editor_hint),
            style = MaterialTheme.typography.bodySmall,
            color = TimeFlipColor.OnSurfaceVariant,
            modifier = Modifier.padding(vertical = TimeFlipSpacing.Space8),
        )

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .aspectRatio(preview.aspectRatio.toFloat(), matchHeightConstraintsFirst = true)
                    .background(TimeFlipColor.Surface),
            ) {
                TransitionPreview(
                    before = before,
                    after = after,
                    frame = preview,
                    alignment = project.alignment,
                    transition = project.transition,
                    linearProgress = progress,
                    modifier = Modifier.semantics {
                        contentDescription = "${stringResource(R.string.editor_preview_cd)}, frame ${frameIndex + 1}"
                    },
                )
            }
        }

        PlaybackRow(
            playing = state.playing,
            position = position,
            total = total,
            onTogglePlayback = onTogglePlayback,
            onReplay = onReplay,
            onScrub = onScrub,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = CONTROLS_MAX_HEIGHT)
                .verticalScroll(rememberScrollState())
                .padding(vertical = TimeFlipSpacing.Space12),
            verticalArrangement = Arrangement.spacedBy(TimeFlipSpacing.Space16),
        ) {
            TransitionControls(
                transition = project.transition,
                onKindSelected = onKindSelected,
                onEasingSelected = onEasingSelected,
                onReversedChange = onReversedChange,
                onDurationPreview = onDurationPreview,
                onDurationCommit = onDurationCommit,
            )
            PresetChooser(
                selected = project.exportProfile.preset,
                onSelected = onPresetSelected,
            )
            state.saveFailure?.let { FailurePanel(failure = it) }
            SecondaryButton(
                text = stringResource(R.string.editor_adjust_alignment),
                onClick = onAdjustAlignment,
                modifier = Modifier.fillMaxWidth(),
            )
            TextActionButton(
                text = stringResource(R.string.editor_change_photos),
                onClick = onChangePhotos,
            )
        }
    }
}

@Composable
private fun PlaybackRow(
    playing: Boolean,
    position: Int,
    total: Int,
    onTogglePlayback: () -> Unit,
    onReplay: () -> Unit,
    onScrub: (Int) -> Unit,
) {
    val context = LocalContext.current
    val timeText = stringResource(
        R.string.editor_position,
        stringResource(R.string.common_seconds, position / MILLIS_PER_SECOND),
        stringResource(R.string.common_seconds, total / MILLIS_PER_SECOND),
    )
    Column(verticalArrangement = Arrangement.spacedBy(TimeFlipSpacing.Space4)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onTogglePlayback, modifier = Modifier.size(TimeFlipSize.MinTouchTarget)) {
                Icon(
                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (playing) R.string.editor_pause_cd else R.string.editor_play_cd),
                    tint = TimeFlipColor.OnBackground,
                )
            }
            IconButton(onClick = onReplay, modifier = Modifier.size(TimeFlipSize.MinTouchTarget)) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.editor_replay_cd),
                    tint = TimeFlipColor.OnBackground,
                )
            }
            Text(
                text = timeText,
                style = TimeFlipType.Readout.copy(fontSize = 16.sp, lineHeight = 22.sp),
                color = TimeFlipColor.OnBackground,
                modifier = Modifier.padding(start = TimeFlipSpacing.Space8),
            )
        }
        Slider(
            value = if (total > 0) position.toFloat() / total else 0f,
            onValueChange = { fraction -> onScrub((fraction * total).toInt()) },
            valueRange = 0f..1f,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = TimeFlipSize.MinTouchTarget)
                .semantics {
                    contentDescription = context.getString(R.string.editor_timeline_cd)
                    stateDescription = timeText
                },
        )
    }
}

private const val NANOS_PER_MILLI = 1_000_000L
private const val MILLIS_PER_SECOND = 1000f
private val CONTROLS_MAX_HEIGHT = 340.dp

@Preview(name = "Editor", widthDp = 390, heightDp = 844)
@Composable
private fun EditorPreview() {
    TimeFlipTheme {
        EditorScreenContent(
            state = EditorUiState(loading = false, project = previewProject(), positionMs = 1500),
            before = null,
            after = null,
            onBack = {},
            onTogglePlayback = {},
            onReplay = {},
            onScrub = {},
            onKindSelected = {},
            onEasingSelected = {},
            onReversedChange = {},
            onDurationPreview = {},
            onDurationCommit = {},
            onPresetSelected = {},
            onExport = {},
            onAdjustAlignment = {},
            onChangePhotos = {},
        )
    }
}

/** Sample project for previews only. It never reaches the repository. */
internal fun previewProject(): TimeFlipProject {
    val before = SourcePhoto(PhotoId("preview-before"), PhotoSlot.BEFORE, PixelSize(1200, 1600), 0L, 0L)
    val after = SourcePhoto(PhotoId("preview-after"), PhotoSlot.AFTER, PixelSize(1200, 1600), 0L, 0L)
    return ProjectRules.newProject(
        id = ProjectId("preview"),
        rawName = "Preview",
        pair = ImagePair(before, after),
        nowEpochMs = 0L,
    ).copy(alignment = AlignmentTransform.IDENTITY)
}
