package com.agency.photoduel.ui.screens.history

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agency.photoduel.R
import com.agency.photoduel.domain.model.ChallengeState
import com.agency.photoduel.domain.model.HistoryEntry
import com.agency.photoduel.ui.common.AppEnv
import com.agency.photoduel.ui.common.EmptyState
import com.agency.photoduel.ui.common.InlineError
import com.agency.photoduel.ui.common.LoadableContent
import com.agency.photoduel.ui.common.PdButton
import com.agency.photoduel.ui.common.PdButtonStyle
import com.agency.photoduel.ui.common.PdScreen
import com.agency.photoduel.ui.common.StaleNotice
import com.agency.photoduel.ui.common.challengeStateLabel
import com.agency.photoduel.ui.common.outcomeLabel
import com.agency.photoduel.ui.common.rememberScreenState
import com.agency.photoduel.ui.state.HistoryState
import com.agency.photoduel.ui.theme.LocalPdPalette
import com.agency.photoduel.ui.theme.PdSpace

/** Challenges the viewer played, newest first, a page at a time. */
@Composable
fun HistoryScreen(env: AppEnv, onBack: () -> Unit, onOpenChallenge: (String) -> Unit, onOpenResults: (String) -> Unit) {
    val state = rememberScreenState("history") { scope -> HistoryState(scope, env.deps.service, env.network) }
    val history by state.state.collectAsStateWithLifecycle()

    PdScreen(title = stringResource(R.string.history_title), onBack = onBack) { padding ->
        LoadableContent(loadable = history, onRetry = state::load, modifier = Modifier.padding(padding)) { page, stale ->
            if (page.entries.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.history_empty_title),
                    message = stringResource(R.string.history_empty_body),
                    icon = Icons.Filled.DateRange,
                    modifier = Modifier.padding(padding),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(horizontal = PdSpace.gutter, vertical = PdSpace.md),
                    verticalArrangement = Arrangement.spacedBy(PdSpace.sm),
                ) {
                    if (stale) item { StaleNotice(onRefresh = state::load) }
                    items(page.entries, key = { it.challenge.id }) { entry ->
                        HistoryRow(entry) {
                            val done = entry.challenge.state == ChallengeState.COMPLETED || entry.challenge.state == ChallengeState.ARCHIVED
                            if (done) onOpenResults(entry.challenge.id) else onOpenChallenge(entry.challenge.id)
                        }
                    }
                    page.loadMoreError?.let { error -> item { InlineError(error) } }
                    if (page.nextCursor != null) {
                        item {
                            PdButton(
                                text = stringResource(R.string.history_load_more),
                                onClick = state::loadMore,
                                style = PdButtonStyle.SECONDARY,
                                loading = page.loadingMore,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, onClick: () -> Unit) {
    val challenge = entry.challenge
    val outcome = entry.result?.outcome
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .border(1.dp, LocalPdPalette.current.border, MaterialTheme.shapes.large)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(PdSpace.md),
        verticalArrangement = Arrangement.spacedBy(PdSpace.xxs),
    ) {
        Text(
            text = stringResource(R.string.history_item_round, entry.roomName, challenge.roundNumber),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = challenge.prompt.title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = if (outcome != null) outcomeLabel(outcome) else challengeStateLabel(challenge.state),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(if (entry.mySubmissionId != null) R.string.history_you_submitted else R.string.history_you_did_not_submit),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
