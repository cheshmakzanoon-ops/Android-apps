@file:OptIn(ExperimentalLayoutApi::class)

package com.agency.photoduel.ui.screens.room

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agency.photoduel.R
import com.agency.photoduel.domain.model.Challenge
import com.agency.photoduel.domain.model.ChallengeState
import com.agency.photoduel.domain.model.InvitationTtl
import com.agency.photoduel.domain.model.MemberRole
import com.agency.photoduel.domain.model.ReportTarget
import com.agency.photoduel.domain.model.RoomMember
import com.agency.photoduel.domain.rules.RosterRules
import com.agency.photoduel.ui.common.AppEnv
import com.agency.photoduel.ui.common.ConfirmDialog
import com.agency.photoduel.ui.common.InlineError
import com.agency.photoduel.ui.common.InlineNotice
import com.agency.photoduel.ui.common.LoadableContent
import com.agency.photoduel.ui.common.PdButton
import com.agency.photoduel.ui.common.PdButtonStyle
import com.agency.photoduel.ui.common.PdScreen
import com.agency.photoduel.ui.common.RefreshOnReturn
import com.agency.photoduel.ui.common.ScrollColumn
import com.agency.photoduel.ui.common.SectionHeading
import com.agency.photoduel.ui.common.StaleNotice
import com.agency.photoduel.ui.common.challengeStateLabel
import com.agency.photoduel.ui.common.formatDateTime
import com.agency.photoduel.ui.common.rememberScreenState
import com.agency.photoduel.ui.common.ttlLabel
import com.agency.photoduel.ui.screens.safety.BlockSheet
import com.agency.photoduel.ui.screens.safety.ReportSheet
import com.agency.photoduel.ui.state.InvitationInput
import com.agency.photoduel.ui.state.RoomConfirm
import com.agency.photoduel.ui.state.RoomDetailData
import com.agency.photoduel.ui.state.RoomDetailState
import com.agency.photoduel.ui.state.RoomDetailUi
import com.agency.photoduel.ui.state.valueOrNull
import com.agency.photoduel.ui.theme.LocalPdPalette
import com.agency.photoduel.ui.theme.PdSpace

private sealed interface MemberSheet {
    data class Report(val member: RoomMember) : MemberSheet
    data class Block(val member: RoomMember) : MemberSheet
}

/**
 * One room: its challenge, invitations and roster. Owner-only actions (start, cancel, remove, delete)
 * are shown only to the owner; the server still decides every one of them.
 */
