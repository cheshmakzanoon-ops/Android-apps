package com.agency.stickerjet.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.agency.stickerjet.ui.theme.TouchTarget

/**
 * Tab row for the editor. The selected indicator is Ink, not Jet: Jet on Paper is 2.7:1, and the
 * selected state must stay visible. Every tab is at least [TouchTarget.min] tall.
 */
@Composable
fun StickerTabRow(
    titles: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    TabRow(
        selectedTabIndex = selected,
        modifier = modifier,
        containerColor = colors.background,
        contentColor = colors.onBackground,
        divider = { HorizontalDivider(color = colors.outlineVariant) },
    ) {
        titles.forEachIndexed { index, title ->
            Tab(
                selected = index == selected,
                onClick = { onSelect(index) },
                modifier = Modifier.heightIn(min = TouchTarget.min),
                selectedContentColor = colors.onBackground,
                unselectedContentColor = colors.onSurfaceVariant,
                text = { Text(text = title, style = MaterialTheme.typography.labelLarge, maxLines = 1) },
            )
        }
    }
}
