package com.agency.timeflip.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import com.agency.timeflip.R
import com.agency.timeflip.ui.theme.TimeFlipColor
import androidx.compose.ui.res.stringResource

/**
 * Asks before a destructive action. The confirm button is the destructive one, and it is never the
 * default: the cancel button is the dismiss action.
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(text = body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = confirmText, color = TimeFlipColor.Error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.common_cancel))
            }
        },
        containerColor = TimeFlipColor.SurfaceRaised,
        textContentColor = TimeFlipColor.OnSurfaceVariant,
        titleContentColor = TimeFlipColor.OnBackground,
    )
}