@Composable
fun RoomDetailScreen(
    env: AppEnv,
    roomId: String,
    onBack: () -> Unit,
    onLeft: () -> Unit,
    onOpenChallenge: (String) -> Unit,
    onOpenResults: (String) -> Unit,
    onCreateChallenge: (previousPromptId: String?) -> Unit,
) {
    val state = rememberScreenState(roomId) { scope -> RoomDetailState(scope, env.deps.service, env.network, roomId) }
    val data by state.data.collectAsStateWithLifecycle()
    val ui by state.ui.collectAsStateWithLifecycle()
    val left by rememberUpdatedState(onLeft)
    var sheet by remember { mutableStateOf<MemberSheet?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    RefreshOnReturn(state::refresh)

    LaunchedEffect(ui.finished) {
        if (ui.finished) left()
    }

    val loaded = data.valueOrNull
    PdScreen(
        title = loaded?.room?.name ?: stringResource(R.string.room_title_loading),
        onBack = onBack,
        actions = {
            IconButton(onClick = state::refresh) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
            if (loaded != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (loaded.isOwner) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.room_delete)) },
                                onClick = {
                                    menuOpen = false
                                    state.ask(RoomConfirm.DeleteRoom)
                                },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.room_leave)) },
                                onClick = {
                                    menuOpen = false
                                    state.ask(RoomConfirm.Leave)
                                },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        LoadableContent(loadable = data, onRetry = state::refresh, modifier = Modifier.padding(padding)) { room, stale ->
            ScrollColumn(padding) {
                if (stale) StaleNotice(onRefresh = state::refresh)
                ui.error?.let { InlineError(it, onDismiss = state::dismissError) }
                ChallengeSection(
                    data = room,
                    busy = ui.busy,
                    onOpenChallenge = onOpenChallenge,
                    onOpenResults = onOpenResults,
                    onCreateChallenge = onCreateChallenge,
                    onStart = state::startChallenge,
                    onCancel = { state.ask(RoomConfirm.CancelChallenge(it)) },
                )
                HorizontalDivider()
                InviteSection(
                    full = room.room.members.size >= room.room.capacity,
                    ui = ui,
                    onTtl = state::setTtl,
                    onCreate = state::createInvitation,
                    onShare = { url -> env.deps.invitationSharing.share(room.room.name, url) },
                    onRevoke = state::revokeInvitation,
                )
                HorizontalDivider()
                MembersSection(
                    data = room,
                    busy = ui.busy,
                    onRemove = { state.ask(RoomConfirm.RemoveMember(it.userId, it.displayName)) },
                    onReport = { sheet = MemberSheet.Report(it) },
                    onBlock = { sheet = MemberSheet.Block(it) },
                )
                if (room.isOwner) {
                    PdButton(
                        text = stringResource(R.string.room_delete),
                        onClick = { state.ask(RoomConfirm.DeleteRoom) },
                        style = PdButtonStyle.TERTIARY,
                        enabled = !ui.busy,
                    )
                } else {
                    PdButton(
                        text = stringResource(R.string.room_leave),
                        onClick = { state.ask(RoomConfirm.Leave) },
                        style = PdButtonStyle.TERTIARY,
                        enabled = !ui.busy,
                    )
                }
            }
        }
    }

    when (val confirm = ui.confirm) {
        RoomConfirm.Leave -> ConfirmDialog(
            title = stringResource(R.string.confirm_leave_title),
            message = stringResource(R.string.confirm_leave_body),
            confirmLabel = stringResource(R.string.action_leave),
            onConfirm = state::leave,
            onDismiss = state::dismissConfirm,
        )
        RoomConfirm.DeleteRoom -> ConfirmDialog(
            title = stringResource(R.string.confirm_delete_room_title),
            message = stringResource(R.string.confirm_delete_room_body),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = state::deleteRoom,
            onDismiss = state::dismissConfirm,
        )
        is RoomConfirm.RemoveMember -> ConfirmDialog(
            title = stringResource(R.string.confirm_remove_title, confirm.displayName),
            message = stringResource(R.string.confirm_remove_body),
            confirmLabel = stringResource(R.string.action_remove),
            onConfirm = { state.removeMember(confirm.userId) },
            onDismiss = state::dismissConfirm,
        )
        is RoomConfirm.CancelChallenge -> ConfirmDialog(
            title = stringResource(R.string.confirm_cancel_challenge_title),
            message = stringResource(R.string.confirm_cancel_challenge_body),
            confirmLabel = stringResource(R.string.room_cancel_challenge),
            onConfirm = { state.cancelChallenge(confirm.challengeId) },
            onDismiss = state::dismissConfirm,
            dismissLabel = stringResource(R.string.action_keep),
        )
        null -> Unit
    }

    when (val open = sheet) {
        is MemberSheet.Report -> ReportSheet(
            env = env,
            target = ReportTarget.User(open.member.userId),
            title = stringResource(R.string.report_title_user, open.member.displayName),
            onDismiss = { sheet = null },
        )
        is MemberSheet.Block -> BlockSheet(
            env = env,
            userId = open.member.userId,
            displayName = open.member.displayName,
            onDismiss = { sheet = null },
        )
        null -> Unit
    }
}

