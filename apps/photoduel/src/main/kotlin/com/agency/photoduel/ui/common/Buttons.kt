package com.agency.photoduel.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.agency.photoduel.R
import com.agency.photoduel.ui.theme.PdSpace

/** How a [PdButton] looks. One primary action per screen; destructive actions are always explicit. */
enum class PdButtonStyle { PRIMARY, SECONDARY, TERTIARY, DESTRUCTIVE }

/**
 * The app's button. States: enabled, disabled, and [loading] (shows a spinner, ignores taps and tells
 * TalkBack it is working). Full width by default, at least 48 dp tall.
 */
@Composable
fun PdButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    style: PdButtonStyle = PdButtonStyle.PRIMARY,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val working = stringResource(R.string.state_working)
    val semanticsModifier = if (loading) Modifier.semantics { stateDescription = working } else Modifier
    val finalModifier = modifier.defaultMinSize(minHeight = PdSpace.touchTarget).then(semanticsModifier)
    val active = enabled && !loading
    val content: @Composable () -> Unit = { ButtonContent(text, loading) }
    when (style) {
        PdButtonStyle.PRIMARY -> Button(onClick = onClick, modifier = finalModifier, enabled = active) { content() }
        PdButtonStyle.DESTRUCTIVE -> Button(
            onClick = onClick,
            modifier = finalModifier,
            enabled = active,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) { content() }
        PdButtonStyle.SECONDARY -> OutlinedButton(onClick = onClick, modifier = finalModifier, enabled = active) { content() }
        PdButtonStyle.TERTIARY -> TextButton(onClick = onClick, modifier = finalModifier, enabled = active) { content() }
    }
}

@Composable
private fun ButtonContent(text: String, loading: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(PdSpace.xs), verticalAlignment = Alignment.CenterVertically) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
        }
        Text(text = text, textAlign = TextAlign.Center)
    }
}
