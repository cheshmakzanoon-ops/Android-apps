package com.agency.stickerjet.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agency.stickerjet.R
import com.agency.stickerjet.domain.StickerProject
import com.agency.stickerjet.platform.ExportSharing
import com.agency.stickerjet.ui.components.StatusKind
import com.agency.stickerjet.ui.components.StatusPanel
import com.agency.stickerjet.ui.components.StickerTabRow
import com.agency.stickerjet.ui.components.StickerTextButton
import com.agency.stickerjet.ui.components.StickerTopBar
import com.agency.stickerjet.ui.components.LoadingPanel
import com.agency.stickerjet.ui.theme.Spacing
import com.agency.stickerjet.ui.theme.StickerShapes
import java.io.File

/**
 * Editor for one project: a top bar with undo and redo, four tabs, and a banner when the original video
 * is missing or a save failed. The tab set is Trim, Subject, Style and Preview, in that order.
 */
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.start() }
    val project = state.project
    val onShare: (File) -> Unit = { file ->
        context.startActivity(ExportSharing.shareIntent(context, file))
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            StickerTopBar(
                title = project?.title ?: stringResource(R.string.editor_title),
                onBack = onBack,
                actions = {
                    if (project != null) {
                        StickerTextButton(
                            text = stringResource(R.string.action_undo),
                            onClick = viewModel::undo,
                            enabled = state.canUndo,
                        )
                        StickerTextButton(
                            text = stringResource(R.string.action_redo),
                            onClick = viewModel::redo,
                            enabled = state.canRedo,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> LoadingPanel(
                    message = stringResource(R.string.editor_loading),
                    modifier = Modifier.fillMaxSize(),
                )
                state.notFound -> StatusPanel(
                    kind = StatusKind.ERROR,
                    title = stringResource(R.string.editor_not_found_title),
                    body = stringResource(R.string.editor_not_found_body),
                    modifier = Modifier.fillMaxSize(),
                    primaryLabel = stringResource(R.string.action_back),
                    onPrimary = onBack,
                )
                state.loadFailed -> StatusPanel(
                    kind = StatusKind.ERROR,
                    title = stringResource(R.string.error_generic_title),
                    body = stringResource(R.string.error_generic_body),
                    modifier = Modifier.fillMaxSize(),
                    primaryLabel = stringResource(R.string.action_retry),
                    onPrimary = viewModel::start,
                )
                project != null -> EditorBody(state = state, project = project, viewModel = viewModel, onShare = onShare)
            }
        }
    }
}

/** Banners plus the active tab. Each tab fills the space left under the tab row. */
@Composable
private fun EditorBody(
    state: EditorUiState,
    project: StickerProject,
    viewModel: EditorViewModel,
    onShare: (File) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (state.sourceMissing) {
            NoticeBanner(text = stringResource(R.string.editor_source_missing_body))
        }
        if (state.saveFailed) {
            NoticeBanner(text = stringResource(R.string.editor_save_failed))
        }
        StickerTabRow(
            titles = listOf(
                stringResource(R.string.editor_tab_trim),
                stringResource(R.string.editor_tab_subject),
                stringResource(R.string.editor_tab_style),
                stringResource(R.string.editor_tab_preview),
            ),
            selected = state.tab.ordinal,
            onSelect = { viewModel.selectTab(EditorTab.entries[it]) },
        )
        val tabModifier = Modifier.fillMaxWidth().weight(1f)
        when (state.tab) {
            EditorTab.TRIM -> TrimTab(
                project = project,
                loop = state.trimLoop,
                onStartDrag = viewModel::onTrimStartDrag,
                onEndDrag = viewModel::onTrimEndDrag,
                onFinished = viewModel::onTrimFinished,
                modifier = tabModifier,
            )
            EditorTab.SUBJECT -> SubjectTab(
                state = state,
                project = project,
                onSetBackground = viewModel::setBackground,
                onDetect = viewModel::detectSubject,
                onStroke = viewModel::addStroke,
                onResetMask = viewModel::resetMask,
                modifier = tabModifier,
            )
            EditorTab.STYLE -> StyleTab(
                state = state,
                project = project,
                onPlacementDrag = viewModel::onPlacementDrag,
                onPlacementFinished = viewModel::onPlacementFinished,
                onRotate = viewModel::rotate,
                onResetPlacement = viewModel::resetPlacement,
                onCaptionCommit = viewModel::commitCaption,
                onCaptionPosition = viewModel::setCaptionPosition,
                onCaptionColor = viewModel::setCaptionColor,
                modifier = tabModifier,
            )
            EditorTab.PREVIEW -> PreviewTab(
                state = state,
                project = project,
                onRefresh = viewModel::refreshPreview,
                onStartExport = viewModel::startExport,
                onCancelExport = viewModel::cancelExport,
                onDismissExport = viewModel::dismissExport,
                onShare = onShare,
                modifier = tabModifier,
            )
        }
    }
}

/** A warning line under the top bar. It is text with an icon, so the state does not depend on colour. */
@Composable
private fun NoticeBanner(text: String) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.xs)
            .clip(StickerShapes.medium)
            .background(colors.errorContainer)
            .padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = colors.onErrorContainer,
            modifier = Modifier.width(24.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
    }
}
