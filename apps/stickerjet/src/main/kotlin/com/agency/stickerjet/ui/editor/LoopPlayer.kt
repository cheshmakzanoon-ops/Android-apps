package com.agency.stickerjet.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import com.agency.stickerjet.R
import com.agency.stickerjet.ui.components.StickerSecondaryButton
import kotlinx.coroutines.delay

/** Frame duration used if a duration is missing. Matches 12 fps. */
private const val FALLBACK_FRAME_MS: Long = 83L

/**
 * Plays [images] as a loop, holding each frame for its entry in [durationsMs]. When [playing] is false
 * the current frame stays on screen, which is how pause and reduced motion work.
 */
@Composable
fun LoopPlayer(
    images: List<ImageBitmap>,
    durationsMs: List<Int>,
    playing: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    var index by remember(images) { mutableIntStateOf(0) }
    LaunchedEffect(images, durationsMs, playing) {
        if (!playing || images.size < 2) return@LaunchedEffect
        while (true) {
            delay(durationsMs.getOrNull(index)?.toLong() ?: FALLBACK_FRAME_MS)
            index = (index + 1) % images.size
        }
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val frame = images.getOrNull(index) ?: images.firstOrNull()
        if (frame != null) {
            Image(
                bitmap = frame,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Pause or play for a looping preview. Labels are text, so the state is clear without colour. */
@Composable
fun PlayPauseButton(playing: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    StickerSecondaryButton(
        text = stringResource(if (playing) R.string.loop_pause else R.string.loop_play),
        onClick = onToggle,
        modifier = modifier,
    )
}
