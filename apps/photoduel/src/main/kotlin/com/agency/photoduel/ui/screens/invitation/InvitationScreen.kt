package com.agency.photoduel.ui.screens.invitation

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agency.photoduel.R
import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.ui.common.AppEnv
import com.agency.photoduel.ui.common.EmptyState
import com.agency.photoduel.ui.common.ErrorState
import com.agency.photoduel.ui.common.InlineError
import com.agency.photoduel.ui.common.InlineNotice
import com.agency.photoduel.ui.common.LoadableContent
import com.agency.photoduel.ui.common.PdButton
import com.agency.photoduel.ui.common.PdButtonStyle
import com.agency.photoduel.ui.common.PdScreen
import com.agency.photoduel.ui.common.ScrollColumn
import com.agency.photoduel.ui.common.formatDateTime
import com.agency.photoduel.ui.common.rememberScreenState
import com.agency.photoduel.ui.state.InvitationState
import com.agency.photoduel.ui.theme.LocalPdPalette
import com.agency.photoduel.ui.theme.PdSpace

/**
 * Landing page for `photoduel://invite/{token}` and pasted links: a preview of the room, then Join.
 * Every unusable link (unknown, expired, revoked, used up, room deleted) gets the same message, because
 * the server answers them all the same way.
 */
@Composable
fun InvitationScreen(env: AppEnv, token: String, onBack: () -> Unit, onJoined: (String) -> Unit, onGoHome: () -> Unit) {
    val state = rememberScreenState(token) { scope -> InvitationState(scope, env.deps.service, env.network, token) }
    val preview by state.state.collectAsStateWithLifecycle()
    val ui by state.ui.collectAsStateWithLifecycle()
    val joined by rememberUpdatedState(onJoined)

    LaunchedEffect(ui.joinedRoomId) {
        val roomId = ui.joinedRoomId ?: return@LaunchedEffect
        state.consumeJoined()
        joined(roomId)
    }

    PdScreen(title = stringResource(R.string.invite_title), onBack = onBack) { padding ->
        LoadableContent(
            loadable = preview,
            onRetry = state::reload,
            modifier = Modifier.padding(padding),
            failure = { error, reconnecting ->
                if (InvitationState.isInvalidLink(error)) {
                    InvalidLink(onGoHome = onGoHome, modifier = Modifier.padding(padding))
                } else {
                    ErrorState(error = error, reconnecting = reconnecting, onRetry = state::reload, modifier = Modifier.padding(padding))
                }
            },
        ) { room, _ ->
            val joinError = ui.joinError
            if (joinError != null && InvitationState.isInvalidLink(joinError)) {
                InvalidLink(onGoHome = onGoHome, modifier = Modifier.padding(padding))
            } else {
                ScrollColumn(padding) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, LocalPdPalette.current.border, MaterialTheme.shapes.large)
                            .padding(PdSpace.lg),
                        verticalArrangement = Arrangement.spacedBy(PdSpace.xs),
                    ) {
                        Text(
                            text = stringResource(R.string.invite_join_heading, room.roomName),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(stringResource(R.string.invite_owner, room.ownerDisplayName), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = stringResource(R.string.room_members_of, room.memberCount, room.capacity),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.invite_expires, formatDateTime(room.expiresAtEpochMillis)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    InlineNotice(text = stringResource(R.string.invite_privacy_note))
                    // Shown as information only: joining is still allowed so an existing member gets in.
                    if (joinError == null && room.memberCount >= room.capacity) {
                        InlineNotice(text = stringResource(R.string.error_room_full))
                    }
                    if (joinError != null) {
                        if (joinError.code == ErrorCode.ROOM_FULL) {
                            InlineNotice(text = stringResource(R.string.error_room_full), isError = true)
                        } else {
                            InlineError(joinError)
                        }
                    }
                    PdButton(
                        text = stringResource(R.string.invite_join_button),
                        onClick = state::join,
                        loading = ui.joining,
                    )
                    PdButton(text = stringResource(R.string.action_cancel), onClick = onBack, style = PdButtonStyle.TERTIARY)
                }
            }
        }
    }
}

@Composable
private fun InvalidLink(onGoHome: () -> Unit, modifier: Modifier = Modifier) {
    EmptyState(
        title = stringResource(R.string.invite_invalid_title),
        message = stringResource(R.string.invite_invalid_body),
        icon = Icons.Filled.Warning,
        modifier = modifier,
    ) {
        PdButton(text = stringResource(R.string.invite_go_home), onClick = onGoHome)
    }
}
