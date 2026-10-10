package com.agency.stickerjet.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.agency.stickerjet.R
import com.agency.stickerjet.domain.Caption
import com.agency.stickerjet.domain.CaptionPosition
import com.agency.stickerjet.domain.StickerLimits
import com.agency.stickerjet.ui.components.StickerChoiceRow
import com.agency.stickerjet.ui.theme.Spacing
import com.agency.stickerjet.ui.theme.TouchTarget

/** One caption colour: its 0xAARRGGBB value and the name spoken for it. */
private class CaptionSwatch(val argb: Long, val nameRes: Int)

private val CaptionSwatches = listOf(
    CaptionSwatch(0xFFFFFFFFL, R.string.color_white),
    CaptionSwatch(0xFF1C1B2EL, R.string.color_ink),
    CaptionSwatch(0xFFFFC857L, R.string.color_sun),
    CaptionSwatch(0xFFFF6A3DL, R.string.color_jet),
    CaptionSwatch(0xFF2BB3A3L, R.string.color_sea),
    CaptionSwatch(0xFF8B7CF6L, R.string.color_lilac),
)

/**
 * Caption text, position and colour. The text is typed raw and committed on Done or when the field
 * loses focus, because the domain trims edge spaces on every commit and that would stop the user typing
 * spaces between words.
 */
@Composable
fun CaptionControls(
    caption: Caption,
    onCommitText: (String) -> Unit,
    onPosition: (CaptionPosition) -> Unit,
    onColor: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable(caption.text) { mutableStateOf(caption.text) }
    var hadFocus by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            text = stringResource(R.string.style_caption_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(StickerLimits.MAX_CAPTION_CHARS) },
            label = { Text(text = stringResource(R.string.style_caption_label)) },
            supportingText = {
                Text(text = stringResource(R.string.style_caption_counter, text.length, StickerLimits.MAX_CAPTION_CHARS))
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onCommitText(text) }),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focus ->
                if (focus.isFocused) {
                    hadFocus = true
                } else if (hadFocus) {
                    hadFocus = false
                    onCommitText(text)
                }
            },
        )
        Text(
            text = stringResource(R.string.style_caption_position),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        StickerChoiceRow(
            options = listOf(stringResource(R.string.style_position_top), stringResource(R.string.style_position_bottom)),
            selectedIndex = if (caption.position == CaptionPosition.TOP) 0 else 1,
            onSelect = { onPosition(if (it == 0) CaptionPosition.TOP else CaptionPosition.BOTTOM) },
        )
        Text(
            text = stringResource(R.string.style_caption_color),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            CaptionSwatches.forEach { swatch ->
                ColorSwatch(
                    argb = swatch.argb,
                    name = stringResource(swatch.nameRes),
                    selected = caption.argb == swatch.argb,
                    onSelect = { onColor(swatch.argb) },
                )
            }
        }
    }
}

/** A 48 dp radio-style swatch. The colour is decorative; the spoken name says which colour it is. */
@Composable
private fun ColorSwatch(argb: Long, name: String, selected: Boolean, onSelect: () -> Unit) {
    Box(
        modifier = Modifier
            .size(TouchTarget.min)
            .semantics { contentDescription = name }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(argb))
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = MaterialTheme.colorScheme.onBackground,
                    shape = CircleShape,
                ),
        )
    }
}
