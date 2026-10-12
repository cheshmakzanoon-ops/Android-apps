@file:OptIn(ExperimentalLayoutApi::class)

package com.agency.photoduel.ui.screens.challenge

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agency.photoduel.R
import com.agency.photoduel.domain.model.Prompt
import com.agency.photoduel.ui.common.AppEnv
import com.agency.photoduel.ui.common.EmptyState
import com.agency.photoduel.ui.common.InlineError
import com.agency.photoduel.ui.common.InlineNotice
import com.agency.photoduel.ui.common.LoadableContent
import com.agency.photoduel.ui.common.PdButton
import com.agency.photoduel.ui.common.PdScreen
import com.agency.photoduel.ui.common.ScrollColumn
import com.agency.photoduel.ui.common.SectionHeading
import com.agency.photoduel.ui.common.hoursLabel
import com.agency.photoduel.ui.common.rememberScreenState
import com.agency.photoduel.ui.state.ChallengeDurations
import com.agency.photoduel.ui.state.CreateChallengeState
import com.agency.photoduel.ui.theme.LocalPdPalette
import com.agency.photoduel.ui.theme.PdSpace

/**
 * Owner picks a prompt from the curated catalogue and the two windows. Only the allowed durations are
 * offered (submission 1, 6, 24, 48 or 72 hours; voting 1, 6 or 24 hours). Used for rematches too, where
 * the previous prompt is not preselected.
 */
@Composable
fun CreateChallengeScreen(
    env: AppEnv,
    roomId: String,
    previousPromptId: String?,
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val state = rememberScreenState("$roomId:${previousPromptId.orEmpty()}") { scope ->
        CreateChallengeState(scope, env.deps.service, env.network, roomId, previousPromptId)
    }
    val ui by state.ui.collectAsStateWithLifecycle()
    val prompts by state.prompts.collectAsStateWithLifecycle()
    val created by rememberUpdatedState(onCreated)

    LaunchedEffect(ui.created) {
        val challenge = ui.created ?: return@LaunchedEffect
        state.consumeCreated()
        created(challenge.id)
    }

    val rematch = previousPromptId != null
    PdScreen(
        title = stringResource(if (rematch) R.string.rematch_title else R.string.create_challenge_title),
        onBack = onBack,
    ) { padding ->
        LoadableContent(loadable = prompts, onRetry = state::reloadPrompts, modifier = Modifier.padding(padding)) { catalog, _ ->
            if (catalog.prompts.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.prompts_empty),
                    message = stringResource(R.string.prompts_empty_body),
                    modifier = Modifier.padding(padding),
                )
            } else {
                ScrollColumn(padding) {
                    if (rematch) InlineNotice(text = stringResource(R.string.rematch_note))
                    SectionHeading(stringResource(R.string.create_challenge_prompt_header))
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(PdSpace.xs)) {
                        catalog.prompts.forEach { prompt ->
                            PromptOption(
                                prompt = prompt,
                                selected = prompt.id == ui.promptId,
                                enabled = !ui.submitting,
                                onSelect = { state.selectPrompt(prompt.id) },
                            )
                        }
                    }
                    SectionHeading(stringResource(R.string.create_challenge_submission_header))
                    HourChips(
                        options = ChallengeDurations.SUBMISSION_HOURS,
                        selected = ui.submissionHours,
                        enabled = !ui.submitting,
                        onSelect = state::setSubmissionHours,
                    )
                    SectionHeading(stringResource(R.string.create_challenge_voting_header))
                    HourChips(
                        options = ChallengeDurations.VOTING_HOURS,
                        selected = ui.votingHours,
                        enabled = !ui.submitting,
                        onSelect = state::setVotingHours,
                    )
                    Text(
                        text = stringResource(R.string.create_challenge_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ui.error?.let { InlineError(it) }
                    PdButton(
                        text = stringResource(R.string.create_challenge_button),
                        onClick = state::submit,
                        enabled = ui.canSubmit,
                        loading = ui.submitting,
                    )
                }
            }
        }
    }
}

@Composable
private fun PromptOption(prompt: Prompt, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val palette = LocalPdPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else palette.border,
                shape = MaterialTheme.shapes.medium,
            )
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(PdSpace.sm),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(PdSpace.sm),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(verticalArrangement = Arrangement.spacedBy(PdSpace.xxs)) {
            Text(text = prompt.title, style = MaterialTheme.typography.titleMedium)
            Text(text = prompt.brief, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HourChips(options: List<Int>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    FlowRow(
        modifier = Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(PdSpace.xs),
    ) {
        options.forEach { hours ->
            FilterChip(
                selected = hours == selected,
                onClick = { onSelect(hours) },
                label = { Text(hoursLabel(hours)) },
                enabled = enabled,
            )
        }
    }
}
