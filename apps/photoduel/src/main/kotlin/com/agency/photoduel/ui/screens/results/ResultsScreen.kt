package com.agency.photoduel.ui.screens.results

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agency.photoduel.R
import com.agency.photoduel.domain.model.ChallengeOutcome
import com.agency.photoduel.domain.model.ChallengeState
import com.agency.photoduel.domain.model.MemberRole
import com.agency.photoduel.domain.model.SubmissionStatus
import com.agency.photoduel.ui.common.AppEnv
import com.agency.photoduel.ui.common.ConfirmDialog
import com.agency.photoduel.ui.common.EmptyState
import com.agency.photoduel.ui.common.InlineError
import com.agency.photoduel.ui.common.InlineNotice
import com.agency.photoduel.ui.common.LoadableContent
import com.agency.photoduel.ui.common.PdButton
import com.agency.photoduel.ui.common.PdButtonStyle
import com.agency.photoduel.ui.common.PdScreen
import com.agency.photoduel.ui.common.PhotoCard
import com.agency.photoduel.ui.common.ScrollColumn
import com.agency.photoduel.ui.common.SectionHeading
import com.agency.photoduel.ui.common.photoFrameRatio
import com.agency.photoduel.ui.common.rememberScreenState
import com.agency.photoduel.ui.state.ResultRow
import com.agency.photoduel.ui.state.ResultsModel
import com.agency.photoduel.ui.state.ResultsState
import com.agency.photoduel.ui.state.RevealStage
import com.agency.photoduel.ui.theme.LocalPdPalette
import com.agency.photoduel.ui.theme.LocalReduceMotion
import com.agency.photoduel.ui.theme.PdMotion
import com.agency.photoduel.ui.theme.PdSpace

/**
 * Results as published by the server. The tally bars grow, then the winner (or every co-winner of a
 * tie) is revealed. With reduced motion the final tally and the winner appear at once. Insufficient
 * outcomes say plainly that there is no winner. Nothing is computed or invented on the phone.
 */
