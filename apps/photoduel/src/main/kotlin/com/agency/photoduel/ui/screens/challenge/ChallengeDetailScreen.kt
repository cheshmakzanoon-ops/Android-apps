package com.agency.photoduel.ui.screens.challenge

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agency.photoduel.R
import com.agency.photoduel.domain.model.ChallengeState
import com.agency.photoduel.domain.rules.RosterRules
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
import com.agency.photoduel.ui.common.RefreshOnReturn
import com.agency.photoduel.ui.common.ScrollColumn
import com.agency.photoduel.ui.common.SectionHeading
import com.agency.photoduel.ui.common.StaleNotice
import com.agency.photoduel.ui.common.challengeStateLabel
import com.agency.photoduel.ui.common.photoFrameRatio
import com.agency.photoduel.ui.common.rememberScreenState
import com.agency.photoduel.ui.common.rememberServerNow
import com.agency.photoduel.ui.state.ChallengeConfirm
import com.agency.photoduel.ui.state.ChallengeData
import com.agency.photoduel.ui.state.ChallengeDetailState
import com.agency.photoduel.ui.state.ChallengePhase
import com.agency.photoduel.ui.state.valueOrNull
import com.agency.photoduel.ui.theme.LocalPdPalette
import com.agency.photoduel.ui.theme.PdSpace

/**
 * A challenge in any state. What the viewer can do next comes from the server's view only
 * ([ChallengePhase]); the countdown is display-only and asks the server again when it reaches zero.
 */
