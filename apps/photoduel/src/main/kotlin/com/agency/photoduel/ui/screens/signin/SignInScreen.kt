package com.agency.photoduel.ui.screens.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agency.photoduel.R
import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.UserProfile
import com.agency.photoduel.ui.common.AppEnv
import com.agency.photoduel.ui.common.InlineError
import com.agency.photoduel.ui.common.InlineNotice
import com.agency.photoduel.ui.common.PdButton
import com.agency.photoduel.ui.common.PdButtonStyle
import com.agency.photoduel.ui.common.displayNameProblem
import com.agency.photoduel.ui.common.rememberScreenState
import com.agency.photoduel.ui.state.Inputs
import com.agency.photoduel.ui.state.NameProblem
import com.agency.photoduel.ui.state.RestoreNotice
import com.agency.photoduel.ui.state.SignInMode
import com.agency.photoduel.ui.state.SignInState
import com.agency.photoduel.ui.theme.PdSpace
import com.agency.photoduel.ui.theme.PdType

/** Guest sign-in with a display name, or restore with a recovery code. */
@Composable
fun SignInScreen(env: AppEnv, onSignedIn: (UserProfile) -> Unit) {
    val state = rememberScreenState("sign-in") { scope -> SignInState(scope, env.deps.service, env.network) }
    val ui by state.state.collectAsStateWithLifecycle()
    val signedIn by rememberUpdatedState(onSignedIn)

    LaunchedEffect(ui.completedUser) {
        val user = ui.completedUser ?: return@LaunchedEffect
        state.consumeCompleted()
        signedIn(user)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = PdSpace.contentMaxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PdSpace.gutter, vertical = PdSpace.xl),
            verticalArrangement = Arrangement.spacedBy(PdSpace.md),
        ) {
            when (ui.mode) {
                SignInMode.GUEST -> GuestForm(state, ui.displayName, ui.nameProblem, ui.submitting)
                SignInMode.RESTORE -> RestoreForm(state, ui.recoveryCode, ui.codeInvalid, ui.submitting)
            }
            ui.error?.let { error ->
                if (ui.mode == SignInMode.RESTORE && error.code == ErrorCode.UNAUTHENTICATED) {
                    InlineNotice(text = stringResource(R.string.error_invalid_recovery_code), isError = true)
                } else {
                    InlineError(error)
                }
            }
            InlineNotice(text = stringResource(R.string.signin_limitation))
        }
    }

    when (val notice = ui.notice) {
        is RestoreNotice.NewCode -> RecoveryCodeDialog(
            title = stringResource(R.string.restore_new_code_title),
            message = stringResource(R.string.restore_new_code_body),
            code = notice.code,
            onSaved = state::acknowledgeNotice,
        )
        RestoreNotice.CodeNotIssued -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.restore_code_not_issued_title)) },
            text = { Text(stringResource(R.string.restore_code_not_issued_body)) },
            confirmButton = { TextButton(onClick = state::acknowledgeNotice) { Text(stringResource(R.string.action_continue)) } },
        )
        null -> Unit
    }
}

@Composable
private fun GuestForm(state: SignInState, name: String, problem: NameProblem?, submitting: Boolean) {
    Text(
        text = stringResource(R.string.signin_title),
        style = PdType.display,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        text = stringResource(R.string.signin_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = name,
        onValueChange = state::onNameChanged,
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
        keyboardActions = KeyboardActions(onDone = { state.submitGuest() }),
        enabled = !submitting,
        modifier = Modifier.fillMaxWidth(),
    )
    PdButton(text = stringResource(R.string.signin_guest_button), onClick = state::submitGuest, loading = submitting)
    PdButton(
        text = stringResource(R.string.signin_have_code),
        onClick = { state.setMode(SignInMode.RESTORE) },
        style = PdButtonStyle.TERTIARY,
        enabled = !submitting,
    )
}

@Composable
private fun RestoreForm(state: SignInState, code: String, invalid: Boolean, submitting: Boolean) {
    Text(
        text = stringResource(R.string.signin_restore_title),
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        text = stringResource(R.string.signin_restore_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = code,
        onValueChange = state::onCodeChanged,
        label = { Text(stringResource(R.string.signin_code_label)) },
        singleLine = true,
        isError = invalid,
        supportingText = if (invalid) {
            { Text(stringResource(R.string.signin_code_invalid)) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Ascii,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { state.submitRestore() }),
        enabled = !submitting,
        modifier = Modifier.fillMaxWidth(),
    )
    PdButton(text = stringResource(R.string.signin_restore_button), onClick = state::submitRestore, loading = submitting)
    PdButton(
        text = stringResource(R.string.signin_new_guest),
        onClick = { state.setMode(SignInMode.GUEST) },
        style = PdButtonStyle.TERTIARY,
        enabled = !submitting,
    )
}

/**
 * Shows a recovery code once. The code is selectable so it can be copied; the dialog can only be left
 * with "I've saved it", after which the app keeps no copy.
 */
@Composable
fun RecoveryCodeDialog(title: String, message: String, code: String, onSaved: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PdSpace.md)) {
                Text(message)
                SelectionContainer {
                    Text(
                        text = code,
                        style = PdType.countdown,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onSaved) { Text(stringResource(R.string.recovery_saved_button)) } },
    )
}
