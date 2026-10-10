@file:OptIn(ExperimentalMaterial3Api::class)

package com.agency.timeflip.ui.screens.manualalign

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agency.timeflip.R
import com.agency.timeflip.domain.model.AlignmentTransform
import com.agency.timeflip.domain.model.PixelSize
import com.agency.timeflip.ui.BitmapLoader
import com.agency.timeflip.ui.TimeFlipTheme
import com.agency.timeflip.ui.UiDependencies
import com.agency.timeflip.ui.common.BackTopBar
import com.agency.timeflip.ui.common.FailurePanel
import com.agency.timeflip.ui.common.PREVIEW_MAX_EDGE
import com.agency.timeflip.ui.common.rememberPhotoBitmap
import com.agency.timeflip.ui.components.BottomActionBar
import com.agency.timeflip.ui.components.ComparisonDivider
import com.agency.timeflip.ui.components.LabeledSlider
import com.agency.timeflip.ui.components.PrimaryButton
import com.agency.timeflip.ui.components.SecondaryButton
import com.agency.timeflip.ui.navigation.manualAlignFactory
import com.agency.timeflip.ui.theme.TimeFlipColor
import com.agency.timeflip.ui.theme.TimeFlipRadius
import com.agency.timeflip.ui.theme.TimeFlipSize
import com.agency.timeflip.ui.theme.TimeFlipSpacing
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

@Composable
fun ManualAlignRoute(
    deps: UiDependencies,
    onFinished: () -> Unit,
) {
    val vm: ManualAlignViewModel = viewModel<ManualAlignViewModel>(factory = manualAlignFactory(deps))
    val state by vm.state.collectAsStateWithLifecycle()
    val before = rememberPhotoBitmap(state.pair?.before?.id, deps.bitmaps, PREVIEW_MAX_EDGE)
    val after = rememberPhotoBitmap(state.pair?.after?.id, deps.bitmaps, PREVIEW_MAX_EDGE)

    BackHandler { vm.finish(onFinished) }

    ManualAlignScreenContent(
        state = state,
        before = before,
        after = after,
        onBack = { vm.finish(onFinished) },
        onGestureDelta = vm::onGestureDelta,
        onGestureEnd = vm::onGestureEnd,
        onScaleChange = vm::onScaleChange,
        onRotationChange = vm::onRotationChange,
        onSliderFinished = vm::onSliderFinished,
        onOverlayOpacityChange = vm::onOverlayOpacityChange,
        onDividerChange = vm::onDividerChange,
        onReset = vm::reset,
        onFit = vm::fit,
        onUndo = vm::undo,
        onRedo = vm::redo,
        onDone = { vm.finish(onFinished) },
    )
}

