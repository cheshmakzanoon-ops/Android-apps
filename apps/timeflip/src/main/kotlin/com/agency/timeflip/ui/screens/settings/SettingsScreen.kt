@file:OptIn(ExperimentalMaterial3Api::class)

package com.agency.timeflip.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agency.timeflip.R
import com.agency.timeflip.ui.TimeFlipTheme
import com.agency.timeflip.ui.UiDependencies
import com.agency.timeflip.ui.common.BackTopBar
import com.agency.timeflip.ui.common.ConfirmDialog
import com.agency.timeflip.ui.common.FailurePanel
import com.agency.timeflip.ui.navigation.settingsFactory
import com.agency.timeflip.ui.theme.TimeFlipColor
import com.agency.timeflip.ui.theme.TimeFlipRadius
import com.agency.timeflip.ui.theme.TimeFlipSize
import com.agency.timeflip.ui.theme.TimeFlipSpacing

@Composable
fun SettingsRoute(deps: UiDependencies, onBack: () -> Unit) {
    val vm: SettingsViewModel = viewModel<SettingsViewModel>(factory = settingsFactory(deps))
    val state by vm.state.collectAsStateWithLifecycle()
    SettingsScreenContent(
        state = state,
        onBack = onBack,
        onDeleteAllRequested = vm::requestDeleteAll,
        onDismissDelete = vm::dismissDeleteAll,
        onConfirmDelete = vm::deleteAll,
    )
}

/** Stateless settings screen. Previews use it with sample state. */
@Composable
fun SettingsScreenContent(
    state: SettingsUiState,
    onBack: () -> Unit,
    onDeleteAllRequested: () -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    Scaffold(
        topBar = { BackTopBar(title = stringResource(R.string.settings_title), onBack = onBack) },
        containerColor = TimeFlipColor.Background,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = TimeFlipSpacing.Space16, vertical = TimeFlipSpacing.Space8),
            verticalArrangement = Arrangement.spacedBy(TimeFlipSpacing.Space16),
        ) {
            Section(title = stringResource(R.string.settings_privacy_heading)) {
                PrivacyPoint(stringResource(R.string.settings_privacy_device))
                PrivacyPoint(stringResource(R.string.settings_privacy_no_upload))
                PrivacyPoint(stringResource(R.string.settings_privacy_no_account))
                PrivacyPoint(stringResource(R.string.settings_privacy_permissions))
            }

            Section(title = stringResource(R.string.settings_data_heading)) {
                Text(
                    text = stringResource(R.string.settings_delete_explain),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TimeFlipColor.OnSurfaceVariant,
                )
                if (state.working) {
                    Text(
                        text = stringResource(R.string.settings_deleting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TimeFlipColor.OnBackground,
                    )
                }
                OutlinedButton(
                    onClick = onDeleteAllRequested,
                    enabled = !state.working,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = TimeFlipSize.MinTouchTarget),
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, TimeFlipColor.Error),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TimeFlipColor.Error),
                ) {
                    Text(
                        text = stringResource(R.string.settings_delete_all),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                if (state.deleted) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = TimeFlipColor.Positive,
                        )
                        Spacer(Modifier.width(TimeFlipSpacing.Space8))
                        Text(
                            text = stringResource(R.string.settings_deleted),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TimeFlipColor.OnBackground,
                        )
                    }
                }
                state.failure?.let { FailurePanel(failure = it) }
            }
        }
    }

    if (state.confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.settings_delete_title),
            body = stringResource(R.string.settings_delete_body),
            confirmText = stringResource(R.string.settings_delete_confirm),
            onConfirm = onConfirmDelete,
            onDismiss = onDismissDelete,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TimeFlipColor.Surface, RoundedCornerShape(TimeFlipRadius.Medium))
            .padding(TimeFlipSpacing.Space16),
        verticalArrangement = Arrangement.spacedBy(TimeFlipSpacing.Space12),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = TimeFlipColor.OnBackground,
            modifier = Modifier.semantics { heading() },
        )
        content()
    }
}

@Composable
private fun PrivacyPoint(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = TimeFlipColor.Accent,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.width(TimeFlipSpacing.Space12))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = TimeFlipColor.OnBackground,
            modifier = Modifier.weight(1f),
        )
    }
}

@Preview(name = "Settings", widthDp = 390, heightDp = 844)
@Composable
private fun SettingsPreview() {
    TimeFlipTheme {
        SettingsScreenContent(
            state = SettingsUiState(),
            onBack = {},
            onDeleteAllRequested = {},
            onDismissDelete = {},
            onConfirmDelete = {},
        )
    }
}
