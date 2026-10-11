package com.agency.stickerjet.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.agency.stickerjet.domain.NormalizedPoint
import com.agency.stickerjet.domain.StrokeMode
import com.agency.stickerjet.ui.theme.StickerTheme

/**
 * Transparent drag surface for the manual brush. The points of the drag in progress are drawn as
 * translucent dots (Sea for restore, Jet for erase). When the finger lifts, one stroke is reported with
 * its points normalized to the surface. A disabled surface ignores gestures.
 */
@Composable
fun BrushSurface(
    mode: StrokeMode,
    brush: BrushSize,
    enabled: Boolean,
    onStroke: (StrokeMode, List<NormalizedPoint>, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var surface by remember { mutableStateOf(IntSize.Zero) }
    val points = remember { mutableStateListOf<NormalizedPoint>() }
    val latestMode by rememberUpdatedState(mode)
    val latestBrush by rememberUpdatedState(brush)
    val latestStroke by rememberUpdatedState(onStroke)
    val tint = StickerTheme.extra
    Canvas(
        modifier = modifier
            .onSizeChanged { surface = it }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset ->
                        points.clear()
                        points.add(normalise(offset, surface))
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        points.add(normalise(change.position, surface))
                    },
                    onDragEnd = {
                        latestStroke(latestMode, points.toList(), latestBrush.radius)
                        points.clear()
                    },
                    onDragCancel = { points.clear() },
                )
            },
    ) {
        val radiusPx = brush.radius * minOf(size.width, size.height)
        val color = if (mode == StrokeMode.RESTORE) tint.keptTint else tint.removedTint
        points.forEach { p ->
            drawCircle(color = color, radius = radiusPx, center = Offset(p.x * size.width, p.y * size.height), alpha = DOT_ALPHA)
        }
    }
}

private const val DOT_ALPHA: Float = 0.35f

/** Converts a pixel offset on the surface to normalized coordinates, clamped to the surface. */
private fun normalise(offset: Offset, surface: IntSize): NormalizedPoint {
    val width = surface.width.coerceAtLeast(1).toFloat()
    val height = surface.height.coerceAtLeast(1).toFloat()
    return NormalizedPoint(
        x = (offset.x / width).coerceIn(0f, 1f),
        y = (offset.y / height).coerceIn(0f, 1f),
    )
}
