@file:OptIn(ExperimentalMaterial3Api::class)

package com.agency.timeflip.ui.screens.newpair

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agency.timeflip.R
import com.agency.timeflip.domain.model.PhotoSlot
import com.agency.timeflip.domain.model.PhotoSourceRef
import com.agency.timeflip.domain.model.ProjectId
import com.agency.timeflip.domain.model.SourcePhoto
import com.agency.timeflip.platform.rememberSystemPhotoPicker
import com.agency.timeflip.ui.BitmapLoader
import com.agency.timeflip.ui.TimeFlipTheme
import com.agency.timeflip.ui.UiDependencies
import com.agency.timeflip.ui.common.BackTopBar
import com.agency.timeflip.ui.common.FailurePanel
import com.agency.timeflip.ui.common.PhotoThumbnail
import com.agency.timeflip.ui.components.BottomActionBar
import com.agency.timeflip.ui.components.PrimaryButton
import com.agency.timeflip.ui.components.SecondaryButton
import com.agency.timeflip.ui.components.TextActionButton
import com.agency.timeflip.ui.navigation.newPairFactory
import com.agency.timeflip.ui.theme.TimeFlipColor
import com.agency.timeflip.ui.theme.TimeFlipRadius
import com.agency.timeflip.ui.theme.TimeFlipSize
import com.agency.timeflip.ui.theme.TimeFlipSpacing
import androidx.compose.runtime.remember

/**
 * Chooses the BEFORE and AFTER photos. When both are imported, a new transformation is created and
 * the wizard continues to alignment. An existing transformation is changed with Continue.
 */
@Composable
fun NewPairRoute(
    deps: UiDependencies,
    onLeave: () -> Unit,
    onReady: (ProjectId) -> Unit,
) {
    val vm: NewPairViewModel = viewModel<NewPairViewModel>(factory = newPairFactory(deps))
    val state by vm.state.collectAsStateWithLifecycle()

    val pickBefore = rememberSystemPhotoPicker { ref: PhotoSourceRef -> vm.onPicked(PhotoSlot.BEFORE, ref) }
    val pickAfter = rememberSystemPhotoPicker { ref: PhotoSourceRef -> vm.onPicked(PhotoSlot.AFTER, ref) }

    BackHandler { vm.cancel(onLeave) }

    LaunchedEffect(state.readyProjectId) {
        val id = state.readyProjectId
        if (id != null) {
            vm.consumeReady()
            onReady(id)
        }
    }

    NewPairScreenContent(
        state = state,
        loader = deps.bitmaps,
        onChoose = { slot -> if (slot == PhotoSlot.BEFORE) pickBefore() else pickAfter() },
        onRemove = vm::remove,
        onSwap = vm::swap,
        onRetryImport = vm::retryImport,
        onDismissImportFailure = vm::dismissImportFailure,
        onContinue = vm::continueToAlignment,
        onRetrySave = vm::continueToAlignment,
        onBack = { vm.cancel(onLeave) },
    )
}

