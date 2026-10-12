package com.agency.photoduel.ui.screens.voting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agency.photoduel.R
import com.agency.photoduel.domain.model.ChallengeState
import com.agency.photoduel.domain.model.ReportTarget
import com.agency.photoduel.domain.model.Submission
import com.agency.photoduel.ui.common.AppEnv
import com.agency.photoduel.ui.common.CountdownChip
import com.agency.photoduel.ui.common.CountdownLabel
import com.agency.photoduel.ui.common.EmptyState
import com.agency.photoduel.ui.common.InlineError
import com.agency.photoduel.ui.common.InlineNotice
import com.agency.photoduel.ui.common.LoadableContent
import com.agency.photoduel.ui.common.PdButton
import com.agency.photoduel.ui.common.PdScreen
import com.agency.photoduel.ui.common.PhotoCard
import com.agency.photoduel.ui.common.PhotoViewerDialog
import com.agency.photoduel.ui.common.ScrollColumn
import com.agency.photoduel.ui.common.SectionHeading
import com.agency.photoduel.ui.common.photoFrameRatio
import com.agency.photoduel.ui.common.rememberScreenState
import com.agency.photoduel.ui.common.rememberServerNow
import com.agency.photoduel.ui.screens.safety.ReportSheet
import com.agency.photoduel.ui.state.VotingData
import com.agency.photoduel.ui.state.VotingEvent
import com.agency.photoduel.ui.state.VotingStage
import com.agency.photoduel.ui.state.VotingState
import com.agency.photoduel.ui.state.VotingUi
import com.agency.photoduel.ui.state.valueOrNull
import com.agency.photoduel.ui.theme.PdSpace

/**
 * Voting: a short explanation, then every photo in full. A tap selects a photo (and can be changed); a
 * separate Confirm casts the ballot, naming the photo's author because votes are final. Selection is
 * shown by an outline and a caption, never on top of the photo. Haptics mark select, cast and refusal.
 */
@Composable
fun VotingScreen(env: AppEnv, challengeId: String, onBack: () -> Unit, onOpenResults: () -> Unit) {
    val state = rememberScreenState(challengeId) { scope ->
        VotingState(scope, env.deps.service, env.network, env.deps.clock::nowEpochMillis, challengeId)
    }
    val ui by state.ui.collectAsStateWithLifecycle()
    val data by state.data.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    var viewing by remember { mutableStateOf<Submission?>(null) }
    var reporting by remember { mutableStateOf<Submission?>(null) }

    LaunchedEffect(state) {
        state.events.collect { event ->
            haptics.performHapticFeedback(
                when (event) {
                    VotingEvent.SELECTED -> HapticFeedbackType.SegmentTick
                    VotingEvent.CAST -> HapticFeedbackType.Confirm
                    VotingEvent.REJECTED -> HapticFeedbackType.Reject
                },
            )
        }
    }

    val selected = data.valueOrNull?.candidates?.firstOrNull { it.id == ui.selectedId }
    PdScreen(
        title = stringResource(R.string.vote_title),
        onBack = onBack,
        bottomBar = {
            if (ui.stage == VotingStage.BALLOT && selected != null) {
                ConfirmBar(author = selected.authorDisplayName, casting = ui.casting, onConfirm = state::confirm)
            }
        },
    ) { padding ->
        LoadableContent(loadable = data, onRetry = state::reload, modifier = Modifier.padding(padding)) { d, _ ->
            when (ui.stage) {
                VotingStage.INTRO -> Intro(env, d, padding, onStart = state::startVoting)
                VotingStage.BALLOT, VotingStage.COMPLETED -> Ballot(
                    env = env,
                    data = d,
                    ui = ui,
                    padding = padding,
                    onSelect = { state.select(it) },
                    onView = { viewing = it },
                    onReport = { reporting = it },
                    onDismissError = state::clearError,
                )
                VotingStage.UNAVAILABLE -> EmptyState(
                    title = stringResource(R.string.vote_unavailable_title),
                    message = stringResource(R.string.vote_unavailable_body),
                    modifier = Modifier.padding(padding),
                ) {
                    ui.error?.let { InlineError(it) }
                    if (d.view.challenge.state == ChallengeState.COMPLETED || d.view.challenge.state == ChallengeState.ARCHIVED) {
                        PdButton(text = stringResource(R.string.challenge_results_cta), onClick = onOpenResults)
                    }
                }
            }
        }
    }

    viewing?.let { photo ->
        PhotoViewerDialog(
            model = env.deps.service.mediaUrl(photo.fullMediaId),
            description = stringResource(R.string.photo_by_cd, photo.authorDisplayName),
            onDismiss = { viewing = null },
        )
    }
    reporting?.let { photo ->
        ReportSheet(
            env = env,
            target = ReportTarget.Submission(photo.id),
            title = stringResource(R.string.report_title_photo),
            onDismiss = { reporting = null },
        )
    }
}

