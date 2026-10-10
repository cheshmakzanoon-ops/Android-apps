package com.agency.stickerjet.ui.importing

import android.content.ActivityNotFoundException
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agency.stickerjet.platform.rememberVideoPicker

/**
 * Test seam for the picker. When a test provides one, the route uses it instead of the system Photo
 * Picker, so instrumented tests can run the import path without opening system UI.
 */
fun interface ClipPicker {
    /** Calls [onPicked] with the picked content URI string, or null when the user cancels. */
    fun pick(onPicked: (String?) -> Unit)
}

/** Null in the app. Set by tests only. */
val LocalClipPicker = compositionLocalOf<ClipPicker?> { null }

/**
 * Connects [ImportScreen] to the system Photo Picker through [rememberVideoPicker]. When the picker is
 * unavailable, the screen shows the unsupported state instead of failing. A finished import calls
 * [onImported] once, with the new project id.
 */
@Composable
fun ImportRoute(
    viewModel: ImportViewModel,
    onBack: () -> Unit,
    onImported: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val override = LocalClipPicker.current
    val systemPicker = rememberVideoPicker { uri -> viewModel.onPicked(uri?.toString()) }
    val choose: () -> Unit = {
        if (override != null) {
            override.pick { viewModel.onPicked(it) }
        } else {
            try {
                systemPicker()
            } catch (e: ActivityNotFoundException) {
                viewModel.onUnsupported()
            }
        }
    }
    LaunchedEffect(state.phase) {
        val phase = state.phase
        if (phase is ImportPhase.Imported) onImported(phase.projectId)
    }
    ImportScreen(
        state = state,
        onChoose = choose,
        onRetry = viewModel::retry,
        onCancel = viewModel::cancel,
        onBack = onBack,
        modifier = modifier,
    )
}
