@file:OptIn(ExperimentalMaterial3Api::class)

package com.agency.timeflip.ui.screens.export

import android.text.format.Formatter
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agency.timeflip.R
import com.agency.timeflip.domain.model.ExportedVideo
import com.agency.timeflip.platform.ExportLeaveGuard
import com.agency.timeflip.platform.shareVideo
import com.agency.timeflip.ui.UiDependencies
import com.agency.timeflip.ui.common.BackTopBar
import com.agency.timeflip.ui.common.ConfirmDialog
import com.agency.timeflip.ui.common.FailurePanel
import com.agency.timeflip.ui.common.formatDate
import com.agency.timeflip.ui.common.timeline
import com.agency.timeflip.ui.components.PrimaryButton
import com.agency.timeflip.ui.components.SecondaryButton
import com.agency.timeflip.ui.navigation.exportFactory
import com.agency.timeflip.ui.screens.editor.presetLabelRes
import com.agency.timeflip.ui.theme.TimeFlipColor
import com.agency.timeflip.ui.theme.TimeFlipRadius
import com.agency.timeflip.ui.theme.TimeFlipSpacing
import com.agency.timeflip.ui.TimeFlipTheme
import com.agency.timeflip.domain.model.TimeFlipProject
import java.io.File

@Composable
fun ExportRoute(
    deps: UiDependencies,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val vm: ExportViewModel = viewModel<ExportViewModel>(factory = exportFactory(deps))
    val state by vm.state.collectAsStateWithLifecycle()
    var leaveRequested by remember { mutableStateOf(false) }

    // While rendering, Back asks first. Leaving cancels the export and keeps no partial file.
    ExportLeaveGuard(exporting = state.phase is ExportPhase.Rendering) { leaveRequested = true }

    ExportScreenContent(
        state = state,
        exportsDir = deps.exportsDir,
        onBack = onBack,
        onStart = vm::start,
        onCancel = vm::cancel,
        onRetry = vm::retry,
        onDone = onDone,
    )

    if (leaveRequested) {
        ConfirmDialog(
            title = stringResource(R.string.export_leave_title),
            body = stringResource(R.string.export_leave_body),
            confirmText = stringResource(R.string.export_leave_confirm),
            onConfirm = {
                leaveRequested = false
                vm.cancel()
                onBack()
            },
            onDismiss = { leaveRequested = false },
        )
    }
}

/** Stateless export screen. Previews and UI tests use it with sample state. */
@Composable
fun ExportScreenContent(
    state: ExportUiState,
    exportsDir: File,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    var shareFailed by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { BackTopBar(title = stringResource(R.string.export_title), onBack = onBack) },
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
            val loadFailure = state.loadFailure
            val project = state.project
            when {
                loadFailure != null -> FailurePanel(failure = loadFailure, onRetry = onRetry)
                project == null -> CircularProgressIndicator(color = TimeFlipColor.Accent)
                else -> {
                    Text(
                        text = summaryText(project, context),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TimeFlipColor.OnSurfaceVariant,
                    )
                    PhaseBody(
                        phase = state.phase,
                        onStart = onStart,
                        onCancel = onCancel,
                        onRetry = onRetry,
                        onShare = { video ->
                            shareFailed = !shareVideo(context, File(exportsDir, video.fileName))
                        },
                        onDone = onDone,
                    )
                    if (shareFailed) {
                        Text(
                            text = stringResource(R.string.export_share_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TimeFlipColor.Error,
                        )
                    }
                }
            }

            if (state.previous.isNotEmpty()) {
                HistorySection(state.previous, context)
            }
        }
    }
}

