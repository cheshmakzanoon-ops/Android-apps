package com.agency.stickerjet.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agency.stickerjet.R
import com.agency.stickerjet.ui.common.FileThumbnail
import com.agency.stickerjet.ui.common.StickerFormat
import com.agency.stickerjet.ui.components.StickerTextButton
import com.agency.stickerjet.ui.theme.Spacing
import com.agency.stickerjet.ui.theme.StickerShapes
import com.agency.stickerjet.ui.theme.TouchTarget

/** One draft in the grid: thumbnail, title, length or a missing-source warning, and a delete action. */
@Composable
fun ProjectTile(
    item: LibraryItem,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = item.project.title
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        FileThumbnail(
            file = item.thumbnail,
            contentDescription = stringResource(R.string.library_thumbnail_cd, title),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.library_open_cd, title),
                    onClick = onOpen,
                ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (item.sourceMissing) {
                        stringResource(R.string.library_source_missing)
                    } else {
                        stringResource(R.string.library_length, StickerFormat.seconds(item.project.trim.durationMs))
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (item.sourceMissing) colors.error else colors.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(TouchTarget.min)) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.library_delete_cd, title),
                    tint = colors.onBackground,
                )
            }
        }
    }
}

/** A recent export: its title, size and length, with Open and Share. Share is hidden when the file is gone. */
@Composable
fun RecentExportCard(
    export: RecentExport,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .width(CARD_WIDTH)
            .clip(StickerShapes.medium)
            .background(colors.surfaceContainer)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text = export.projectTitle,
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(
                R.string.library_export_meta,
                StickerFormat.kilobytes(export.record.sizeBytes),
                StickerFormat.seconds(export.record.durationMs),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        if (export.fileExists) {
            val openLabel = stringResource(R.string.action_open)
            val shareLabel = stringResource(R.string.action_share)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                StickerTextButton(
                    text = openLabel,
                    onClick = onOpen,
                    modifier = Modifier.semantics {
                        contentDescription = "$openLabel: ${export.projectTitle}"
                    },
                )
                StickerTextButton(
                    text = shareLabel,
                    onClick = onShare,
                    modifier = Modifier.semantics {
                        contentDescription = "$shareLabel: ${export.projectTitle}"
                    },
                )
            }
        } else {
            Text(
                text = stringResource(R.string.library_export_missing),
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
            )
        }
    }
}

/** Section heading for a group in the library. Marked as a heading for TalkBack. */
@Composable
fun LibrarySectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.semantics { heading() },
    )
}

private val CARD_WIDTH = 200.dp
