package com.agency.stickerjet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.agency.stickerjet.ui.theme.Spacing

/** The kinds of non-content state a screen can show. Each maps to one icon and one colour role. */
enum class StatusKind { EMPTY, ERROR, PERMISSION, UNSUPPORTED, WARNING }

/** Icon and colours for one [StatusKind]. */
private class StatusLook(val icon: ImageVector, val background: Color, val tint: Color)

/**
 * Empty, error, permission and unsupported states share one layout: an icon, a title, a sentence of
 * explanation and at most two actions. The text is announced politely when it appears.
 */
@Composable
fun StatusPanel(
    kind: StatusKind,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val look: StatusLook = when (kind) {
        StatusKind.EMPTY -> StatusLook(Icons.Filled.Add, colors.secondaryContainer, colors.onSecondaryContainer)
        StatusKind.ERROR -> StatusLook(Icons.Filled.Warning, colors.errorContainer, colors.onErrorContainer)
        StatusKind.PERMISSION -> StatusLook(Icons.Filled.Lock, colors.tertiaryContainer, colors.onTertiaryContainer)
        StatusKind.UNSUPPORTED -> StatusLook(Icons.Filled.Info, colors.surfaceVariant, colors.onSurfaceVariant)
        StatusKind.WARNING -> StatusLook(Icons.Filled.Warning, colors.primaryContainer, colors.onPrimaryContainer)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.lg)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(look.background, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // The title carries the meaning; the icon is decorative.
            Icon(imageVector = look.icon, contentDescription = null, tint = look.tint, modifier = Modifier.size(36.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = colors.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (primaryLabel != null && onPrimary != null) {
            StickerPrimaryButton(text = primaryLabel, onClick = onPrimary)
        }
        if (secondaryLabel != null && onSecondary != null) {
            StickerTextButton(text = secondaryLabel, onClick = onSecondary)
        }
    }
}

/**
 * Busy state. Pass [progress] (0..1) when the work can be measured; otherwise the indicator spins.
 * A cancel action is shown when [onCancel] is set, so long work is never a dead end.
 */
@Composable
fun LoadingPanel(
    message: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    cancelLabel: String? = null,
    onCancel: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.lg)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (progress == null) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        } else {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
        Text(text = message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
        if (cancelLabel != null && onCancel != null) {
            StickerSecondaryButton(text = cancelLabel, onClick = onCancel)
        }
    }
}
