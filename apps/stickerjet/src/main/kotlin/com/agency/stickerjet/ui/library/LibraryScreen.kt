package com.agency.stickerjet.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agency.stickerjet.R
import com.agency.stickerjet.platform.ExportSharing
import com.agency.stickerjet.ui.components.ConfirmDialog
import com.agency.stickerjet.ui.components.LoadingPanel
import com.agency.stickerjet.ui.components.StatusKind
import com.agency.stickerjet.ui.components.StatusPanel
import com.agency.stickerjet.ui.components.StickerPrimaryButton
import com.agency.stickerjet.ui.components.StickerTopBar
import com.agency.stickerjet.ui.theme.Spacing
import com.agency.stickerjet.ui.theme.TouchTarget
import java.io.File

/**
 * Library: drafts as a grid, recent exports above them, and a bottom Import button. The list reloads
 * whenever the screen resumes, so an export or a deletion made in another screen shows up at once.
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenProject: (String) -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val deletedText = stringResource(R.string.library_notice_deleted)
    val deleteFailedText = stringResource(R.string.library_notice_delete_failed)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(if (notice == LibraryNotice.DELETED) deletedText else deleteFailedText)
        viewModel.clearNotice()
    }
    val onShare: (File) -> Unit = { file -> context.startActivity(ExportSharing.shareIntent(context, file)) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            StickerTopBar(
                title = stringResource(R.string.library_title),
                actions = {
                    IconButton(onClick = onSettings, modifier = Modifier.size(TouchTarget.min)) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.action_settings),
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (state.items.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = Spacing.gutter, vertical = Spacing.md),
                    contentAlignment = Alignment.Center,
                ) {
                    StickerPrimaryButton(
                        text = stringResource(R.string.action_import),
                        onClick = onImport,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LibraryContent(
            state = state,
            onOpen = onOpenProject,
            onShare = onShare,
            onDelete = viewModel::requestDelete,
            onRetry = viewModel::refresh,
            onImport = onImport,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }

    state.pendingDelete?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.library_delete_title, target.project.title),
            body = stringResource(R.string.library_delete_body),
            confirmLabel = stringResource(R.string.library_delete_confirm),
            dismissLabel = stringResource(R.string.action_cancel),
            destructive = true,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::cancelDelete,
        )
    }
}

/** Chooses between loading, failure, the empty state, and the grid. */
@Composable
private fun LibraryContent(
    state: LibraryUiState,
    onOpen: (String) -> Unit,
    onShare: (File) -> Unit,
    onDelete: (LibraryItem) -> Unit,
    onRetry: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier,
) {
    when {
        state.loading && state.items.isEmpty() -> LoadingPanel(
            message = stringResource(R.string.library_loading),
            modifier = modifier,
        )
        state.loadFailed -> StatusPanel(
            kind = StatusKind.ERROR,
            title = stringResource(R.string.library_error_title),
            body = stringResource(R.string.library_error_body),
            modifier = modifier,
            primaryLabel = stringResource(R.string.action_retry),
            onPrimary = onRetry,
        )
        state.items.isEmpty() -> StatusPanel(
            kind = StatusKind.EMPTY,
            title = stringResource(R.string.library_empty_title),
            body = stringResource(R.string.library_empty_body),
            modifier = modifier,
            primaryLabel = stringResource(R.string.action_import),
            onPrimary = onImport,
        )
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = TILE_MIN_WIDTH),
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (state.recentExports.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LibrarySectionTitle(text = stringResource(R.string.library_recent_title))
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        items(items = state.recentExports, key = { it.file.path }) { export ->
                            RecentExportCard(
                                export = export,
                                onOpen = { onOpen(export.projectId) },
                                onShare = { onShare(export.file) },
                            )
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                LibrarySectionTitle(text = stringResource(R.string.library_drafts_title))
            }
            items(items = state.items, key = { it.project.id }) { entry ->
                ProjectTile(
                    item = entry,
                    onOpen = { onOpen(entry.project.id) },
                    onDelete = { onDelete(entry) },
                )
            }
        }
    }
}

private val TILE_MIN_WIDTH = 152.dp
