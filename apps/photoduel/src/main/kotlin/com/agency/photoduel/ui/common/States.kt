package com.agency.photoduel.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.agency.photoduel.R
import com.agency.photoduel.domain.model.PhotoDuelError
import com.agency.photoduel.ui.state.Loadable
import com.agency.photoduel.ui.theme.LocalPdPalette
import com.agency.photoduel.ui.theme.PdSpace

/** Centred, scrollable column limited to a readable width. Used by full-screen states. */
@Composable
fun CenteredMessage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = PdSpace.contentMaxWidth)
                .verticalScroll(rememberScrollState())
                .padding(PdSpace.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PdSpace.md),
            content = content,
        )
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier, label: String = stringResource(R.string.loading)) {
    CenteredMessage(modifier) {
        CircularProgressIndicator()
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Full-screen failure with the reason and, when it can help, a retry button. */
@Composable
fun ErrorState(
    error: PhotoDuelError,
    modifier: Modifier = Modifier,
    reconnecting: Boolean = false,
    title: String = if (reconnecting) stringResource(R.string.error_reconnecting_title) else stringResource(R.string.error_title),
    message: String = if (reconnecting) stringResource(R.string.error_reconnecting_body) else errorMessage(error),
    onRetry: (() -> Unit)? = null,
) {
    CenteredMessage(modifier) {
        if (reconnecting) {
            CircularProgressIndicator()
        } else {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (onRetry != null && (reconnecting || error.canRetry())) {
            PdButton(text = stringResource(R.string.action_retry), onClick = onRetry, style = PdButtonStyle.SECONDARY)
        }
    }
}

/** Nothing to show yet, with one suggested next step. */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.Info,
    action: (@Composable () -> Unit)? = null,
) {
    CenteredMessage(modifier) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        action?.invoke()
    }
}

/** Thin banner shown across the top of the app while the last call failed for lack of a connection. */
@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = PdSpace.md, vertical = PdSpace.xs)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PdSpace.xs),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.inverseOnSurface)
            Text(text = stringResource(R.string.offline_banner), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** An error inside a screen that still shows its content, for example a refused action. */
@Composable
fun InlineError(error: PhotoDuelError, modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null) {
    InlineNotice(text = errorMessage(error), modifier = modifier, isError = true, onDismiss = onDismiss)
}

@Composable
fun InlineNotice(text: String, modifier: Modifier = Modifier, isError: Boolean = false, onDismiss: (() -> Unit)? = null) {
    val palette = LocalPdPalette.current
    val background = if (isError) palette.errorContainer else palette.accentContainer
    val foreground = if (isError) palette.onErrorContainer else palette.onAccentContainer
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background, MaterialTheme.shapes.medium)
            .padding(start = PdSpace.md, top = PdSpace.xs, bottom = PdSpace.xs, end = PdSpace.xxs)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isError) Icons.Filled.Warning else Icons.Filled.Info,
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = text,
            color = foreground,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = PdSpace.sm, vertical = PdSpace.xs),
        )
        if (onDismiss != null) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_dismiss), tint = foreground)
            }
        }
    }
}

/** Shown above content that could not be refreshed. */
@Composable
fun StaleNotice(onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
            .padding(start = PdSpace.md, end = PdSpace.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.stale_content),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(vertical = PdSpace.sm),
        )
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
        }
    }
}

/**
 * Renders a [Loadable]: a spinner while loading, the error (or "reconnecting") when it failed, and
 * [content] when loaded. [content] receives whether the value is stale.
 */
@Composable
fun <T> LoadableContent(
    loadable: Loadable<T>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    failure: (@Composable (PhotoDuelError, Boolean) -> Unit)? = null,
    content: @Composable (value: T, stale: Boolean) -> Unit,
) {
    when (loadable) {
        is Loadable.Loading -> LoadingState(modifier)
        is Loadable.Failed -> if (failure != null) {
            failure(loadable.error, loadable.reconnecting)
        } else {
            ErrorState(error = loadable.error, reconnecting = loadable.reconnecting, onRetry = onRetry, modifier = modifier)
        }
        is Loadable.Loaded -> content(loadable.value, loadable.stale)
    }
}
