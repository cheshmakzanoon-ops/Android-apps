package com.agency.photoduel.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agency.photoduel.R
import com.agency.photoduel.domain.model.UserProfile
import com.agency.photoduel.ui.common.AppEnv
import com.agency.photoduel.ui.common.ConfirmDialog
import com.agency.photoduel.ui.common.InlineError
import com.agency.photoduel.ui.common.LoadableContent
import com.agency.photoduel.ui.common.PdButton
import com.agency.photoduel.ui.common.PdButtonStyle
import com.agency.photoduel.ui.common.PdScreen
import com.agency.photoduel.ui.common.ScrollColumn
import com.agency.photoduel.ui.common.SectionHeading
import com.agency.photoduel.ui.common.displayNameProblem
import com.agency.photoduel.ui.common.rememberScreenState
import com.agency.photoduel.ui.screens.signin.RecoveryCodeDialog
import com.agency.photoduel.ui.state.Inputs
import com.agency.photoduel.ui.state.ProfileState
import com.agency.photoduel.ui.state.ProfileUi
import com.agency.photoduel.ui.theme.PdSpace

/**
 * Profile: rename, issue a recovery code (shown once, replacing any earlier code after confirmation),
 * sign out, and delete the account after typing the confirmation word.
 */
@Composable
fun ProfileScreen(
    env: AppEnv,
    onBack: () -> Unit,
    onProfileChanged: (UserProfile) -> Unit,
    onSignedOut: () -> Unit,
    onAccountDeleted: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBlocked: () -> Unit,
) {
    val changed by rememberUpdatedState(onProfileChanged)
    val state = rememberScreenState("profile") { scope -> ProfileState(scope, env.deps.service, env.network) { changed(it) } }
    val profile by state.profile.collectAsStateWithLifecycle()
    val ui by state.ui.collectAsStateWithLifecycle()
    val signedOut by rememberUpdatedState(onSignedOut)
    val deleted by rememberUpdatedState(onAccountDeleted)

    LaunchedEffect(ui.signedOut) { if (ui.signedOut) signedOut() }
    LaunchedEffect(ui.accountDeleted) { if (ui.accountDeleted) deleted() }

    PdScreen(title = stringResource(R.string.profile_title), onBack = onBack) { padding ->
        LoadableContent(loadable = profile, onRetry = state::reload, modifier = Modifier.padding(padding)) { user, _ ->
            ScrollColumn(padding) {
                ui.error?.let { InlineError(it, onDismiss = state::dismissError) }
                NameSection(user, ui, state)
                HorizontalDivider()
                SectionHeading(stringResource(R.string.profile_recovery_header))
                Text(
                    text = stringResource(if (user.hasRecoveryCode) R.string.profile_recovery_has else R.string.profile_recovery_none),
                    style = MaterialTheme.typography.bodyLarge,
                )
                PdButton(
                    text = stringResource(if (user.hasRecoveryCode) R.string.profile_recovery_new else R.string.profile_recovery_create),
                    onClick = state::requestRecoveryCode,
                    style = if (user.hasRecoveryCode) PdButtonStyle.SECONDARY else PdButtonStyle.PRIMARY,
                    loading = ui.busy && ui.recoveryCode == null && !ui.deleting && !ui.renaming,
                )
                HorizontalDivider()
                PdButton(text = stringResource(R.string.profile_settings), onClick = onOpenSettings, style = PdButtonStyle.SECONDARY)
                PdButton(text = stringResource(R.string.profile_blocked), onClick = onOpenBlocked, style = PdButtonStyle.SECONDARY)
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.profile_sign_out_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PdButton(text = stringResource(R.string.profile_sign_out), onClick = state::signOut, style = PdButtonStyle.SECONDARY, enabled = !ui.busy)
                PdButton(text = stringResource(R.string.profile_delete), onClick = state::startDelete, style = PdButtonStyle.TERTIARY, enabled = !ui.busy)
            }
        }
    }

    ui.recoveryCode?.let { code ->
        RecoveryCodeDialog(
            title = stringResource(R.string.recovery_dialog_title),
            message = stringResource(R.string.recovery_dialog_body),
            code = code,
            onSaved = state::dismissRecoveryCode,
        )
    }
    if (ui.confirmRecoveryReplace) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_replace_code_title),
            message = stringResource(R.string.confirm_replace_code_body),
            confirmLabel = stringResource(R.string.action_replace),
            onConfirm = state::issueRecoveryCode,
            onDismiss = state::dismissReplaceConfirm,
        )
    }
    if (ui.deleting) DeleteAccountDialog(ui, state)
}

@Composable
private fun NameSection(user: UserProfile, ui: ProfileUi, state: ProfileState) {
    SectionHeading(stringResource(R.string.profile_name_label))
    if (!ui.renaming) {
        Text(text = user.displayName, style = MaterialTheme.typography.headlineSmall)
        PdButton(text = stringResource(R.string.profile_rename), onClick = state::startRename, style = PdButtonStyle.SECONDARY)
    } else {
        val problem = ui.renameProblem
        OutlinedTextField(
            value = ui.renameText,
            onValueChange = state::onRenameTextChanged,
            label = { Text(stringResource(R.string.signin_name_label)) },
            singleLine = true,
            isError = problem != null,
            supportingText = {
                Text(
                    if (problem != null) {
                        displayNameProblem(problem, Inputs.DISPLAY_NAME_MAX)
                    } else {
                        stringResource(R.string.signin_name_support, Inputs.DISPLAY_NAME_MAX)
                    },
                )
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { state.saveRename() }),
            enabled = !ui.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(PdSpace.sm)) {
            PdButton(
                text = stringResource(R.string.action_cancel),
                onClick = state::cancelRename,
                style = PdButtonStyle.SECONDARY,
                modifier = Modifier.weight(1f),
            )
            PdButton(
                text = stringResource(R.string.profile_save),
                onClick = state::saveRename,
                loading = ui.busy,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DeleteAccountDialog(ui: ProfileUi, state: ProfileState) {
    AlertDialog(
        onDismissRequest = { if (!ui.busy) state.cancelDelete() },
        title = { Text(stringResource(R.string.delete_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PdSpace.md)) {
                Text(stringResource(R.string.delete_dialog_body))
                OutlinedTextField(
                    value = ui.deleteText,
                    onValueChange = state::onDeleteTextChanged,
                    label = { Text(stringResource(R.string.delete_dialog_label, Inputs.DELETE_CONFIRMATION_WORD)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                    enabled = !ui.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                ui.error?.let { InlineError(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = state::confirmDelete, enabled = ui.deleteConfirmed && !ui.busy) {
                Text(stringResource(R.string.action_delete_account), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = state::cancelDelete, enabled = !ui.busy) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