@Composable
fun ChallengeDetailScreen(
    env: AppEnv,
    challengeId: String,
    onBack: () -> Unit,
    onOpenSubmission: () -> Unit,
    onOpenVoting: () -> Unit,
    onOpenResults: () -> Unit,
) {
    val state = rememberScreenState(challengeId) { scope ->
        ChallengeDetailState(scope, env.deps.service, env.network, env.deps.clock::nowEpochMillis, challengeId)
    }
    val data by state.data.collectAsStateWithLifecycle()
    val ui by state.ui.collectAsStateWithLifecycle()
    RefreshOnReturn(state::refresh)

    val title = data.valueOrNull?.challenge?.roundNumber?.let { stringResource(R.string.challenge_title, it) }
        ?: stringResource(R.string.challenge_title_loading)
    PdScreen(
        title = title,
        onBack = onBack,
        actions = {
            IconButton(onClick = state::refresh) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) { padding ->
        LoadableContent(loadable = data, onRetry = state::refresh, modifier = Modifier.padding(padding)) { d, stale ->
            ScrollColumn(padding) {
                if (stale) StaleNotice(onRefresh = state::refresh)
                ui.error?.let { InlineError(it, onDismiss = state::dismissError) }
                PromptCard(d)
                d.deadline?.let { deadline ->
                    val serverNow = rememberServerNow(d.anchor, env.deps.clock, env.liveClock)
                    CountdownChip(
                        deadlineEpochMillis = deadline,
                        serverNowMillis = serverNow,
                        label = if (d.challenge.state == ChallengeState.VOTING) CountdownLabel.VOTING else CountdownLabel.SUBMISSIONS,
                        onDeadlineReached = state::onDeadlineReached,
                    )
                }
                PhaseSection(
                    env = env,
                    data = d,
                    busy = ui.busy,
                    onStart = state::start,
                    onCancel = { state.ask(ChallengeConfirm.CANCEL_CHALLENGE) },
                    onOpenSubmission = onOpenSubmission,
                    onOpenVoting = onOpenVoting,
                    onOpenResults = onOpenResults,
                )
                HorizontalDivider()
                SectionHeading(stringResource(R.string.challenge_roster_header, d.view.roster.size))
                d.view.roster.forEach { member ->
                    val name = if (member.userId == env.viewerId) stringResource(R.string.room_member_you, member.displayName) else member.displayName
                    Text(text = name, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }

    if (ui.confirm == ChallengeConfirm.CANCEL_CHALLENGE) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_cancel_challenge_title),
            message = stringResource(R.string.confirm_cancel_challenge_body),
            confirmLabel = stringResource(R.string.room_cancel_challenge),
            onConfirm = state::cancel,
            onDismiss = state::dismissConfirm,
            dismissLabel = stringResource(R.string.action_keep),
        )
    }
}

@Composable
private fun PromptCard(d: ChallengeData) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, LocalPdPalette.current.border, MaterialTheme.shapes.large)
            .padding(PdSpace.lg),
        verticalArrangement = Arrangement.spacedBy(PdSpace.xs),
    ) {
        Text(
            text = stringResource(R.string.challenge_prompt_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = d.challenge.prompt.title,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(text = d.challenge.prompt.brief, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = challengeStateLabel(d.challenge.state),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun PhaseSection(
    env: AppEnv,
    data: ChallengeData,
    busy: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onOpenSubmission: () -> Unit,
    onOpenVoting: () -> Unit,
    onOpenResults: () -> Unit,
) {
    val canCancel = data.isOwner &&
        (data.challenge.state == ChallengeState.CREATED || data.challenge.state == ChallengeState.OPEN_FOR_SUBMISSIONS)
    when (data.phase) {
        ChallengePhase.WAITING_TO_START -> {
            if (data.isOwner) {
                val enough = data.view.roster.size >= RosterRules.MIN_MEMBERS_TO_START
                BodyText(stringResource(R.string.challenge_waiting_owner))
                PdButton(text = stringResource(R.string.room_start_challenge), onClick = onStart, enabled = enough, loading = busy)
                if (!enough) {
                    BodyText(stringResource(R.string.room_start_needs_members, RosterRules.MIN_MEMBERS_TO_START))
                }
            } else {
                BodyText(stringResource(R.string.challenge_waiting_member))
            }
        }
        ChallengePhase.SUBMIT_PHOTO -> {
            BodyText(stringResource(R.string.challenge_submit_body))
            PdButton(text = stringResource(R.string.challenge_submit_cta), onClick = onOpenSubmission)
        }
        ChallengePhase.PHOTO_SUBMITTED -> {
            InlineNotice(text = stringResource(R.string.challenge_submitted))
            data.mySubmission?.let { mine ->
                PhotoCard(
                    model = env.deps.service.mediaUrl(mine.thumbnailMediaId),
                    frameRatio = photoFrameRatio(mine.width, mine.height),
                    description = stringResource(R.string.my_photo_cd),
                ) {
                    Text(stringResource(R.string.vote_your_photo), style = MaterialTheme.typography.labelLarge)
                }
            }
            PdButton(text = stringResource(R.string.challenge_change_photo), onClick = onOpenSubmission, style = PdButtonStyle.SECONDARY)
        }
        ChallengePhase.VOTE -> {
            BodyText(stringResource(R.string.challenge_vote_body))
            PdButton(text = stringResource(R.string.challenge_vote_cta), onClick = onOpenVoting)
        }
        ChallengePhase.VOTE_CAST -> {
            InlineNotice(text = stringResource(R.string.challenge_vote_cast))
            PdButton(text = stringResource(R.string.challenge_view_photos), onClick = onOpenVoting, style = PdButtonStyle.SECONDARY)
        }
        ChallengePhase.RESULTS -> PdButton(text = stringResource(R.string.challenge_results_cta), onClick = onOpenResults)
        ChallengePhase.CANCELLED -> InlineNotice(text = stringResource(R.string.challenge_cancelled))
        ChallengePhase.ARCHIVED -> {
            InlineNotice(text = stringResource(R.string.challenge_archived))
            PdButton(text = stringResource(R.string.challenge_results_cta), onClick = onOpenResults, style = PdButtonStyle.SECONDARY)
        }
        ChallengePhase.WATCHING -> InlineNotice(text = stringResource(R.string.challenge_watching))
    }
    if (canCancel) {
        PdButton(
            text = stringResource(R.string.room_cancel_challenge),
            onClick = onCancel,
            style = PdButtonStyle.TERTIARY,
            enabled = !busy,
        )
    }
}

@Composable
private fun BodyText(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge)
}
