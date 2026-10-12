package com.agency.photoduel.ui.screens.home

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agency.photoduel.R
import com.agency.photoduel.domain.model.RoomSummary
import com.agency.photoduel.ui.common.AppEnv
import com.agency.photoduel.ui.common.EmptyState
import com.agency.photoduel.ui.common.LoadableContent
import com.agency.photoduel.ui.common.PdButton
import com.agency.photoduel.ui.common.PdButtonStyle
import com.agency.photoduel.ui.common.PdScreen
import com.agency.photoduel.ui.common.RefreshOnReturn
import com.agency.photoduel.ui.common.StaleNotice
import com.agency.photoduel.ui.common.challengeStateLabel
import com.agency.photoduel.ui.common.rememberScreenState
import com.agency.photoduel.ui.state.HomeState
import com.agency.photoduel.ui.state.valueOrNull
import com.agency.photoduel.ui.theme.LocalPdPalette
import com.agency.photoduel.ui.theme.PdSpace

/** The signed-in start screen: the user's rooms, plus create and join. */
@Composable
fun HomeScreen(
    env: AppEnv,
    onOpenRoom: (String) -> Unit,
    onCreateRoom: () -> Unit,
    onOpenInvitation: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state = rememberScreenState("home") { scope -> HomeState(scope, env.deps.service, env.network) }
    val rooms by state.state.collectAsStateWithLifecycle()
    val join by state.join.collectAsStateWithLifecycle()
    val hasRooms = rooms.valueOrNull?.isNotEmpty() == true
    RefreshOnReturn(state::refresh)

    PdScreen(
        title = stringResource(R.string.home_title),
        onBack = null,
        actions = {
            IconButton(onClick = state::refresh) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
            IconButton(onClick = onOpenHistory) {
                Icon(Icons.Filled.DateRange, contentDescription = stringResource(R.string.home_history))
            }
            IconButton(onClick = onOpenProfile) {
                Icon(Icons.Filled.AccountCircle, contentDescription = stringResource(R.string.home_profile))
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.home_settings))
            }
        },
        floatingActionButton = {
            if (hasRooms) {
                ExtendedFloatingActionButton(
                    onClick = onCreateRoom,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.home_create_room)) },
                )
            }
        },
    ) { padding ->
        LoadableContent(loadable = rooms, onRetry = state::refresh, modifier = Modifier.padding(padding)) { list, stale ->
            if (list.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.home_empty_title),
                    message = stringResource(R.string.home_empty_body),
                    modifier = Modifier.padding(padding),
                ) {
                    PdButton(text = stringResource(R.string.home_create_room), onClick = onCreateRoom)
                    PdButton(text = stringResource(R.string.home_join_link), onClick = state::openJoinDialog, style = PdButtonStyle.SECONDARY)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(start = PdSpace.gutter, end = PdSpace.gutter, top = PdSpace.xs, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(PdSpace.sm),
                ) {
                    if (stale) {
                        item { StaleNotice(onRefresh = state::refresh) }
                    }
                    items(list, key = { it.id }) { room -> RoomRow(room, onClick = { onOpenRoom(room.id) }) }
                    item {
                        PdButton(
                            text = stringResource(R.string.home_join_link),
                            onClick = state::openJoinDialog,
                            style = PdButtonStyle.TERTIARY,
                        )
                    }
                }
            }
        }
    }

    if (join.open) {
        AlertDialog(
            onDismissRequest = state::closeJoinDialog,
            title = { Text(stringResource(R.string.join_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = join.text,
                    onValueChange = state::onJoinTextChanged,
                    label = { Text(stringResource(R.string.join_dialog_label)) },
                    isError = join.invalid,
                    supportingText = if (join.invalid) {
                        { Text(stringResource(R.string.join_dialog_invalid)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { state.submitJoinLink()?.let(onOpenInvitation) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { state.submitJoinLink()?.let(onOpenInvitation) }) {
                    Text(stringResource(R.string.join_dialog_button))
                }
            },
            dismissButton = {
                TextButton(onClick = state::closeJoinDialog) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun RoomRow(room: RoomSummary, onClick: () -> Unit) {
    val palette = LocalPdPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, palette.border, MaterialTheme.shapes.large)
            .clip(MaterialTheme.shapes.large)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(PdSpace.md),
        verticalArrangement = Arrangement.spacedBy(PdSpace.xxs),
    ) {
        Text(text = room.name, style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(PdSpace.sm), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.room_members_of, room.memberCount, room.capacity),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = room.activeChallengeState?.let { challengeStateLabel(it) } ?: stringResource(R.string.room_no_challenge),
                style = MaterialTheme.typography.labelLarge,
                color = if (room.activeChallengeState != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
