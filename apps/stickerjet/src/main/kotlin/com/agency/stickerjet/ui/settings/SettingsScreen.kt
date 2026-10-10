package com.agency.stickerjet.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agency.stickerjet.R
import com.agency.stickerjet.ui.components.StickerSecondaryButton
import com.agency.stickerjet.ui.components.StickerTopBar
import com.agency.stickerjet.ui.components.TypedConfirmDialog
import com.agency.stickerjet.ui.theme.PillShape
import com.agency.stickerjet.ui.theme.Spacing
import com.agency.stickerjet.ui.theme.TouchTarget

/**
 * Settings: the privacy statement, temporary-file cleanup, delete-all behind a typed confirmation,
 * licences and attribution, and the app version. [onDataDeleted] fires once after everything is gone.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onDataDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val version = remember(context) { versionName(context) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.delete) {
        if (state.delete == ActionState.DONE) onDataDeleted()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { StickerTopBar(title = stringResource(R.string.settings_title), onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Section(title = stringResource(R.string.settings_privacy_title)) {
                Text(
                    text = stringResource(R.string.settings_privacy_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            Section(title = stringResource(R.string.settings_clean_title)) {
                Text(
                    text = stringResource(R.string.settings_clean_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StickerSecondaryButton(
                    text = stringResource(R.string.settings_clean_action),
                    onClick = viewModel::clearTemporaryFiles,
                    enabled = state.cleanup != ActionState.WORKING,
                )
                ActionStatus(
                    state = state.cleanup,
                    doneText = stringResource(R.string.settings_clean_done),
                    failedText = stringResource(R.string.settings_clean_failed),
                )
            }
            Section(title = stringResource(R.string.settings_delete_title)) {
                Text(
                    text = stringResource(R.string.settings_delete_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DestructiveButton(
                    text = stringResource(R.string.settings_delete_action),
                    onClick = { confirmingDelete = true },
                    enabled = state.delete != ActionState.WORKING,
                )
                ActionStatus(
                    state = state.delete,
                    doneText = stringResource(R.string.settings_delete_done),
                    failedText = stringResource(R.string.settings_delete_failed),
                )
            }
            Section(title = stringResource(R.string.settings_licences_title)) {
                Text(stringResource(R.string.settings_licence_font), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
                Text(stringResource(R.string.settings_licence_compose), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
                Text(stringResource(R.string.settings_licence_icons), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
                Text(stringResource(R.string.settings_licence_mlkit), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
            }
            Section(title = stringResource(R.string.settings_version_title)) {
                Text(
                    text = stringResource(R.string.settings_version_value, version),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }

    if (confirmingDelete) {
        TypedConfirmDialog(
            title = stringResource(R.string.settings_delete_dialog_title),
            body = stringResource(R.string.settings_delete_dialog_body),
            fieldLabel = stringResource(R.string.settings_delete_typed_label),
            requiredText = stringResource(R.string.settings_delete_word),
            confirmLabel = stringResource(R.string.settings_delete_confirm),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = {
                confirmingDelete = false
                viewModel.deleteEverything()
            },
            onDismiss = { confirmingDelete = false },
        )
    }
}

/** A titled group. The title is a heading, so TalkBack users can move between sections. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        content()
    }
}

/** Working, done or failed, spoken and written. Nothing is shown while idle. */
@Composable
private fun ActionStatus(state: ActionState, doneText: String, failedText: String) {
    val message = when (state) {
        ActionState.IDLE -> return
        ActionState.WORKING -> stringResource(R.string.settings_working)
        ActionState.DONE -> doneText
        ActionState.FAILED -> failedText
    }
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = if (state == ActionState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
    )
}

/** Destructive action in the error colour, with the same 48 dp minimum target as the other buttons. */
@Composable
private fun DestructiveButton(text: String, onClick: () -> Unit, enabled: Boolean) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = PillShape,
        modifier = Modifier.heightIn(min = TouchTarget.min),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Version name from the package, or an empty string when it cannot be read. */
private fun versionName(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
} catch (e: PackageManager.NameNotFoundException) {
    ""
}
