package com.agency.photoduel.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.agency.photoduel.R
import com.agency.photoduel.ui.theme.LocalPdPalette
import com.agency.photoduel.ui.theme.PdSpace
import com.agency.photoduel.ui.theme.PdTokens

/** Keeps very tall or very wide photos from taking over a list. The whole photo is still shown (letterboxed). */
fun photoFrameRatio(width: Int, height: Int): Float {
    if (width <= 0 || height <= 0) return 4f / 3f
    return (width.toFloat() / height.toFloat()).coerceIn(0.6f, 1.8f)
}

/**
 * A photo shown in full: the image is fitted inside its frame and never cropped. Nothing is ever drawn
 * on top of the photo; selection and labels live in the caption row below it. While loading, or when the
 * image cannot be loaded, a quiet text sits in the empty frame.
 */
@Composable
fun PhotoFrame(model: Any?, frameRatio: Float, modifier: Modifier = Modifier) {
    var status by remember(model) { mutableStateOf(PhotoLoad.LOADING) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(frameRatio)
            .background(LocalPdPalette.current.bgSunken),
        contentAlignment = Alignment.Center,
    ) {
        if (status != PhotoLoad.SHOWN) {
            Text(
                text = stringResource(if (status == PhotoLoad.FAILED) R.string.photo_unavailable else R.string.photo_loading),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AsyncImage(
            model = model,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            onState = { state ->
                status = when (state) {
                    is AsyncImagePainter.State.Success -> PhotoLoad.SHOWN
                    is AsyncImagePainter.State.Error -> PhotoLoad.FAILED
                    else -> PhotoLoad.LOADING
                }
            },
        )
    }
}

private enum class PhotoLoad { LOADING, SHOWN, FAILED }

/**
 * A photo with a caption row. [selected] draws an accent outline around the whole card and a label in
 * the caption, outside the image.
 *
 * Interaction: with [onSelect] the card is a radio-style choice for TalkBack (voting); with [onClick] it
 * is a plain button; with neither it is static. [description] is what TalkBack reads for the card.
 */
@Composable
fun PhotoCard(
    model: Any?,
    frameRatio: Float,
    description: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onSelect: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    caption: @Composable RowScope.() -> Unit = {},
) {
    val palette = LocalPdPalette.current
    val outline = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, palette.border)
    val interaction = when {
        onSelect != null -> Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
        onClick != null -> Modifier.clickable(enabled = enabled, onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
        else -> Modifier
    }
    val shape = MaterialTheme.shapes.large
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(outline, shape)
            .background(MaterialTheme.colorScheme.surface)
            .then(interaction)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        PhotoFrame(model = model, frameRatio = frameRatio)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PdSpace.md, vertical = PdSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PdSpace.xs),
            content = caption,
        )
    }
}

/** Full-screen view of one photo on a dark backdrop. The photo is fitted, never cropped. */
@Composable
fun PhotoViewerDialog(model: Any?, description: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Always a dark backdrop, in both themes, so the photo reads the same way.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PdTokens.Dark.bgCanvas),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onDismiss, modifier = Modifier.padding(PdSpace.xs)) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.photo_viewer_close), tint = PdTokens.Dark.textPrimary)
                }
            }
            AsyncImage(
                model = model,
                contentDescription = description,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = PdSpace.lg),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
