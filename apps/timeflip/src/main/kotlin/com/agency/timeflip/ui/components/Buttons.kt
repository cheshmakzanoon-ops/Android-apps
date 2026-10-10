package com.agency.timeflip.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.agency.timeflip.ui.theme.TimeFlipColor
import com.agency.timeflip.ui.theme.TimeFlipSize
import com.agency.timeflip.ui.theme.TimeFlipSpacing

/** Main action of a screen. Full width in the bottom bar, so it is reachable with one hand. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = TimeFlipSize.MinTouchTarget),
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = TimeFlipColor.Accent,
            contentColor = TimeFlipColor.OnAccent,
            disabledContainerColor = TimeFlipColor.SurfaceRaised,
            disabledContentColor = TimeFlipColor.OnSurfaceVariant,
        ),
    ) {
        LabelWithIcon(text = text, icon = icon)
    }
}

/** Secondary action with an outline. Used beside a primary action. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = TimeFlipSize.MinTouchTarget),
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TimeFlipColor.OnBackground),
    ) {
        LabelWithIcon(text = text, icon = icon)
    }
}

/** Low-emphasis action such as "Remove" or "Reset". */
@Composable
fun TextActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = TimeFlipSize.MinTouchTarget),
        colors = ButtonDefaults.textButtonColors(contentColor = TimeFlipColor.Accent),
    ) {
        LabelWithIcon(text = text, icon = icon)
    }
}

@Composable
private fun LabelWithIcon(text: String, icon: ImageVector?) {
    Row {
        if (icon != null) {
            // The icon is decorative: the text next to it names the action.
            Icon(imageVector = icon, contentDescription = null)
            Spacer(Modifier.width(TimeFlipSpacing.Space8))
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}
