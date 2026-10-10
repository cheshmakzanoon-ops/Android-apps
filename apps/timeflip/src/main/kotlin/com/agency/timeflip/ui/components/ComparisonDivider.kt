package com.agency.timeflip.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.agency.timeflip.ui.theme.TimeFlipColor
import com.agency.timeflip.ui.theme.TimeFlipSize

/**
 * Vertical divider over a before/after comparison. Dragging the grab area moves the boundary, which
 * reveals the photo on one side and hides it on the other. The grab area is a sibling drawn on top
 * of the photo canvas, so it takes its own touches and never conflicts with the photo gestures.
 * The caller gives this composable the full size of the comparison area.
 *
 * @param fraction boundary position, from 0 (left edge) to 1 (right edge).
 * @param label accessibility name. The position is announced as a percentage.
 */
@Composable
fun ComparisonDivider(
    fraction: Float,
    onFractionChange: (Float) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    onDragFinished: () -> Unit = {},
) {
    var widthPx by remember { mutableStateOf(0) }
    val currentFraction by rememberUpdatedState(fraction)
    val currentOnChange by rememberUpdatedState(onFractionChange)
    val currentFinished by rememberUpdatedState(onDragFinished)
    val percent = (fraction * 100f).toInt()
    val grabWidth = TimeFlipSize.DividerGrab
    val density = LocalDensity.current

    Box(modifier = modifier.onSizeChanged { widthPx = it.width }) {
        val boundaryPx = fraction * widthPx
        val grabHalfPx = with(density) { grabWidth.toPx() } / 2f
        val offsetX = with(density) { (boundaryPx - grabHalfPx).toDp() }

        Box(
            modifier = Modifier
                .offset(x = offsetX)
                .width(grabWidth)
                .fillMaxHeight()
                .semantics {
                    contentDescription = label
                    stateDescription = "$percent%"
                    role = Role.Slider
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = { currentFinished() },
                        onDragCancel = { currentFinished() },
                    ) { change, dragAmount ->
                        change.consume()
                        if (widthPx > 0) {
                            currentOnChange((currentFraction + dragAmount / widthPx).coerceIn(0f, 1f))
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxHeight().width(grabWidth)) {
                val x = size.width / 2f
                drawLine(
                    color = TimeFlipColor.OnBackground,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 2.dp.toPx(),
                )
                drawCircle(
                    color = TimeFlipColor.OnBackground,
                    radius = 6.dp.toPx(),
                    center = Offset(x, size.height / 2f),
                )
            }
        }
    }
}
