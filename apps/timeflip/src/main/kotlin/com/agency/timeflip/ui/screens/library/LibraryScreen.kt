@file:OptIn(ExperimentalMaterial3Api::class)

package com.agency.timeflip.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agency.timeflip.R
import com.agency.timeflip.domain.model.ProjectId
import com.agency.timeflip.domain.model.ProjectSummary
import com.agency.timeflip.ui.BitmapLoader
import com.agency.timeflip.ui.TimeFlipTheme
import com.agency.timeflip.ui.UiDependencies
import com.agency.timeflip.ui.common.ConfirmDialog
import com.agency.timeflip.ui.common.FailurePanel
import com.agency.timeflip.ui.common.PhotoThumbnail
import com.agency.timeflip.ui.common.UiFailure
import com.agency.timeflip.ui.common.formatDate
import com.agency.timeflip.ui.components.BottomActionBar
import com.agency.timeflip.ui.components.PrimaryButton
import com.agency.timeflip.ui.navigation.libraryFactory
import com.agency.timeflip.ui.theme.TimeFlipColor
import com.agency.timeflip.ui.theme.TimeFlipRadius
import com.agency.timeflip.ui.theme.TimeFlipSize
import com.agency.timeflip.ui.theme.TimeFlipSpacing
import com.agency.timeflip.domain.rules.ProjectRules

@Composable
fun LibraryRoute(
    deps: UiDependencies,
    onNewTransformation: () -> Unit,
    onOpenProject: (ProjectId, OpenTarget) -> Unit,
    onSettings: () -> Unit,
) {
    val vm: LibraryViewModel = viewModel<LibraryViewModel>(factory = libraryFactory(deps))
    val state by vm.state.collectAsStateWithLifecycle()
    LibraryScreenContent(
        state = state,
        loader = deps.bitmaps,
        onNewTransformation = onNewTransformation,
        onOpenProject = { row -> vm.openProject(row.id) { target -> onOpenProject(row.id, target) } },
        onRename = vm::startRename,
        onDuplicate = { row -> vm.duplicate(row.id) },
        onDelete = vm::startDelete,
        onOpenSettings = onSettings,
        onRetry = vm::retry,
        onMessageShown = vm::consumeMessage,
        onRenameDraftChange = vm::changeRenameDraft,
        onConfirmRename = vm::confirmRename,
        onConfirmDelete = vm::confirmDelete,
        onDismissDialog = vm::dismissDialog,
    )
}

/** Stateless library. Previews and UI tests use it directly with fake state. */
@Composable
fun LibraryScreenContent(
    state: LibraryUiState,
    loader: BitmapLoader,
    onNewTransformation: () -> Unit,
    onOpenProject: (ProjectSummary) -> Unit,
    onRename: (ProjectSummary) -> Unit,
    onDuplicate: (ProjectSummary) -> Unit,
    onDelete: (ProjectSummary) -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    onMessageShown: () -> Unit,
    onRenameDraftChange: (String) -> Unit,
    onConfirmRename: () -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDialog: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val message = state.message

    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(context.getString(messageTextRes(message)))
            onMessageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.library_settings_cd),
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (state.content is LibraryContent.Ready) {
                BottomActionBar {
                    PrimaryButton(
                        text = stringResource(R.string.library_new),
                        onClick = onNewTransformation,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = TimeFlipColor.Background,
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val content = state.content) {
                LibraryContent.Loading -> LoadingBody()
                LibraryContent.Empty -> EmptyBody(onNewTransformation)
                is LibraryContent.Ready -> ProjectList(content.rows, loader, onOpenProject, onRename, onDuplicate, onDelete)
                is LibraryContent.Failed -> FailureBody(content.failure, onRetry)
            }
        }
    }

    when (val dialog = state.dialog) {
        LibraryDialog.None -> Unit
        is LibraryDialog.Rename -> RenameDialog(
            draft = dialog.draftName,
            busy = state.working,
            onDraftChange = onRenameDraftChange,
            onConfirm = onConfirmRename,
            onDismiss = onDismissDialog,
        )
        is LibraryDialog.ConfirmDelete -> ConfirmDialog(
            title = stringResource(R.string.library_delete_title),
            body = stringResource(R.string.library_delete_body, dialog.name),
            confirmText = stringResource(R.string.library_delete_confirm),
            onConfirm = onConfirmDelete,
            onDismiss = onDismissDialog,
        )
    }
}

