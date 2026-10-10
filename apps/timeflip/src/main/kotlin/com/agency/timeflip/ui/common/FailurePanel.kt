package com.agency.timeflip.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.agency.timeflip.R
import com.agency.timeflip.domain.error.ErrorCategory
import com.agency.timeflip.ui.components.PrimaryButton
import com.agency.timeflip.ui.components.SecondaryButton
import com.agency.timeflip.ui.theme.TimeFlipColor
import com.agency.timeflip.ui.theme.TimeFlipSpacing

/**
 * Shows what went wrong and what to do next. Retry appears only when the same action can succeed
 * without changing any input. A missing photo copy offers [alternativeLabel] instead, which opens
 * the photo chooser. The icon and the words carry the status, not the colour.
 */
@Composable
fun FailurePanel(
    failure: UiFailure,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    alternativeLabel: String? = null,
    onAlternative: (() -> Unit)? = null,
) {
    val category: ErrorCategory? = (failure as? UiFailure.Known)?.error?.category
    val messageRes = category?.messageRes() ?: R.string.error_unexpected_message
    val nextRes = category?.nextStepRes() ?: R.string.error_unexpected_next
    val canRetry = when (failure) {
        UiFailure.Unexpected -> true
        is UiFailure.Known -> failure.error.retryable && category != ErrorCategory.SOURCE_MISSING
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(TimeFlipSpacing.Space8),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = TimeFlipColor.Error,
            )
            Spacer(Modifier.width(TimeFlipSpacing.Space8))
            Text(
                text = stringResource(messageRes),
                style = MaterialTheme.typography.titleMedium,
                color = TimeFlipColor.OnBackground,
            )
        }
        Text(
            text = stringResource(nextRes),
            style = MaterialTheme.typography.bodyMedium,
            color = TimeFlipColor.OnSurfaceVariant,
        )
        if (canRetry && onRetry != null) {
            PrimaryButton(
                text = stringResource(R.string.common_retry),
                onClick = onRetry,
                icon = Icons.Filled.Refresh,
            )
        }
        if (alternativeLabel != null && onAlternative != null) {
            SecondaryButton(text = alternativeLabel, onClick = onAlternative)
        }
    }
}