@Composable
private fun Intro(env: AppEnv, data: VotingData, padding: PaddingValues, onStart: () -> Unit) {
    ScrollColumn(padding) {
        Text(text = data.view.challenge.prompt.title, style = MaterialTheme.typography.headlineSmall)
        val deadline = data.view.challenge.votingDeadlineEpochMillis
        if (deadline != null && data.view.challenge.state == ChallengeState.VOTING) {
            CountdownChip(
                deadlineEpochMillis = deadline,
                serverNowMillis = rememberServerNow(data.anchor, env.deps.clock, env.liveClock),
                label = CountdownLabel.VOTING,
            )
        }
        SectionHeading(stringResource(R.string.vote_intro_title))
        listOf(R.string.vote_intro_1, R.string.vote_intro_2, R.string.vote_intro_3).forEach { line ->
            Text(text = stringResource(line), style = MaterialTheme.typography.bodyLarge)
        }
        PdButton(text = stringResource(R.string.vote_start), onClick = onStart)
    }
}

@Composable
private fun Ballot(
    env: AppEnv,
    data: VotingData,
    ui: VotingUi,
    padding: PaddingValues,
    onSelect: (String) -> Unit,
    onView: (Submission) -> Unit,
    onReport: (Submission) -> Unit,
    onDismissError: () -> Unit,
) {
    val completed = ui.stage == VotingStage.COMPLETED
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = PdSpace.contentMaxWidth)
                .fillMaxWidth()
                .selectableGroup(),
            contentPadding = PaddingValues(horizontal = PdSpace.gutter, vertical = PdSpace.md),
            verticalArrangement = Arrangement.spacedBy(PdSpace.lg),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(PdSpace.sm)) {
                    if (completed) {
                        val votedFor = data.candidates.firstOrNull { it.id == ui.castSubmissionId }
                        InlineNotice(
                            text = if (votedFor != null) {
                                stringResource(R.string.vote_you_voted_for, votedFor.authorDisplayName)
                            } else {
                                stringResource(R.string.vote_done_body)
                            },
                        )
                    } else {
                        Text(text = stringResource(R.string.vote_select_hint), style = MaterialTheme.typography.bodyLarge)
                    }
                    ui.error?.let { InlineError(it, onDismiss = onDismissError) }
                }
            }
            if (data.candidates.isEmpty()) {
                item { Text(stringResource(R.string.vote_no_photos), style = MaterialTheme.typography.bodyLarge) }
            }
            items(data.candidates, key = { it.id }) { photo ->
                BallotCard(
                    env = env,
                    photo = photo,
                    selected = photo.id == (if (completed) ui.castSubmissionId else ui.selectedId),
                    completed = completed,
                    enabled = !ui.casting,
                    onSelect = { onSelect(photo.id) },
                    onView = { onView(photo) },
                    onReport = { onReport(photo) },
                )
            }
        }
    }
}

@Composable
private fun BallotCard(
    env: AppEnv,
    photo: Submission,
    selected: Boolean,
    completed: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onView: () -> Unit,
    onReport: () -> Unit,
) {
    val description = if (photo.isMine) {
        stringResource(R.string.vote_own_photo_cd)
    } else {
        stringResource(R.string.photo_by_cd, photo.authorDisplayName)
    }
    val selectable = !photo.isMine && !completed
    PhotoCard(
        model = env.deps.service.mediaUrl(photo.thumbnailMediaId),
        frameRatio = photoFrameRatio(photo.width, photo.height),
        description = description,
        selected = selected,
        enabled = enabled,
        onSelect = if (selectable) onSelect else null,
    ) {
        Text(
            text = if (photo.isMine) stringResource(R.string.vote_your_photo) else photo.authorDisplayName,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(
                text = stringResource(if (completed) R.string.vote_your_vote else R.string.vote_selected),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        val viewLabel = stringResource(R.string.vote_view_full_cd, photo.authorDisplayName)
        IconButton(onClick = onView, modifier = Modifier.semantics { contentDescription = viewLabel }) {
            Icon(Icons.Filled.Search, contentDescription = null)
        }
        if (!photo.isMine) {
            PhotoMenu(onReport = onReport)
        }
    }
}

@Composable
private fun PhotoMenu(onReport: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.action_more)
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = label }) {
            Icon(Icons.Filled.MoreVert, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.photo_report)) },
                onClick = {
                    open = false
                    onReport()
                },
            )
        }
    }
}

@Composable
private fun ConfirmBar(author: String, casting: Boolean, onConfirm: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column {
            HorizontalDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = PdSpace.gutter, vertical = PdSpace.sm),
                verticalArrangement = Arrangement.spacedBy(PdSpace.xs),
            ) {
                Text(text = stringResource(R.string.vote_confirm_prompt, author), style = MaterialTheme.typography.bodyLarge)
                PdButton(text = stringResource(R.string.vote_confirm_button), onClick = onConfirm, loading = casting)
            }
        }
    }
}
