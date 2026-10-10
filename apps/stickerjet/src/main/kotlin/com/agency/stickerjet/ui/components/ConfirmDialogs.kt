package com.agency.stickerjet.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.agency.stickerjet.ui.theme.PillShape
import com.agency.stickerjet.ui.theme.Spacing
import com.agency.stickerjet.ui.theme.StickerShapes
import com.agency.stickerjet.ui.theme.TouchTarget

/**
 * Two-button confirmation. A [destructive] confirm uses the error colour so it is never mistaken for
 * the safe choice. Dismissing (back, scrim or [dismissLabel]) never performs the action.
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    dismissLabel: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = StickerShapes.large,
        title = { Text(text = title, style = MaterialTheme.typography.headlineSmall) },
        text = { Text(text = body, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = PillShape,
                modifier = Modifier.defaultMinSize(minHeight = TouchTarget.min),
                colors = if (destructive) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                },
            ) {
                Text(text = confirmLabel, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            StickerTextButton(text = dismissLabel, onClick = onDismiss)
        },
    )
}

/**
 * Confirmation that requires the user to type [requiredText] exactly (after trimming spaces). Used
 * for deleting all data, where a single tap is too easy to hit by accident.
 */
@Composable
fun TypedConfirmDialog(
    title: String,
    body: String,
    fieldLabel: String,
    requiredText: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by rememberSaveable { mutableStateOf("") }
    val matches = typed.trim() == requiredText
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = StickerShapes.large,
        title = { Text(text = title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(text = body, style = MaterialTheme.typography.bodyLarge)
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(text = fieldLabel) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.md),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = matches,
                shape = PillShape,
                modifier = Modifier.defaultMinSize(minHeight = TouchTarget.min),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(text = confirmLabel, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            StickerTextButton(text = dismissLabel, onClick = onDismiss)
        },
    )
}
