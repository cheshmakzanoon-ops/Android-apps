package com.agency.timeflip.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import com.agency.timeflip.ui.theme.TimeFlipColor

/**
 * A choice chip. The selected state shows a check mark as well as the accent colour, so the
 * selection never depends on colour alone. The touch target is at least 48 dp.
 */
@Composable
fun SelectableChip(
    label: String,
    selected: Boolean,
    onSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val checkIcon: @Composable () -> Unit = {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
    }
    FilterChip(
        selected = selected,
        onClick = onSelected,
        label = { Text(text = label, style = MaterialTheme.typography.labelLarge) },
        leadingIcon = if (selected) checkIcon else null,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = TimeFlipColor.SurfaceVariant,
            labelColor = TimeFlipColor.OnBackground,
            selectedContainerColor = TimeFlipColor.AccentContainer,
            selectedLabelColor = TimeFlipColor.OnAccentContainer,
            selectedLeadingIconColor = TimeFlipColor.OnAccentContainer,
        ),
        modifier = modifier.minimumInteractiveComponentSize(),
    )
}