@Composable
fun ResultsScreen(
    env: AppEnv,
    challengeId: String,
    onBack: () -> Unit,
    onOpenRoom: (String) -> Unit,
    onRematch: (roomId: String, previousPromptId: String) -> Unit,
) {
    val systemReduce = LocalReduceMotion.current
    val state = rememberScreenState(challengeId) { scope ->
        ResultsState(scope, env.deps.service, env.network, challengeId, reduceMotion = systemReduce)
    }
    val data by state.data.collectAsStateWithLifecycle()
    val ui by state.ui.collectAsStateWithLifecycle()
    val reduce = systemReduce || state.reduceMotion

    PdScreen(title = stringResource(R.string.results_title), onBack = onBack) { padding ->
        LoadableContent(loadable = data, onRetry = state::reload, modifier = Modifier.padding(padding)) { loaded, _ ->
            val model = loaded.model
            val view = loaded.view
            if (model == null) {
                EmptyState(
                    title = stringResource(R.string.results_none),
                    message = stringResource(R.string.results_none_body),
                    modifier = Modifier.padding(padding),
                ) {
                    PdButton(text = stringResource(R.string.results_back_to_room), onClick = { onOpenRoom(view.challenge.roomId) })
                }
            } else {
                val reveal = remember(challengeId) { Animatable(if (reduce || ui.stage == RevealStage.WINNER) 1f else 0f) }
                LaunchedEffect(model.outcome, reduce) {
                    if (reduce) {
                        reveal.snapTo(1f)
                    } else {
                        reveal.animateTo(1f, tween(PdMotion.REVEAL_MS, easing = FastOutSlowInEasing))
                    }
                    state.showWinner()
                }
                val mine = view.submissions.firstOrNull { it.isMine && it.status == SubmissionStatus.ACTIVE }
                val isOwner = view.roster.any { it.userId == env.viewerId && it.role == MemberRole.OWNER }
                val finished = view.challenge.state == ChallengeState.COMPLETED || view.challenge.state == ChallengeState.ARCHIVED
                ScrollColumn(padding) {
                    Text(
                        text = view.challenge.prompt.title,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    ui.error?.let { InlineError(it, onDismiss = state::dismissError) }
                    if (ui.photoDeleted) InlineNotice(text = stringResource(R.string.results_photo_deleted))
                    if (ui.stage == RevealStage.WINNER) {
                        if (reduce) {
                            OutcomeSection(env, model)
                        } else {
                            val appear = remember { MutableTransitionState(false) }.apply { targetState = true }
                            AnimatedVisibility(visibleState = appear, enter = fadeIn(tween(PdMotion.LONG_MS))) {
                                Column(verticalArrangement = Arrangement.spacedBy(PdSpace.md)) { OutcomeSection(env, model) }
                            }
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.results_counting),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    TallySection(model = model, reveal = reveal.value)
                    if (mine != null && finished) {
                        PdButton(
                            text = stringResource(R.string.results_delete_my_photo),
                            onClick = state::askDeleteMyPhoto,
                            style = PdButtonStyle.TERTIARY,
                            loading = ui.deleting,
                        )
                    }
                    if (isOwner && finished) {
                        PdButton(
                            text = stringResource(R.string.results_rematch),
                            onClick = { onRematch(view.challenge.roomId, view.challenge.prompt.id) },
                        )
                    }
                    PdButton(
                        text = stringResource(R.string.results_back_to_room),
                        onClick = { onOpenRoom(view.challenge.roomId) },
                        style = PdButtonStyle.SECONDARY,
                    )
                }
                if (ui.confirmDelete && mine != null) {
                    ConfirmDialog(
                        title = stringResource(R.string.confirm_delete_photo_title),
                        message = stringResource(R.string.confirm_delete_photo_body),
                        confirmLabel = stringResource(R.string.action_delete),
                        onConfirm = { state.deleteMyPhoto(mine.id) },
                        onDismiss = state::dismissDelete,
                    )
                }
            }
        }
    }
}

@Composable
private fun OutcomeSection(env: AppEnv, model: ResultsModel) {
    when (model.outcome) {
        ChallengeOutcome.WINNER -> {
            SectionHeading(stringResource(R.string.results_winner_heading))
            model.winners.forEach { WinnerCard(env, it) }
        }
        ChallengeOutcome.TIE -> {
            SectionHeading(stringResource(R.string.results_tie_heading))
            Text(stringResource(R.string.results_tie_body, model.winners.size), style = MaterialTheme.typography.bodyLarge)
            model.winners.forEach { WinnerCard(env, it) }
        }
        ChallengeOutcome.INSUFFICIENT_SUBMISSIONS -> {
            SectionHeading(stringResource(R.string.results_no_winner_title))
            Text(stringResource(R.string.results_insufficient_submissions_body), style = MaterialTheme.typography.bodyLarge)
        }
        ChallengeOutcome.INSUFFICIENT_BALLOTS -> {
            SectionHeading(stringResource(R.string.results_no_winner_title))
            Text(stringResource(R.string.results_insufficient_ballots_body), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun rowName(row: ResultRow): String = row.submission?.authorDisplayName ?: stringResource(R.string.results_photo_removed)

@Composable
private fun WinnerCard(env: AppEnv, row: ResultRow) {
    val votes = pluralStringResource(R.plurals.results_votes, row.votes, row.votes)
    val name = rowName(row)
    val submission = row.submission
    if (submission == null) {
        InlineNotice(text = stringResource(R.string.results_winner_removed, votes))
    } else {
        PhotoCard(
            model = env.deps.service.mediaUrl(submission.fullMediaId),
            frameRatio = photoFrameRatio(submission.width, submission.height),
            description = stringResource(R.string.results_winner_cd, name, votes),
        ) {
            Text(text = name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(text = votes, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun TallySection(model: ResultsModel, reveal: Float) {
    val palette = LocalPdPalette.current
    SectionHeading(stringResource(R.string.results_tally_heading))
    Text(
        text = stringResource(R.string.results_ballots, model.ballotCount, model.eligibleVoterCount),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (model.rows.isEmpty()) {
        Text(stringResource(R.string.results_no_tally), style = MaterialTheme.typography.bodyLarge)
    }
    model.rows.forEach { row ->
        val name = rowName(row)
        val votes = pluralStringResource(R.plurals.results_votes, row.votes, row.votes)
        val spoken = if (row.isWinner) {
            stringResource(R.string.results_row_winner_a11y, name, votes)
        } else {
            stringResource(R.string.results_row_a11y, name, votes)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics { contentDescription = spoken },
            verticalArrangement = Arrangement.spacedBy(PdSpace.xxs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(text = votes, style = MaterialTheme.typography.labelLarge)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .background(palette.bgSunken, MaterialTheme.shapes.small),
            ) {
                val width = (row.fraction * reveal).coerceIn(0f, 1f)
                if (width > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(width)
                            .height(12.dp)
                            .background(
                                if (row.isWinner) MaterialTheme.colorScheme.primary else palette.borderStrong,
                                MaterialTheme.shapes.small,
                            ),
                    )
                }
            }
        }
    }
}