@Composable
private fun ChallengeSection(
    data: RoomDetailData,
    busy: Boolean,
    onOpenChallenge: (String) -> Unit,
    onOpenResults: (String) -> Unit,
    onCreateChallenge: (String?) -> Unit,
    onStart: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    SectionHeading(stringResource(R.string.room_challenge_header))
    val active = data.activeChallenge
    val latest = data.latestChallenge
    when {
        active != null -> {
            ChallengeSummary(active)
            PdButton(
                text = stringResource(R.string.room_open_challenge),
                onClick = { onOpenChallenge(active.id) },
                style = if (data.isOwner && active.state == ChallengeState.CREATED) PdButtonStyle.SECONDARY else PdButtonStyle.PRIMARY,
            )
            if (data.isOwner && active.state == ChallengeState.CREATED) {
                val enough = data.room.members.size >= RosterRules.MIN_MEMBERS_TO_START
                PdButton(
                    text = stringResource(R.string.room_start_challenge),
                    onClick = { onStart(active.id) },
                    enabled = enough,
                    loading = busy,
                )
                if (!enough) {
                    Text(
                        text = stringResource(R.string.room_start_needs_members, RosterRules.MIN_MEMBERS_TO_START),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (data.isOwner && (active.state == ChallengeState.CREATED || active.state == ChallengeState.OPEN_FOR_SUBMISSIONS)) {
                PdButton(
                    text = stringResource(R.string.room_cancel_challenge),
                    onClick = { onCancel(active.id) },
                    style = PdButtonStyle.TERTIARY,
                    enabled = !busy,
                )
            }
        }
        latest != null -> {
            ChallengeSummary(latest)
            if (latest.state == ChallengeState.COMPLETED || latest.state == ChallengeState.ARCHIVED) {
                PdButton(
                    text = stringResource(R.string.room_see_results),
                    onClick = { onOpenResults(latest.id) },
                    style = if (data.isOwner) PdButtonStyle.SECONDARY else PdButtonStyle.PRIMARY,
                )
                if (data.isOwner) {
                    PdButton(text = stringResource(R.string.room_rematch), onClick = { onCreateChallenge(latest.prompt.id) })
                }
            } else if (data.isOwner) {
                PdButton(text = stringResource(R.string.room_new_challenge), onClick = { onCreateChallenge(null) })
            }
        }
        data.isOwner -> {
            Text(stringResource(R.string.room_challenge_none_owner), style = MaterialTheme.typography.bodyLarge)
            PdButton(text = stringResource(R.string.room_new_challenge), onClick = { onCreateChallenge(null) })
        }
        else -> Text(stringResource(R.string.room_challenge_none_member), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ChallengeSummary(challenge: Challenge) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, LocalPdPalette.current.border, MaterialTheme.shapes.large)
            .padding(PdSpace.md),
        verticalArrangement = Arrangement.spacedBy(PdSpace.xxs),
    ) {
        Text(
            text = stringResource(R.string.room_round, challenge.roundNumber),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = challenge.prompt.title, style = MaterialTheme.typography.titleLarge)
        Text(text = challengeStateLabel(challenge.state), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun InviteSection(
    full: Boolean,
    ui: RoomDetailUi,
    onTtl: (InvitationTtl) -> Unit,
    onCreate: () -> Unit,
    onShare: (String) -> Unit,
    onRevoke: () -> Unit,
) {
    SectionHeading(stringResource(R.string.room_invite_header))
    val invitation = ui.invitation
    if (invitation == null) {
        Text(stringResource(R.string.room_invite_body), style = MaterialTheme.typography.bodyLarge)
        if (full) {
            InlineNotice(text = stringResource(R.string.room_full_note))
        } else {
            Text(stringResource(R.string.room_invite_ttl_label), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(PdSpace.xs)) {
                InvitationTtl.entries.forEach { ttl ->
                    FilterChip(
                        selected = ui.ttl == ttl,
                        onClick = { onTtl(ttl) },
                        label = { Text(ttlLabel(ttl)) },
                        enabled = !ui.busy,
                    )
                }
            }
            PdButton(text = stringResource(R.string.room_invite_create), onClick = onCreate, loading = ui.busy)
        }
    } else {
        val url = InvitationInput.urlFor(invitation.token)
        InlineNotice(text = stringResource(R.string.room_invite_created))
        SelectionContainer {
            Text(
                text = url,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, LocalPdPalette.current.border, MaterialTheme.shapes.medium)
                    .padding(PdSpace.sm),
            )
        }
        Text(
            text = stringResource(R.string.room_invite_expires, formatDateTime(invitation.expiresAtEpochMillis)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PdButton(text = stringResource(R.string.room_invite_share), onClick = { onShare(url) })
        PdButton(
            text = stringResource(R.string.room_invite_revoke),
            onClick = onRevoke,
            style = PdButtonStyle.TERTIARY,
            loading = ui.busy,
        )
    }
}

@Composable
private fun MembersSection(
    data: RoomDetailData,
    busy: Boolean,
    onRemove: (RoomMember) -> Unit,
    onReport: (RoomMember) -> Unit,
    onBlock: (RoomMember) -> Unit,
) {
    SectionHeading(stringResource(R.string.room_members_header, data.room.members.size, data.room.capacity))
    val frozen = data.activeChallenge?.state.let { it == ChallengeState.OPEN_FOR_SUBMISSIONS || it == ChallengeState.VOTING }
    if (frozen) {
        Text(
            text = stringResource(R.string.members_roster_frozen_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    data.room.members.forEach { member ->
        MemberRow(
            member = member,
            isViewer = member.userId == data.room.viewerUserId,
            canRemove = data.isOwner && member.role != MemberRole.OWNER && !busy,
            onRemove = { onRemove(member) },
            onReport = { onReport(member) },
            onBlock = { onBlock(member) },
        )
    }
}

@Composable
private fun MemberRow(
    member: RoomMember,
    isViewer: Boolean,
    canRemove: Boolean,
    onRemove: () -> Unit,
    onReport: () -> Unit,
    onBlock: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val name = if (isViewer) stringResource(R.string.room_member_you, member.displayName) else member.displayName
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PdSpace.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            if (member.role == MemberRole.OWNER) {
                Text(
                    text = stringResource(R.string.room_owner_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (!isViewer) {
            val moreLabel = stringResource(R.string.member_more_cd, member.displayName)
            Box {
                IconButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = moreLabel }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.member_report)) }, onClick = { open = false; onReport() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.member_block)) }, onClick = { open = false; onBlock() })
                    if (canRemove) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.room_remove_member, member.displayName)) },
                            onClick = { open = false; onRemove() },
                        )
                    }
                }
            }
        }
    }
}