/** Stateless manual alignment screen. Previews use it with sample state and no bitmaps. */
@Composable
fun ManualAlignScreenContent(
    state: ManualAlignUiState,
    before: Bitmap?,
    after: Bitmap?,
    onBack: () -> Unit,
    onGestureDelta: (Double, Double, Double, Double) -> Unit,
    onGestureEnd: () -> Unit,
    onScaleChange: (Double) -> Unit,
    onRotationChange: (Double) -> Unit,
    onSliderFinished: () -> Unit,
    onOverlayOpacityChange: (Float) -> Unit,
    onDividerChange: (Float) -> Unit,
    onReset: () -> Unit,
    onFit: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDone: () -> Unit,
) {
    val transform = state.transform
    Scaffold(
        topBar = {
            BackTopBar(
                title = stringResource(R.string.manual_title),
                onBack = onBack,
                actions = {
                    IconButton(onClick = onUndo, enabled = state.canUndo) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = stringResource(R.string.common_undo_cd),
                            tint = if (state.canUndo) TimeFlipColor.OnBackground else TimeFlipColor.Outline,
                        )
                    }
                    IconButton(onClick = onRedo, enabled = state.canRedo) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Redo,
                            contentDescription = stringResource(R.string.common_redo_cd),
                            tint = if (state.canRedo) TimeFlipColor.OnBackground else TimeFlipColor.Outline,
                        )
                    }
                },
            )
        },
        bottomBar = {
            BottomActionBar {
                SecondaryButton(
                    text = stringResource(R.string.manual_reset),
                    onClick = onReset,
                    enabled = !state.loading,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    text = stringResource(R.string.manual_fit),
                    onClick = onFit,
                    enabled = !state.loading && state.pair != null,
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    text = stringResource(R.string.common_done),
                    onClick = onDone,
                    enabled = !state.loading,
                    modifier = Modifier.weight(1f),
                )
            }
        },
        containerColor = TimeFlipColor.Background,
    ) { padding ->
        val loadFailure = state.loadFailure
        if (loadFailure != null) {
            Column(modifier = Modifier.padding(padding).padding(TimeFlipSpacing.Space16)) {
                FailurePanel(failure = loadFailure)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = TimeFlipSpacing.Space16),
        ) {
            Text(
                text = stringResource(R.string.manual_gesture_hint),
                style = MaterialTheme.typography.bodySmall,
                color = TimeFlipColor.OnSurfaceVariant,
                modifier = Modifier.padding(vertical = TimeFlipSpacing.Space8),
            )

            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = Modifier.aspectRatio(state.frame.aspectRatio.toFloat(), matchHeightConstraintsFirst = true)) {
                    AlignmentCanvas(
                        before = before,
                        after = after,
                        frame = state.frame,
                        transform = transform,
                        overlayOpacity = state.overlayOpacity,
                        dividerFraction = state.dividerFraction,
                        onGestureDelta = onGestureDelta,
                        onGestureEnd = onGestureEnd,
                        modifier = Modifier.fillMaxSize(),
                    )
                    ComparisonDivider(
                        fraction = state.dividerFraction,
                        onFractionChange = onDividerChange,
                        label = stringResource(R.string.manual_divider_cd),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            if (state.hasEmptyEdges) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = TimeFlipSpacing.Space8)
                        .background(TimeFlipColor.AccentContainer, RoundedCornerShape(TimeFlipRadius.Small))
                        .padding(TimeFlipSpacing.Space12),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = TimeFlipColor.Accent,
                    )
                    Spacer(Modifier.width(TimeFlipSpacing.Space8))
                    Text(
                        text = stringResource(R.string.manual_coverage_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = TimeFlipColor.OnAccentContainer,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = CONTROLS_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = TimeFlipSpacing.Space8),
                verticalArrangement = Arrangement.spacedBy(TimeFlipSpacing.Space8),
            ) {
                LabeledSlider(
                    label = stringResource(R.string.manual_overlay_label),
                    valueText = stringResource(R.string.manual_percent_value, (state.overlayOpacity * 100).toInt()),
                    value = state.overlayOpacity,
                    onValueChange = onOverlayOpacityChange,
                    valueRange = 0f..1f,
                )
                LabeledSlider(
                    label = stringResource(R.string.manual_divider_label),
                    valueText = stringResource(R.string.manual_percent_value, (state.dividerFraction * 100).toInt()),
                    value = state.dividerFraction,
                    onValueChange = onDividerChange,
                    valueRange = 0f..1f,
                )
                LabeledSlider(
                    label = stringResource(R.string.manual_scale_label),
                    valueText = stringResource(R.string.manual_scale_value, transform.scale),
                    value = transform.scale.toFloat(),
                    onValueChange = { onScaleChange(it.toDouble()) },
                    valueRange = AlignmentTransform.MIN_SCALE.toFloat()..AlignmentTransform.MAX_SCALE.toFloat(),
                    onValueChangeFinished = onSliderFinished,
                )
                LabeledSlider(
                    label = stringResource(R.string.manual_rotation_label),
                    valueText = stringResource(R.string.manual_rotation_value, transform.rotationDegrees),
                    value = transform.rotationDegrees.toFloat(),
                    onValueChange = { onRotationChange(it.toDouble()) },
                    valueRange = (-AlignmentTransform.MAX_ROTATION_DEGREES).toFloat()..AlignmentTransform.MAX_ROTATION_DEGREES.toFloat(),
                    onValueChangeFinished = onSliderFinished,
                )
                Text(
                    text = stringResource(saveStateRes(state.saveState)),
                    style = MaterialTheme.typography.labelMedium,
                    color = TimeFlipColor.OnSurfaceVariant,
                )
            }
        }
    }
}

private fun saveStateRes(state: SaveState): Int = when (state) {
    SaveState.SAVED -> R.string.manual_saved
    SaveState.PENDING -> R.string.manual_saving
    SaveState.FAILED -> R.string.manual_save_failed
}

private val CONTROLS_MAX_HEIGHT = 260.dp

@Preview(name = "Manual alignment", widthDp = 390, heightDp = 844)
@Composable
private fun ManualAlignPreview() {
    TimeFlipTheme {
        ManualAlignScreenContent(
            state = ManualAlignUiState(
                loading = false,
                transform = AlignmentTransform(scale = 1.2, rotationDegrees = 3.0, offsetX = 0.05, offsetY = -0.02),
                coverage = 0.9,
                canUndo = true,
            ),
            before = null,
            after = null,
            onBack = {},
            onGestureDelta = { _, _, _, _ -> },
            onGestureEnd = {},
            onScaleChange = {},
            onRotationChange = {},
            onSliderFinished = {},
            onOverlayOpacityChange = {},
            onDividerChange = {},
            onReset = {},
            onFit = {},
            onUndo = {},
            onRedo = {},
            onDone = {},
        )
    }
}
