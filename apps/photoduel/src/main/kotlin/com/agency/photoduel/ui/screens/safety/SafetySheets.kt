@file:OptIn(ExperimentalMaterial3Api::class)

package com.agency.photoduel.ui.screens.safety

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agency.photoduel.R
import com.agency.photoduel.domain.model.ReportReason
import com.agency.photoduel.domain.model.ReportTarget
import com.agency.photoduel.ui.common.AppEnv
import com.agency.photoduel.ui.common.InlineError
import com.agency.photoduel.ui.common.PdButton
import com.agency.photoduel.ui.common.PdButtonStyle
import com.agency.photoduel.ui.common.reportReasonLabel
import com.agency.photoduel.ui.state.BlockState
import com.agency.photoduel.ui.state.Inputs
import com.agency.photoduel.ui.state.ReportState
import com.agency.photoduel.ui.theme.PdSpace

@Composable
private fun SheetColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PdSpace.gutter)
            .padding(bottom = PdSpace.lg)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(PdSpace.md),
        content = content,
    )
}

@Composable
private fun SheetTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
}

/**
 * Report a photo or a person. A reason is required; details are optional and capped at 280 characters.
 * The reported person is never told who reported them.
 */
@Composable
fun ReportSheet(env: AppEnv, target: ReportTarget, title: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val state = remember(target) { ReportState(scope, env.deps.service, env.network, target) }
    val ui by state.ui.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        SheetColumn {
            if (ui.sent) {
                SheetTitle(stringResource(R.string.report_sent_title))
                Text(stringResource(R.string.report_sent_body), style = MaterialTheme.typography.bodyLarge)
                PdButton(text = stringResource(R.string.action_done), onClick = onDismiss)
            } else {
                ReportForm(state, title)
            }
        }
    }
}

@Composable
private fun ReportForm(state: ReportState, title: String) {
    val ui by state.ui.collectAsStateWithLifecycle()
    SheetTitle(title)
    Text(
        text = stringResource(R.string.report_note),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(Modifier.selectableGroup()) {
        ReportReason.entries.forEach { reason ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = PdSpace.touchTarget)
                    .selectable(
                        selected = ui.reason == reason,
                        enabled = !ui.submitting,
                        role = Role.RadioButton,
                        onClick = { state.selectReason(reason) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PdSpace.sm),
            ) {
                RadioButton(selected = ui.reason == reason, onClick = null)
                Text(reportReasonLabel(reason), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    OutlinedTextField(
        value = ui.details,
        onValueChange = state::onDetailsChanged,
        label = { Text(stringResource(R.string.report_details_label)) },
        supportingText = { Text(stringResource(R.string.report_details_count, ui.details.length, Inputs.REPORT_DETAILS_MAX)) },
        minLines = 2,
        maxLines = 5,
        enabled = !ui.submitting,
        modifier = Modifier.fillMaxWidth(),
    )
    ui.error?.let { InlineError(it) }
    PdButton(
        text = stringResource(R.string.report_send),
        onClick = state::send,
        enabled = ui.canSend,
        loading = ui.submitting,
    )
}

/**
 * Block one person. Blocking hides each other's photos and prevents votes between the two; it does not
 * remove anyone from a room and the other person is not told.
 */
@Composable
fun BlockSheet(env: AppEnv, userId: String, displayName: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val state = remember(userId) { BlockState(scope, env.deps.service, env.network, userId) }
    val ui by state.ui.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        SheetColumn {
            if (ui.done) {
                SheetTitle(stringResource(R.string.block_done, displayName))
                PdButton(text = stringResource(R.string.action_done), onClick = onDismiss)
            } else {
                SheetTitle(stringResource(R.string.block_title, displayName))
                Text(stringResource(R.string.block_body), style = MaterialTheme.typography.bodyLarge)
                ui.error?.let { InlineError(it) }
                PdButton(
                    text = stringResource(R.string.block_confirm),
                    onClick = state::confirm,
                    style = PdButtonStyle.DESTRUCTIVE,
                    loading = ui.submitting,
                )
                PdButton(text = stringResource(R.string.action_cancel), onClick = onDismiss, style = PdButtonStyle.TERTIARY)
            }
        }
    }
}
