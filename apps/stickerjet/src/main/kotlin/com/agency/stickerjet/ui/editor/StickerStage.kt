package com.agency.stickerjet.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import com.agency.stickerjet.domain.Caption
import com.agency.stickerjet.domain.CaptionPosition
import com.agency.stickerjet.domain.Transform
import com.agency.stickerjet.ui.theme.Spacing
import com.agency.stickerjet.ui.theme.StickerPalette
import com.agency.stickerjet.ui.theme.StickerTileShape

/**
 * The square preview the Style tab edits. It shows the still frame with the placement applied and the
 * caption over it. This is an interactive approximation: the rendered animation in Preview is the truth.
 * [onSizeChanged] feeds the stage's size, so offsets stay normalized to the canvas.
 */
@Composable
fun StickerStage(
    still: ImageBitmap?,
    transform: Transform,
    caption: Caption,
    darkBackground: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
    gestures: Modifier = Modifier,
    onStageSize: (widthPx: Float, heightPx: Float) -> Unit = { _, _ -> },
) {
    var widthPx by remember { mutableFloatStateOf(1f) }
    var heightPx by remember { mutableFloatStateOf(1f) }
    val background = if (darkBackground) StickerPalette.Ink else StickerPalette.Paper
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(StickerTileShape)
            .background(background)
            .onSizeChanged {
                widthPx = it.width.toFloat()
                heightPx = it.height.toFloat()
                onStageSize(widthPx, heightPx)
            }
            .then(gestures),
        contentAlignment = Alignment.Center,
    ) {
        if (still != null) {
            Image(
                bitmap = still,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = transform.scale
                        scaleY = transform.scale
                        rotationZ = transform.quarterTurns * QUARTER_TURN_DEGREES
                        translationX = transform.offsetX * widthPx
                        translationY = transform.offsetY * heightPx
                    },
            )
        }
        if (caption.text.isNotEmpty()) {
            Text(
                text = caption.text,
                color = Color(caption.argb),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(if (caption.position == CaptionPosition.TOP) Alignment.TopCenter else Alignment.BottomCenter)
                    .padding(Spacing.md),
            )
        }
    }
}

private const val QUARTER_TURN_DEGREES: Float = 90f
