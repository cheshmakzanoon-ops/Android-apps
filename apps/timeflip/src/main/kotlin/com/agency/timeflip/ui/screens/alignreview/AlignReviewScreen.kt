@file:OptIn(ExperimentalMaterial3Api::class)

package com.agency.timeflip.ui.screens.alignreview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agency.timeflip.R
import com.agency.timeflip.domain.error.ErrorCategory
import com.agency.timeflip.domain.error.TimeFlipError
import com.agency.timeflip.domain.model.AlignmentEstimate
import com.agency.timeflip.domain.model.AlignmentSource
import com.agency.timeflip.domain.model.AlignmentStatus
import com.agency.timeflip.ui.TimeFlipTheme
import com.agency.timeflip.ui.UiDependencies
import com.agency.timeflip.ui.common.BackTopBar
import com.agency.timeflip.ui.common.FailurePanel
import com.agency.timeflip.ui.common.UiFailure
import com.agency.timeflip.ui.components.PrimaryButton
import com.agency.timeflip.ui.components.SecondaryButton
import com.agency.timeflip.ui.components.TextActionButton
import com.agency.timeflip.ui.navigation.alignReviewFactory
import com.agency.timeflip.ui.theme.TimeFlipColor
import com.agency.timeflip.ui.theme.TimeFlipRadius
import com.agency.timeflip.ui.theme.TimeFlipSpacing
import kotlin.math.roundToInt

/** Plain-language threshold, shown to the user. Matches AlignmentEstimate.ACCEPT_CONFIDENCE. */
private val ACCEPT_PERCENT = (AlignmentEstimate.ACCEPT_CONFIDENCE * 100).roundToInt()

@Composable
fun AlignReviewRoute(
    deps: UiDependencies,
    onBack: () -> Unit,
    onOpenEditor: () -> Unit,
    onAdjustManually: () -> Unit,
    onChangePhotos: () -> Unit,
) {
    val vm: AlignReviewViewModel = viewModel<AlignReviewViewModel>(factory = alignReviewFactory(deps))
    val state by vm.state.collectAsStateWithLifecycle()
    AlignReviewScreenContent(
        state = state,
        onBack = onBack,
        onRun = vm::start,
        onCancel = vm::cancel,
        onOpenEditorWithEstimate = { vm.acceptEstimate(onOpenEditor) },
        onOpenEditorWithoutEstimate = { vm.openEditorWithoutEstimate(onOpenEditor) },
        onAdjustManually = { vm.adjustManually(onAdjustManually) },
        onReset = vm::resetAlignment,
        onRetry = vm::retry,
        onChangePhotos = onChangePhotos,
    )
}

/**
 * Shows the automatic result and the actions for it. A low-confidence or failed estimate is never
 * presented as the placement: its primary action is manual adjustment.
 */
