package com.agency.photoduel.ui.screens.safety

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agency.photoduel.R
import com.agency.photoduel.ui.common.AppEnv
import com.agency.photoduel.ui.common.EmptyState
import com.agency.photoduel.ui.common.InlineError
import com.agency.photoduel.ui.common.LoadableContent
import com.agency.photoduel.ui.common.PdScreen
import com.agency.photoduel.ui.common.rememberScreenState
import com.agency.photoduel.ui.state.BlockedUsersState
import com.agency.photoduel.ui.theme.PdSpace

/** People the viewer blocked, each with an Unblock button. */
@Composable
fun BlockedUsersScreen(env: AppEnv, onBack: () -> Unit) {
    val state = rememberScreenState("blocked") { scope -> BlockedUsersState(scope, env.deps.service, env.network) }
    val users by state.users.collectAsStateWithLifecycle()
    val ui by state.ui.collectAsStateWithLifecycle()

    PdScreen(title = stringResource(R.string.blocked_title), onBack = onBack) { padding ->
        LoadableContent(loadable = users, onRetry = state::reload, modifier = Modifier.padding(padding)) { list, _ ->
            if (list.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.blocked_empty_title),
                    message = stringResource(R.string.blocked_empty_body),
                    icon = Icons.Filled.Lock,
                    modifier = Modifier.padding(padding),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(horizontal = PdSpace.gutter, vertical = PdSpace.xs),
                    verticalArrangement = Arrangement.spacedBy(PdSpace.xs),
                ) {
                    ui.error?.let { error -> item { InlineError(error, onDismiss = state::dismissError) } }
                    items(list, key = { it.userId }) { user ->
                        val unblockLabel = stringResource(R.string.blocked_unblock_cd, user.displayName)
                        ListItem(
                            headlineContent = { Text(user.displayName, style = MaterialTheme.typography.bodyLarge) },
                            trailingContent = {
                                OutlinedButton(
                                    onClick = { state.unblock(user.userId) },
                                    enabled = user.userId !in ui.unblocking,
                                    modifier = Modifier.semantics { contentDescription = unblockLabel },
                                ) { Text(stringResource(R.string.blocked_unblock)) }
                            },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                        )
                    }
                }
            }
        }
    }
}