@Composable
private fun LoadingBody() {
    Column(
        modifier = Modifier.fillMaxSize().padding(TimeFlipSpacing.Space24),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = TimeFlipColor.Accent)
        Spacer(Modifier.size(TimeFlipSpacing.Space16))
        Text(
            text = stringResource(R.string.library_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = TimeFlipColor.OnSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyBody(onNewTransformation: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(TimeFlipSpacing.Space24),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.library_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            color = TimeFlipColor.OnBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.size(TimeFlipSpacing.Space12))
        Text(
            text = stringResource(R.string.library_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = TimeFlipColor.OnSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(TimeFlipSpacing.Space24))
        PrimaryButton(text = stringResource(R.string.library_new), onClick = onNewTransformation)
    }
}

@Composable
private fun FailureBody(failure: UiFailure, onRetry: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(TimeFlipSpacing.Space16)) {
        FailurePanel(failure = failure, onRetry = onRetry)
    }
}

@Composable
private fun ProjectList(
    rows: List<ProjectSummary>,
    loader: BitmapLoader,
    onOpen: (ProjectSummary) -> Unit,
    onRename: (ProjectSummary) -> Unit,
    onDuplicate: (ProjectSummary) -> Unit,
    onDelete: (ProjectSummary) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = TimeFlipSpacing.Space16, vertical = TimeFlipSpacing.Space8),
        verticalArrangement = Arrangement.spacedBy(TimeFlipSpacing.Space8),
    ) {
        items(rows, key = { it.id.value }) { row ->
            ProjectRow(row, loader, onOpen, onRename, onDuplicate, onDelete)
        }
    }
}

@Composable
private fun ProjectRow(
    row: ProjectSummary,
    loader: BitmapLoader,
    onOpen: (ProjectSummary) -> Unit,
    onRename: (ProjectSummary) -> Unit,
    onDuplicate: (ProjectSummary) -> Unit,
    onDelete: (ProjectSummary) -> Unit,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val exportsText = context.resources.getQuantityString(R.plurals.library_exports, row.exportCount, row.exportCount)
    val metaText = stringResource(
        R.string.library_row_meta,
        formatDate(row.updatedAtEpochMs),
        exportsText,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TimeFlipColor.Surface, RoundedCornerShape(TimeFlipRadius.Medium))
            .padding(start = TimeFlipSpacing.Space12, top = TimeFlipSpacing.Space8, bottom = TimeFlipSpacing.Space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhotoThumbnail(
            photoId = row.thumbnailPhotoId,
            loader = loader,
            maxEdge = THUMBNAIL_EDGE_PX,
            contentDescription = null,
            modifier = Modifier.size(TimeFlipSize.RowThumbnail),
        )
        Spacer(Modifier.width(TimeFlipSpacing.Space12))
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable { onOpen(row) }
                .padding(vertical = TimeFlipSpacing.Space8),
        ) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.titleMedium,
                color = TimeFlipColor.OnBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(TimeFlipSpacing.Space4))
            Text(
                text = metaText,
                style = MaterialTheme.typography.bodyMedium,
                color = TimeFlipColor.OnSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(TimeFlipSize.MinTouchTarget)) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.library_row_more_cd, row.name),
                    tint = TimeFlipColor.OnBackground,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_menu_rename)) },
                    onClick = {
                        menuOpen = false
                        onRename(row)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_menu_duplicate)) },
                    onClick = {
                        menuOpen = false
                        onDuplicate(row)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_menu_delete), color = TimeFlipColor.Error) },
                    onClick = {
                        menuOpen = false
                        onDelete(row)
                    },
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(
    draft: String,
    busy: Boolean,
    onDraftChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_rename_title), style = MaterialTheme.typography.titleLarge) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                label = { Text(stringResource(R.string.library_rename_label)) },
                singleLine = true,
                supportingText = { Text(stringResource(R.string.library_rename_limit, ProjectRules.MAX_NAME_LENGTH)) },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = draft.isNotBlank() && !busy) {
                Text(stringResource(R.string.library_rename_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
        containerColor = TimeFlipColor.SurfaceRaised,
    )
}

private const val THUMBNAIL_EDGE_PX = 160

private fun messageTextRes(message: LibraryMessage): Int = when (message) {
    LibraryMessage.RENAMED -> R.string.library_msg_renamed
    LibraryMessage.DELETED -> R.string.library_msg_deleted
    LibraryMessage.DUPLICATED -> R.string.library_msg_duplicated
    LibraryMessage.ACTION_FAILED -> R.string.library_msg_failed
    LibraryMessage.PROJECT_MISSING -> R.string.library_msg_missing
}

@Preview(name = "Library empty", widthDp = 390, heightDp = 844)
@Composable
private fun LibraryEmptyPreview() {
    TimeFlipTheme {
        LibraryScreenContent(
            state = LibraryUiState(content = LibraryContent.Empty),
            loader = BitmapLoader { _, _ -> null },
            onNewTransformation = {},
            onOpenProject = {},
            onRename = {},
            onDuplicate = {},
            onDelete = {},
            onOpenSettings = {},
            onRetry = {},
            onMessageShown = {},
            onRenameDraftChange = {},
            onConfirmRename = {},
            onConfirmDelete = {},
            onDismissDialog = {},
        )
    }
}
