package com.agency.stickerjet.ui.importing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.agency.stickerjet.R
import com.agency.stickerjet.domain.StickerLimits
import com.agency.stickerjet.ui.common.StickerFormat
import com.agency.stickerjet.ui.components.LoadingPanel
import com.agency.stickerjet.ui.components.StatusKind
import com.agency.stickerjet.ui.components.StatusPanel
import com.agency.stickerjet.ui.components.StickerPrimaryButton
import com.agency.stickerjet.ui.components.StickerTopBar
import com.agency.stickerjet.ui.ports.ImportFailureKind
import com.agency.stickerjet.ui.theme.Spacing

/**
 * Import screen, stateless so every phase can be shown without the picker. The idle state explains the
 * limits and the privacy rule. Rejections and failures each carry a reason and a way forward.
 */
@Composable
fun ImportScreen(
    state: ImportUiState,
    onChoose: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { StickerTopBar(title = stringResource(R.string.import_title), onBack = onBack) },
    ) { padding ->
        val body = Modifier.fillMaxSize().padding(padding)
        when (val phase = state.phase) {
            ImportPhase.Idle -> IdleIntro(onChoose = onChoose, modifier = body)
            ImportPhase.Importing -> LoadingPanel(
                message = stringResource(R.string.import_busy),
                modifier = body,
                cancelLabel = stringResource(R.string.action_cancel),
                onCancel = onCancel,
            )
            is ImportPhase.Rejected -> StatusPanel(
                kind = StatusKind.WARNING,
                title = stringResource(R.string.import_rejected_title),
                body = importRejectionMessage(phase.reason),
                modifier = body,
                primaryLabel = stringResource(R.string.import_choose_another),
                onPrimary = onChoose,
            )
            is ImportPhase.Failed -> ImportFailedPanel(
                cause = phase.cause,
                onRetry = onRetry,
                onChoose = onChoose,
                modifier = body,
            )
            ImportPhase.Unsupported -> StatusPanel(
                kind = StatusKind.UNSUPPORTED,
                title = stringResource(R.string.import_unsupported_title),
                body = stringResource(R.string.import_unsupported_body),
                modifier = body,
            )
            is ImportPhase.Imported -> LoadingPanel(
                message = stringResource(R.string.import_opening),
                modifier = body,
            )
        }
    }
}

/** Idle state: what the clip must be, and what happens to it. */
@Composable
private fun IdleIntro(onChoose: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            text = stringResource(R.string.import_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(
                R.string.import_limits,
                StickerLimits.MAX_SOURCE_MS / MS_PER_SECOND,
                StickerLimits.MAX_SOURCE_BYTES / BYTES_PER_MB,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.import_privacy),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StickerPrimaryButton(
            text = stringResource(R.string.import_choose),
            onClick = onChoose,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Failure state. Lost access gets the permission treatment: the fix is to pick the file again. */
@Composable
private fun ImportFailedPanel(
    cause: ImportFailureKind,
    onRetry: () -> Unit,
    onChoose: () -> Unit,
    modifier: Modifier,
) {
    val accessLost = cause == ImportFailureKind.ACCESS_REVOKED
    StatusPanel(
        kind = if (accessLost) StatusKind.PERMISSION else StatusKind.ERROR,
        title = stringResource(if (accessLost) R.string.import_permission_title else R.string.import_failed_title),
        body = importFailureMessage(cause),
        modifier = modifier,
        primaryLabel = stringResource(R.string.action_retry),
        onPrimary = onRetry,
        secondaryLabel = stringResource(R.string.import_choose_another),
        onSecondary = onChoose,
    )
}

private const val MS_PER_SECOND: Long = 1_000L
private const val BYTES_PER_MB: Long = 1024L * 1024L
