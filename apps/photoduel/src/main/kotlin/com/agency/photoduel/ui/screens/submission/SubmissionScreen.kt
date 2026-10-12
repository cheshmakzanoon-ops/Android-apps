package com.agency.photoduel.ui.screens.submission

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agency.photoduel.R
import com.agency.photoduel.domain.model.ChallengeState
import com.agency.photoduel.domain.model.ChallengeView
import com.agency.photoduel.ui.ProcessingStage
import com.agency.photoduel.ui.common.AppEnv
import com.agency.photoduel.ui.common.ConfirmDialog
import com.agency.photoduel.ui.common.CountdownChip
import com.agency.photoduel.ui.common.CountdownLabel
import com.agency.photoduel.ui.common.InlineError
import com.agency.photoduel.ui.common.InlineNotice
import com.agency.photoduel.ui.common.LoadableContent
import com.agency.photoduel.ui.common.PdButton
import com.agency.photoduel.ui.common.PdButtonStyle
import com.agency.photoduel.ui.common.PdScreen
import com.agency.photoduel.ui.common.PhotoCard
import com.agency.photoduel.ui.common.PhotoFrame
import com.agency.photoduel.ui.common.ScrollColumn
import com.agency.photoduel.ui.common.photoFrameRatio
import com.agency.photoduel.ui.common.rememberScreenState
import com.agency.photoduel.ui.common.rememberServerNow
import com.agency.photoduel.ui.state.PrepStage
import com.agency.photoduel.ui.state.ServerTimeAnchor
import com.agency.photoduel.ui.state.SubmissionFailure
import com.agency.photoduel.ui.state.SubmissionStage
import com.agency.photoduel.ui.state.SubmissionState
import com.agency.photoduel.ui.state.SubmissionUi
import com.agency.photoduel.ui.theme.PdSpace

private fun ProcessingStage.toPrepStage(): PrepStage = when (this) {
    ProcessingStage.READING -> PrepStage.READING
    ProcessingStage.DECODING -> PrepStage.DECODING
    ProcessingStage.ENCODING -> PrepStage.ENCODING
    ProcessingStage.DONE -> PrepStage.DONE
}

/**
 * Choose, preview, prepare and upload one photo; then replace or withdraw it until the deadline.
 * Picking goes through [com.agency.photoduel.ui.PhotoPicking] and preparation (resize, re-encode,
 * metadata removal) through [com.agency.photoduel.ui.PhotoProcessing]; the UI never reads the file.
 */
@Composable
fun SubmissionScreen(env: AppEnv, challengeId: String, onBack: () -> Unit) {
    val state = rememberScreenState(challengeId) { scope ->
        SubmissionState<Uri>(scope, env.deps.service, env.network, challengeId) { uri, onStage ->
            env.deps.photoProcessing.prepare(uri) { stage -> onStage(stage.toPrepStage()) }
        }
    }
    val ui by state.ui.collectAsStateWithLifecycle()
    val context by state.challenge.collectAsStateWithLifecycle()
    var confirmWithdraw by remember { mutableStateOf(false) }
    val pick: () -> Unit = { env.deps.photoPicking.pick { uri -> state.onPicked(uri) } }

    PdScreen(title = stringResource(R.string.submit_title), onBack = onBack) { padding ->
        LoadableContent(loadable = context, onRetry = state::reload, modifier = Modifier.padding(padding)) { view, _ ->
            val anchor = remember(view) { ServerTimeAnchor.of(view.challenge, env.deps.clock.nowEpochMillis()) }
            ScrollColumn(padding) {
                Text(text = view.challenge.prompt.title, style = MaterialTheme.typography.headlineSmall)
                Text(text = view.challenge.prompt.brief, style = MaterialTheme.typography.bodyLarge)
                val deadline = view.challenge.submissionDeadlineEpochMillis
                if (view.challenge.state == ChallengeState.OPEN_FOR_SUBMISSIONS && deadline != null) {
                    CountdownChip(
                        deadlineEpochMillis = deadline,
                        serverNowMillis = rememberServerNow(anchor, env.deps.clock, env.liveClock),
                        label = CountdownLabel.SUBMISSIONS,
                        onDeadlineReached = state::reload,
                    )
                }
                if (view.challenge.state != ChallengeState.OPEN_FOR_SUBMISSIONS && ui.stage != SubmissionStage.SUBMITTED) {
                    InlineNotice(text = stringResource(R.string.submit_closed), isError = true)
                } else {
                    StageContent(
                        env = env,
                        view = view,
                        ui = ui,
                        onPick = pick,
                        onConfirm = state::confirm,
                        onCancel = state::cancel,
                        onRetry = state::retry,
                        onChooseDifferent = state::chooseDifferent,
                        onKeepCurrent = state::keepCurrent,
                        onWithdraw = { confirmWithdraw = true },
                    )
                }
            }
        }
    }

    if (confirmWithdraw) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_withdraw_title),
            message = stringResource(R.string.confirm_withdraw_body),
            confirmLabel = stringResource(R.string.action_withdraw),
            onConfirm = {
                confirmWithdraw = false
                state.withdraw()
            },
            onDismiss = { confirmWithdraw = false },
        )
    }
}

