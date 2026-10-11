package com.agency.timeflip.ui.screens.manualalign

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.agency.timeflip.domain.geometry.AffineMatrix
import com.agency.timeflip.domain.geometry.PhotoPlacement
import com.agency.timeflip.domain.model.AlignmentTransform
import com.agency.timeflip.domain.model.PixelSize
import com.agency.timeflip.ui.theme.TimeFlipColor
import kotlin.math.PI
import kotlin.math.atan2

/**
 * Draws the BEFORE photo, then the AFTER photo placed by [transform] and clipped to the left of
 * [dividerFraction]. The placement is PhotoPlacement, the same function the engine uses, so what is
 * shown here is where the exporter puts the photo. The output frame and an inset safe area are drawn
 * on top. Touches drive [onGestureDelta] and [onGestureEnd].
 */
@Composable
fun AlignmentCanvas(
    before: Bitmap?,
    after: Bitmap?,
    frame: PixelSize,
    transform: AlignmentTransform,
    overlayOpacity: Float,
    dividerFraction: Float,
    onGestureDelta: (zoom: Double, rotationDeltaDegrees: Double, panFractionX: Double, panFractionY: Double) -> Unit,
    onGestureEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    val currentDelta by rememberUpdatedState(onGestureDelta)
    val currentEnd by rememberUpdatedState(onGestureEnd)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectAlignmentGestures(
                    onDelta = { zoom, rotation, panX, panY -> currentDelta(zoom, rotation, panX, panY) },
                    onEnd = { currentEnd() },
                )
            },
    ) {
        val toCanvas = AffineMatrix.scale(
            (size.width / frame.width).toDouble(),
            (size.height / frame.height).toDouble(),
        )
        val beforeMatrix = PhotoPlacement.coverBase(
            source = PixelSize(before?.width ?: 1, before?.height ?: 1),
            frame = frame,
        ).then(toCanvas)
        val afterMatrix = after?.let {
            PhotoPlacement.placement(PixelSize(it.width, it.height), frame, transform).then(toCanvas)
        }

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            if (before != null) {
                paint.alpha = OPAQUE
                native.drawBitmap(before, beforeMatrix.toAndroidMatrix(), paint)
            }
            if (after != null && afterMatrix != null) {
                native.save()
                native.clipRect(0f, 0f, size.width * dividerFraction, size.height)
                paint.alpha = (overlayOpacity.coerceIn(0f, 1f) * OPAQUE).toInt()
                native.drawBitmap(after, afterMatrix.toAndroidMatrix(), paint)
                native.restore()
            }
        }

        drawRect(
            color = TimeFlipColor.OnBackground,
            style = Stroke(width = 1.dp.toPx()),
        )
        val inset = size.width * SAFE_AREA_INSET
        val insetY = size.height * SAFE_AREA_INSET
        drawRect(
            color = TimeFlipColor.OnBackground.copy(alpha = 0.5f),
            topLeft = Offset(inset, insetY),
            size = Size(size.width - 2 * inset, size.height - 2 * insetY),
            style = Stroke(
                width = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())),
            ),
        )
    }
}

private const val OPAQUE = 255
private const val SAFE_AREA_INSET = 0.05f

/** Maps a domain affine matrix onto android.graphics.Matrix, as AndroidMatrix does for the renderer. */
private fun AffineMatrix.toAndroidMatrix(): Matrix = Matrix().apply {
    setValues(floatArrayOf(a.toFloat(), c.toFloat(), e.toFloat(), b.toFloat(), d.toFloat(), f.toFloat(), 0f, 0f, 1f))
}

/**
 * Turns touches into transform deltas. One finger pans. Two fingers pinch to zoom and twist to
 * rotate. A change in the number of fingers starts a new baseline, so lifting one finger never
 * makes the photo jump. [onEnd] runs once, when the last finger lifts.
 */
internal suspend fun PointerInputScope.detectAlignmentGestures(
    onDelta: (zoom: Double, rotationDeltaDegrees: Double, panFractionX: Double, panFractionY: Double) -> Unit,
    onEnd: () -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var previousCentroid: Offset? = null
        var previousSpan = 0f
        var previousAngle = 0f
        var previousCount = 0
        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isNotEmpty()) {
                val centroid = pressed.fold(Offset.Zero) { acc, change -> acc + change.position } / pressed.size.toFloat()
                val span = pressed.map { (it.position - centroid).getDistance() }.average().toFloat()
                val angle = if (pressed.size >= 2) angleDegrees(pressed[0].position, pressed[1].position) else 0f

                if (previousCount == pressed.size && previousCentroid != null) {
                    val pan = centroid - previousCentroid
                    val zoom = if (pressed.size >= 2 && previousSpan > 0f) span / previousSpan else 1f
                    val rotation = if (pressed.size >= 2) normalizeDegrees(angle - previousAngle) else 0f
                    if (pan != Offset.Zero || zoom != 1f || rotation != 0f) {
                        onDelta(
                            zoom.toDouble(),
                            rotation.toDouble(),
                            (pan.x / size.width).toDouble(),
                            (pan.y / size.height).toDouble(),
                        )
                    }
                }
                previousCentroid = centroid
                previousSpan = span
                previousAngle = angle
                previousCount = pressed.size
                event.changes.forEach { change ->
                    if (change.position != change.previousPosition) change.consume()
                }
            }
        } while (event.changes.any { it.pressed })
        onEnd()
    }
}

private fun angleDegrees(from: Offset, to: Offset): Float =
    (atan2(to.y - from.y, to.x - from.x) * 180.0 / PI).toFloat()

private fun normalizeDegrees(value: Float): Float {
    var result = value
    while (result > 180f) result -= 360f
    while (result < -180f) result += 360f
    return result
}