@Composable
private fun PhaseBody(
    phase: ExportPhase,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onShare: (ExportedVideo) -> Unit,
    onDone: () -> Unit,
) {
    when (phase) {
        ExportPhase.Idle -> {
            Text(
                text = stringResource(R.string.export_ready),
                style = MaterialTheme.typography.bodyLarge,
                color = TimeFlipColor.OnBackground,
            )
            PrimaryButton(
                text = stringResource(R.string.export_start),
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is ExportPhase.Rendering -> {
            val progressName = stringResource(R.string.export_progress_cd)
            Text(
                text = stringResource(R.string.export_rendering, phase.framesDone, phase.framesTotal),
                style = MaterialTheme.typography.titleMedium,
                color = TimeFlipColor.OnBackground,
            )
            LinearProgressIndicator(
                progress = { phase.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = progressName },
                color = TimeFlipColor.Accent,
            )
            SecondaryButton(
                text = stringResource(R.string.export_cancel),
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ExportPhase.Recording -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = TimeFlipColor.Accent)
            Spacer(Modifier.width(TimeFlipSpacing.Space12))
            Text(
                text = stringResource(R.string.export_recording),
                style = MaterialTheme.typography.bodyLarge,
                color = TimeFlipColor.OnBackground,
            )
        }

        is ExportPhase.Finished -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = TimeFlipColor.Positive,
                )
                Spacer(Modifier.width(TimeFlipSpacing.Space8))
                Text(
                    text = stringResource(R.string.export_success_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = TimeFlipColor.OnBackground,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                text = stringResource(R.string.export_success_body),
                style = MaterialTheme.typography.bodyMedium,
                color = TimeFlipColor.OnSurfaceVariant,
            )
            PrimaryButton(
                text = stringResource(R.string.export_share),
                onClick = { onShare(phase.video) },
                modifier = Modifier.fillMaxWidth(),
            )
            SecondaryButton(
                text = stringResource(R.string.common_done),
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is ExportPhase.Failed -> FailurePanel(failure = phase.failure, onRetry = onRetry)

        ExportPhase.Cancelled -> {
            Text(
                text = stringResource(R.string.export_cancelled_title),
                style = MaterialTheme.typography.titleMedium,
                color = TimeFlipColor.OnBackground,
            )
            Text(
                text = stringResource(R.string.export_cancelled_body),
                style = MaterialTheme.typography.bodyMedium,
                color = TimeFlipColor.OnSurfaceVariant,
            )
            PrimaryButton(
                text = stringResource(R.string.export_start),
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun HistorySection(previous: List<ExportedVideo>, context: android.content.Context) {
    Column(verticalArrangement = Arrangement.spacedBy(TimeFlipSpacing.Space8)) {
        Text(
            text = stringResource(R.string.export_history_heading),
            style = MaterialTheme.typography.titleMedium,
            color = TimeFlipColor.OnBackground,
            modifier = Modifier.semantics { heading() },
        )
        previous.forEach { video ->
            Text(
                text = stringResource(
                    R.string.export_history_item,
                    formatDate(video.createdAtEpochMs),
                    Formatter.formatShortFileSize(context, video.sizeBytes),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = TimeFlipColor.OnSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TimeFlipColor.Surface, RoundedCornerShape(TimeFlipRadius.Small))
                    .padding(TimeFlipSpacing.Space12),
            )
        }
    }
}

private fun summaryText(project: TimeFlipProject, context: android.content.Context): String {
    val timeline = project.timeline()
    return context.getString(
        R.string.export_summary,
        context.getString(presetLabelRes(project.exportProfile.preset)),
        project.exportProfile.fps,
        context.getString(R.string.common_seconds, timeline.totalDurationMs / 1000f),
    )
}

@Preview(name = "Export ready", widthDp = 390, heightDp = 844)
@Composable
private fun ExportReadyPreview() {
    TimeFlipTheme {
        ExportScreenContent(
            state = ExportUiState(loading = false, project = com.agency.timeflip.ui.screens.editor.previewProject()),
            exportsDir = File("."),
            onBack = {},
            onStart = {},
            onCancel = {},
            onRetry = {},
            onDone = {},
        )
    }
}