@Composable
private fun StageContent(
    env: AppEnv,
    view: ChallengeView,
    ui: SubmissionUi<Uri>,
    onPick: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onChooseDifferent: () -> Unit,
    onKeepCurrent: () -> Unit,
    onWithdraw: () -> Unit,
) {
    val open = view.challenge.state == ChallengeState.OPEN_FOR_SUBMISSIONS
    when (ui.stage) {
        SubmissionStage.CHOOSE -> {
            Text(stringResource(R.string.submit_choose_body), style = MaterialTheme.typography.bodyLarge)
            if (ui.replacing) InlineNotice(text = stringResource(R.string.submit_replace_note))
            PdButton(text = stringResource(R.string.submit_choose), onClick = onPick)
            if (ui.replacing) {
                PdButton(text = stringResource(R.string.submit_keep_current), onClick = onKeepCurrent, style = PdButtonStyle.TERTIARY)
            }
        }
        SubmissionStage.PREVIEW -> {
            PhotoFrame(model = ui.source, frameRatio = 4f / 3f)
            Text(
                text = stringResource(R.string.submit_preview_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PdButton(text = stringResource(R.string.submit_use_photo), onClick = onConfirm)
            PdButton(text = stringResource(R.string.submit_choose_other), onClick = onPick, style = PdButtonStyle.SECONDARY)
            if (ui.replacing) {
                PdButton(text = stringResource(R.string.submit_keep_current), onClick = onKeepCurrent, style = PdButtonStyle.TERTIARY)
            }
        }
        SubmissionStage.PROCESSING -> {
            PhotoFrame(model = ui.source, frameRatio = 4f / 3f)
            val stageText = when (ui.prepStage) {
                PrepStage.DECODING -> stringResource(R.string.prep_decoding)
                PrepStage.ENCODING -> stringResource(R.string.prep_encoding)
                PrepStage.DONE -> stringResource(R.string.prep_done)
                PrepStage.READING, null -> stringResource(R.string.prep_reading)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PdSpace.sm),
                modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(PdSpace.xxs), strokeWidth = 3.dp)
                Text(text = stageText, style = MaterialTheme.typography.bodyLarge)
            }
            PdButton(text = stringResource(R.string.action_cancel), onClick = onCancel, style = PdButtonStyle.SECONDARY)
        }
        SubmissionStage.UPLOADING -> {
            PhotoFrame(model = ui.source, frameRatio = 4f / 3f)
            val percent = (ui.progress * 100).toInt().coerceIn(0, 100)
            val label = stringResource(R.string.submit_uploading, percent)
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            LinearProgressIndicator(progress = { ui.progress }, modifier = Modifier.fillMaxWidth())
            PdButton(text = stringResource(R.string.submit_cancel), onClick = onCancel, style = PdButtonStyle.SECONDARY)
        }
        SubmissionStage.FAILED -> {
            if (ui.source != null) PhotoFrame(model = ui.source, frameRatio = 4f / 3f)
            when (val failure = ui.failure) {
                SubmissionFailure.Processing -> InlineNotice(text = stringResource(R.string.submit_failed_processing), isError = true)
                is SubmissionFailure.Service -> InlineError(failure.error)
                null -> Unit
            }
            if (open) {
                PdButton(text = stringResource(R.string.action_retry), onClick = onRetry)
                PdButton(text = stringResource(R.string.submit_choose_other), onClick = onPick, style = PdButtonStyle.SECONDARY)
            }
        }
        SubmissionStage.SUBMITTED -> {
            val mine = ui.submitted
            Text(stringResource(R.string.submit_done_title), style = MaterialTheme.typography.titleLarge)
            if (mine != null) {
                PhotoCard(
                    model = env.deps.service.mediaUrl(mine.thumbnailMediaId),
                    frameRatio = photoFrameRatio(mine.width, mine.height),
                    description = stringResource(R.string.my_photo_cd),
                ) {
                    Text(stringResource(R.string.vote_your_photo), style = MaterialTheme.typography.labelLarge)
                }
            }
            (ui.failure as? SubmissionFailure.Service)?.let { InlineError(it.error) }
            if (open) {
                Text(stringResource(R.string.submit_done_body), style = MaterialTheme.typography.bodyLarge)
                PdButton(text = stringResource(R.string.submit_replace), onClick = onChooseDifferent, style = PdButtonStyle.SECONDARY)
                PdButton(
                    text = stringResource(R.string.submit_withdraw),
                    onClick = onWithdraw,
                    style = PdButtonStyle.TERTIARY,
                    loading = ui.withdrawing,
                )
            }
        }
    }
}
