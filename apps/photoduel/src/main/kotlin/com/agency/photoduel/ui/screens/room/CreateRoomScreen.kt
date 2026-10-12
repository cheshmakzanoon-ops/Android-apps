package com.agency.photoduel.ui.screens.room

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.agency.photoduel.ui.common.AppEnv
import com.agency.photoduel.ui.common.InlineError
import com.agency.photoduel.ui.common.PdButton
import com.agency.photoduel.ui.common.PdScreen
import com.agency.photoduel.ui.common.ScrollColumn
import com.agency.photoduel.ui.common.rememberScreenState
import com.agency.photoduel.ui.common.roomNameProblem
import com.agency.photoduel.ui.state.CreateRoomState
import com.agency.photoduel.ui.state.Inputs

/** Name a new room. On success the room screen opens, where the owner invites friends. */
@Composable
fun CreateRoomScreen(env: AppEnv, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val state = rememberScreenState("create-room") { scope -> CreateRoomState(scope, env.deps.service, env.network) }
    val ui by state.state.collectAsStateWithLifecycle()
    val created by rememberUpdatedState(onCreated)

    LaunchedEffect(ui.createdRoomId) {
        val id = ui.createdRoomId ?: return@LaunchedEffect
        state.consumeCreated()
        created(id)
    }

    PdScreen(title = stringResource(R.string.create_room_title), onBack = onBack) { padding ->
        ScrollColumn(padding) {
            OutlinedTextField(
                value = ui.name,
                onValueChange = state::onNameChanged,
                label = { Text(stringResource(R.string.create_room_label)) },
                singleLine = true,
                isError = ui.problem != null,
                supportingText = {
                    val problem = ui.problem
                    Text(
                        if (problem != null) {
                            roomNameProblem(problem, Inputs.ROOM_NAME_MAX)
                        } else {
                            stringResource(R.string.create_room_support, Inputs.ROOM_NAME_MAX)
                        },
                    )
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { state.submit() }),
                enabled = !ui.submitting,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.create_room_capacity_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ui.error?.let { InlineError(it) }
            PdButton(text = stringResource(R.string.create_room_button), onClick = state::submit, loading = ui.submitting)
        }
    }
}
