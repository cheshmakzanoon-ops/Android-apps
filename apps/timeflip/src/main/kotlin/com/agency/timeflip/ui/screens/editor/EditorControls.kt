package com.agency.timeflip.ui.screens.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.agency.timeflip.R
import com.agency.timeflip.domain.model.Easing
import com.agency.timeflip.domain.model.OutputPreset
import com.agency.timeflip.domain.model.TransitionDefinition
import com.agency.timeflip.domain.model.TransitionKind
import com.agency.timeflip.domain.model.TransitionParameters
import com.agency.timeflip.ui.components.LabeledSlider
import com.agency.timeflip.ui.components.SelectableChip
import com.agency.timeflip.ui.theme.TimeFlipColor
import com.agency.timeflip.ui.theme.TimeFlipSize
import com.agency.timeflip.ui.theme.TimeFlipSpacing
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role as SemanticsRole

/**
 * Transition, timing and output controls. Each chip and switch is a real control with a visible
 * label and a selected state, so the choice never depends on colour alone.
 */
@Composable
fun TransitionControls(
    transition: TransitionDefinition,
    onKindSelected: (TransitionKind) -> Unit,
    onEasingSelected: (Easing) -> Unit,
    onReversedChange: (Boolean) -> Unit,
    onDurationPreview: (Int) -> Unit,
    onDurationCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val parameters: TransitionParameters = transition.parameters
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(TimeFlipSpacing.Space12),
    ) {
        Text(
            text = stringResource(R.string.editor_transition_heading),
            style = MaterialTheme.typography.titleMedium,
            color = TimeFlipColor.OnBackground,
        )
        KindChipRow(
            selected = transition.kind,
            onSelected = onKindSelected,
        )

        LabeledSlider(
            label = stringResource(R.string.editor_duration_label),
            valueText = stringResource(R.string.common_seconds, parameters.durationMs / 1000f),
            value = parameters.durationMs / 1000f,
            onValueChange = { seconds -> onDurationPreview((seconds * 1000f).toInt()) },
            valueRange = (TransitionParameters.MIN_DURATION_MS / 1000f)..(TransitionParameters.MAX_DURATION_MS / 1000f),
            onValueChangeFinished = onDurationCommit,
        )

        Text(
            text = stringResource(R.string.editor_easing_heading),
            style = MaterialTheme.typography.titleMedium,
            color = TimeFlipColor.OnBackground,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(TimeFlipSpacing.Space8)) {
            EasingChip(R.string.editor_easing_linear, Easing.LINEAR, parameters.easing, onEasingSelected, Modifier.weight(1f))
            EasingChip(R.string.editor_easing_in_out, Easing.EASE_IN_OUT_CUBIC, parameters.easing, onEasingSelected, Modifier.weight(1f))
            EasingChip(R.string.editor_easing_out, Easing.EASE_OUT_CUBIC, parameters.easing, onEasingSelected, Modifier.weight(1f))
        }

        ReverseSwitch(checked = parameters.reversed, onCheckedChange = onReversedChange)
    }
}

/** Output preset chooser. Each preset is a chip with its name, so the size is never implied by colour. */
@Composable
fun PresetChooser(
    selected: OutputPreset,
    onSelected: (OutputPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(TimeFlipSpacing.Space8),
    ) {
        Text(
            text = stringResource(R.string.editor_output_heading),
            style = MaterialTheme.typography.titleMedium,
            color = TimeFlipColor.OnBackground,
        )
        val presets = OutputPreset.entries
        presets.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(TimeFlipSpacing.Space8)) {
                pair.forEach { preset ->
                    SelectableChip(
                        label = stringResource(presetLabelRes(preset)),
                        selected = preset == selected,
                        onSelected = { onSelected(preset) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun KindChipRow(selected: TransitionKind, onSelected: (TransitionKind) -> Unit) {
    val kinds = listOf(
        TransitionKind.HORIZONTAL_REVEAL to R.string.transition_horizontal,
        TransitionKind.VERTICAL_REVEAL to R.string.transition_vertical,
        TransitionKind.CROSSFADE to R.string.transition_crossfade,
        TransitionKind.DIRECTIONAL_WIPE to R.string.transition_wipe,
    )
    kinds.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(TimeFlipSpacing.Space8)) {
            pair.forEach { (kind, labelRes) ->
                SelectableChip(
                    label = stringResource(labelRes),
                    selected = kind == selected,
                    onSelected = { onSelected(kind) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun EasingChip(
    labelRes: Int,
    easing: Easing,
    selected: Easing,
    onSelected: (Easing) -> Unit,
    modifier: Modifier,
) {
    SelectableChip(
        label = stringResource(labelRes),
        selected = easing == selected,
        onSelected = { onSelected(easing) },
        modifier = modifier,
    )
}

@Composable
private fun ReverseSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val label = stringResource(R.string.editor_reverse)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = TimeFlipSize.MinTouchTarget)
            .toggleable(
                value = checked,
                role = SemanticsRole.Switch,
                onValueChange = onCheckedChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = TimeFlipColor.OnBackground,
            modifier = Modifier.weight(1f),
        )
        // The row is the toggle; the switch is a visual state only.
        Switch(checked = checked, onCheckedChange = null)
    }
}

internal fun presetLabelRes(preset: OutputPreset): Int = when (preset) {
    OutputPreset.PORTRAIT_720 -> R.string.preset_portrait_720
    OutputPreset.PORTRAIT_1080 -> R.string.preset_portrait_1080
    OutputPreset.SQUARE_1080 -> R.string.preset_square_1080
    OutputPreset.LANDSCAPE_1080 -> R.string.preset_landscape_1080
}