@Composable
fun NewPairScreenContent(
    state: NewPairUiState,
    loader: BitmapLoader,
    onChoose: (PhotoSlot) -> Unit,
    onRemove: (PhotoSlot) -> Unit,
    onSwap: () -> Unit,
    onRetryImport: () -> Unit,
    onDismissImportFailure: () -> Unit,
    onContinue: () -> Unit,
    onRetrySave: () -> Unit,
    onBack: () -> Unit,
) {
    val title = if (state.editing) R.string.new_pair_edit_title else R.string.new_pair_title
    Scaffold(
        topBar = { BackTopBar(title = stringResource(title), onBack = onBack) },
        bottomBar = {
            if (state.editing) {
                BottomActionBar {
                    PrimaryButton(
                        text = stringResource(R.string.new_pair_continue),
                        onClick = onContinue,
                        enabled = state.canContinue,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        containerColor = TimeFlipColor.Background,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = TimeFlipSpacing.Space16, vertical = TimeFlipSpacing.Space8),
            verticalArrangement = Arrangement.spacedBy(TimeFlipSpacing.Space16),
        ) {
            if (state.loading) {
                CircularProgressIndicator(color = TimeFlipColor.Accent)
                Text(
                    text = stringResource(R.string.new_pair_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TimeFlipColor.OnSurfaceVariant,
                )
            }

            state.loadFailure?.let { FailurePanel(failure = it) }

            if (!state.loading && state.loadFailure == null) {
                Text(
                    text = stringResource(
                        if (state.editing) R.string.new_pair_edit_hint else R.string.new_pair_hint,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TimeFlipColor.OnSurfaceVariant,
                )

                PhotoSlotCard(
                    slot = PhotoSlot.BEFORE,
                    photo = state.before,
                    loader = loader,
                    importing = state.importing == PhotoSlot.BEFORE,
                    missing = PhotoSlot.BEFORE in state.missingSlots,
                    canRemove = !state.editing,
                    onChoose = { onChoose(PhotoSlot.BEFORE) },
                    onRemove = { onRemove(PhotoSlot.BEFORE) },
                )

                PhotoSlotCard(
                    slot = PhotoSlot.AFTER,
                    photo = state.after,
                    loader = loader,
                    importing = state.importing == PhotoSlot.AFTER,
                    missing = PhotoSlot.AFTER in state.missingSlots,
                    canRemove = !state.editing,
                    onChoose = { onChoose(PhotoSlot.AFTER) },
                    onRemove = { onRemove(PhotoSlot.AFTER) },
                )

                if (state.editing && state.before != null && state.after != null) {
                    SecondaryButton(
                        text = stringResource(R.string.new_pair_swap),
                        onClick = onSwap,
                        enabled = state.importing == null && !state.saving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                state.importFailure?.let { failure ->
                    FailurePanel(
                        failure = failure.failure,
                        onRetry = onRetryImport,
                        alternativeLabel = stringResource(R.string.common_dismiss),
                        onAlternative = onDismissImportFailure,
                    )
                }

                state.saveFailure?.let { FailurePanel(failure = it, onRetry = onRetrySave) }

                if (state.saving) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp).width(20.dp),
                            color = TimeFlipColor.Accent,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(TimeFlipSpacing.Space12))
                        Text(
                            text = stringResource(R.string.new_pair_saving),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TimeFlipColor.OnSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoSlotCard(
    slot: PhotoSlot,
    photo: SourcePhoto?,
    loader: BitmapLoader,
    importing: Boolean,
    missing: Boolean,
    canRemove: Boolean,
    onChoose: () -> Unit,
    onRemove: () -> Unit,
) {
    val isBefore = slot == PhotoSlot.BEFORE
    val label = stringResource(if (isBefore) R.string.new_pair_before_label else R.string.new_pair_after_label)
    val chooseText = stringResource(if (isBefore) R.string.new_pair_choose_before else R.string.new_pair_choose_after)
    val replaceText = stringResource(if (isBefore) R.string.new_pair_replace_before else R.string.new_pair_replace_after)
    val removeText = stringResource(R.string.new_pair_remove)
    val description = stringResource(if (isBefore) R.string.new_pair_before_preview_cd else R.string.new_pair_after_preview_cd)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TimeFlipColor.Surface, RoundedCornerShape(TimeFlipRadius.Medium))
            .padding(TimeFlipSpacing.Space12),
        verticalArrangement = Arrangement.spacedBy(TimeFlipSpacing.Space8),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = TimeFlipColor.OnBackground,
                modifier = Modifier.weight(1f),
            )
            if (photo != null && !importing) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = stringResource(R.string.new_pair_photo_ready_cd),
                    tint = TimeFlipColor.Positive,
                )
            }
        }

        PhotoThumbnail(
            photoId = photo?.id,
            loader = loader,
            maxEdge = PREVIEW_EDGE_PX,
            contentDescription = description,
            modifier = Modifier.fillMaxWidth().height(TimeFlipSize.PhotoSlotHeight),
        )

        if (photo == null && !importing) {
            Text(
                text = stringResource(R.string.new_pair_no_photo),
                style = MaterialTheme.typography.bodyMedium,
                color = TimeFlipColor.OnSurfaceVariant,
            )
        }

        if (importing) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = TimeFlipColor.Accent,
            )
            Text(
                text = stringResource(R.string.new_pair_importing),
                style = MaterialTheme.typography.bodyMedium,
                color = TimeFlipColor.OnSurfaceVariant,
            )
        }

        if (missing) {
            Text(
                text = stringResource(R.string.new_pair_slot_missing),
                style = MaterialTheme.typography.bodyMedium,
                color = TimeFlipColor.Error,
            )
        }

        if (photo == null) {
            PrimaryButton(
                text = chooseText,
                onClick = onChoose,
                enabled = !importing,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            SecondaryButton(
                text = replaceText,
                onClick = onChoose,
                enabled = !importing,
                modifier = Modifier.fillMaxWidth(),
            )
            if (canRemove) {
                TextActionButton(
                    text = removeText,
                    onClick = onRemove,
                    enabled = !importing,
                )
            }
        }
    }
}

private const val PREVIEW_EDGE_PX = 480

@Preview(name = "New pair, empty", widthDp = 390, heightDp = 844)
@Composable
private fun NewPairEmptyPreview() {
    TimeFlipTheme {
        NewPairScreenContent(
            state = NewPairUiState(),
            loader = BitmapLoader { _, _ -> null },
            onChoose = {},
            onRemove = {},
            onSwap = {},
            onRetryImport = {},
            onDismissImportFailure = {},
            onContinue = {},
            onRetrySave = {},
            onBack = {},
        )
    }
}