@Composable
fun AlignReviewScreenContent(
    state: AlignReviewUiState,
    onBack: () -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onOpenEditorWithEstimate: () -> Unit,
    onOpenEditorWithoutEstimate: () -> Unit,
    onAdjustManually: () -> Unit,
    onReset: () -> Unit,
    onRetry: () -> Unit,
    onChangePhotos: () -> Unit,
) {
    val busy = state.busy
    Scaffold(
        topBar = { BackTopBar(title = stringResource(R.string.align_title), onBack = onBack) },
        containerColor = TimeFlipColor.Background,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = TimeFlipSpacing.Space16, vertical = TimeFlipSpacing.Space8),
            verticalArrangement = Arrangement.spacedBy(TimeFlipSpacing.Space16),
        ) {
            Text(
                text = stringResource(R.string.align_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = TimeFlipColor.OnSurfaceVariant,
            )
            Text(
                text = stringResource(savedSourceRes(state.savedSource)),
                style = MaterialTheme.typography.labelLarge,
                color = TimeFlipColor.OnBackground,
            )

            when (val phase = state.phase) {
                AlignPhase.Loading, AlignPhase.Running -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = TimeFlipColor.Accent)
                        Spacer(Modifier.width(TimeFlipSpacing.Space12))
                        Column {
                            Text(
                                text = stringResource(R.string.align_running),
                                style = MaterialTheme.typography.titleMedium,
                                color = TimeFlipColor.OnBackground,
                            )
                            Text(
                                text = stringResource(R.string.align_running_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TimeFlipColor.OnSurfaceVariant,
                            )
                        }
                    }
                    if (phase == AlignPhase.Running) {
                        SecondaryButton(
                            text = stringResource(R.string.align_cancel),
                            onClick = onCancel,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                AlignPhase.NotRun -> {
                    Text(
                        text = stringResource(R.string.align_not_run),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TimeFlipColor.OnBackground,
                    )
                    PrimaryButton(
                        text = stringResource(R.string.align_run),
                        onClick = onRun,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                AlignPhase.Cancelled -> {
                    Text(
                        text = stringResource(R.string.align_cancelled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TimeFlipColor.OnBackground,
                    )
                    PrimaryButton(
                        text = stringResource(R.string.align_run),
                        onClick = onRun,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SecondaryButton(
                        text = stringResource(R.string.align_adjust),
                        onClick = onAdjustManually,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SecondaryButton(
                        text = stringResource(R.string.align_open_editor),
                        onClick = onOpenEditorWithoutEstimate,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                is AlignPhase.Estimated -> EstimateBody(
                    estimate = phase.estimate,
                    busy = busy,
                    onOpenEditorWithEstimate = onOpenEditorWithEstimate,
                    onOpenEditorWithoutEstimate = onOpenEditorWithoutEstimate,
                    onAdjustManually = onAdjustManually,
                )

                is AlignPhase.Failed -> {
                    val sourceMissing = (phase.failure as? UiFailure.Known)?.error?.category == ErrorCategory.SOURCE_MISSING
                    FailurePanel(
                        failure = phase.failure,
                        onRetry = onRetry,
                        alternativeLabel = if (sourceMissing) stringResource(R.string.align_choose_photos_again) else null,
                        onAlternative = if (sourceMissing) onChangePhotos else null,
                    )
                    if (!sourceMissing) {
                        SecondaryButton(
                            text = stringResource(R.string.align_adjust),
                            onClick = onAdjustManually,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SecondaryButton(
                            text = stringResource(R.string.align_open_editor),
                            onClick = onOpenEditorWithoutEstimate,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            state.actionFailure?.let { FailurePanel(failure = it) }

            if (state.savedSource != AlignmentSource.UNALIGNED && state.phase !is AlignPhase.Loading) {
                TextActionButton(
                    text = stringResource(R.string.align_reset),
                    onClick = onReset,
                    enabled = !busy,
                )
            }
            TextActionButton(
                text = stringResource(R.string.align_change_photos),
                onClick = onChangePhotos,
                enabled = !busy,
            )
        }
    }
}

@Composable
private fun EstimateBody(
    estimate: AlignmentEstimate,
    busy: Boolean,
    onOpenEditorWithEstimate: () -> Unit,
    onOpenEditorWithoutEstimate: () -> Unit,
    onAdjustManually: () -> Unit,
) {
    val percent = (estimate.confidence * 100).roundToInt()
    val accepted = estimate.status == AlignmentStatus.ACCEPTED

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TimeFlipColor.Surface, RoundedCornerShape(TimeFlipRadius.Medium))
            .padding(TimeFlipSpacing.Space16),
        verticalArrangement = Arrangement.spacedBy(TimeFlipSpacing.Space8),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The icon and the words carry the status; colour only reinforces it.
            Icon(
                imageVector = if (accepted) Icons.Filled.Check else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (accepted) TimeFlipColor.Positive else TimeFlipColor.Accent,
            )
            Spacer(Modifier.width(TimeFlipSpacing.Space8))
            Text(
                text = stringResource(statusTitleRes(estimate.status)),
                style = MaterialTheme.typography.titleMedium,
                color = TimeFlipColor.OnBackground,
                modifier = Modifier.semantics { heading() },
            )
        }
        Text(
            text = stringResource(R.string.align_confidence, percent),
            style = MaterialTheme.typography.headlineSmall,
            color = TimeFlipColor.OnBackground,
        )
        Text(
            text = stringResource(statusMeaningRes(estimate.status)),
            style = MaterialTheme.typography.bodyMedium,
            color = TimeFlipColor.OnSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.align_threshold_note, ACCEPT_PERCENT),
            style = MaterialTheme.typography.bodySmall,
            color = TimeFlipColor.OnSurfaceVariant,
        )
    }

    if (accepted) {
        PrimaryButton(
            text = stringResource(R.string.align_open_editor),
            onClick = onOpenEditorWithEstimate,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        SecondaryButton(
            text = stringResource(R.string.align_adjust),
            onClick = onAdjustManually,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        PrimaryButton(
            text = stringResource(R.string.align_adjust),
            onClick = onAdjustManually,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.align_open_without_estimate),
            style = MaterialTheme.typography.bodySmall,
            color = TimeFlipColor.OnSurfaceVariant,
        )
        SecondaryButton(
            text = stringResource(R.string.align_open_editor),
            onClick = onOpenEditorWithoutEstimate,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun savedSourceRes(source: AlignmentSource): Int = when (source) {
    AlignmentSource.UNALIGNED -> R.string.align_saved_none
    AlignmentSource.AUTOMATIC -> R.string.align_saved_automatic
    AlignmentSource.MANUAL -> R.string.align_saved_manual
}

private fun statusTitleRes(status: AlignmentStatus): Int = when (status) {
    AlignmentStatus.ACCEPTED -> R.string.align_status_accepted
    AlignmentStatus.NEEDS_MANUAL -> R.string.align_status_needs_manual
    AlignmentStatus.FAILED -> R.string.align_status_failed
}

private fun statusMeaningRes(status: AlignmentStatus): Int = when (status) {
    AlignmentStatus.ACCEPTED -> R.string.align_meaning_accepted
    AlignmentStatus.NEEDS_MANUAL -> R.string.align_meaning_needs_manual
    AlignmentStatus.FAILED -> R.string.align_meaning_failed
}

@Preview(name = "Alignment, accepted", widthDp = 390, heightDp = 844)
@Composable
private fun AlignReviewAcceptedPreview() {
    TimeFlipTheme {
        AlignReviewScreenContent(
            state = AlignReviewUiState(
                phase = AlignPhase.Estimated(
                    AlignmentEstimate(
                        transform = com.agency.timeflip.domain.model.AlignmentTransform.IDENTITY,
                        confidence = 0.86,
                        inlierCount = 42,
                        status = AlignmentStatus.ACCEPTED,
                    ),
                ),
            ),
            onBack = {},
            onRun = {},
            onCancel = {},
            onOpenEditorWithEstimate = {},
            onOpenEditorWithoutEstimate = {},
            onAdjustManually = {},
            onReset = {},
            onRetry = {},
            onChangePhotos = {},
        )
    }
}
