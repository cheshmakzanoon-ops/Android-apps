package com.agency.timeflip.domain.transition

import com.agency.timeflip.domain.model.Easing
import com.agency.timeflip.domain.model.PixelSize
import com.agency.timeflip.domain.model.TransitionDefinition
import com.agency.timeflip.domain.model.TransitionKind
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Region of the frame that shows the AFTER photograph. Pixels outside it show BEFORE. */
sealed interface ClipShape {
    data object Full : ClipShape

    data object Empty : ClipShape

    /** Axis-aligned rectangle in frame pixels. */
    data class Rect(val left: Double, val top: Double, val right: Double, val bottom: Double) : ClipShape

    /** Points q with normalX·qx + normalY·qy ≤ offset are inside. Normal is unit length. */
    data class HalfPlane(val normalX: Double, val normalY: Double, val offset: Double) : ClipShape
}

/** What a renderer draws for one frame: the AFTER photograph at [afterAlpha], clipped to [afterClip]. */
data class TransitionFrame(val afterAlpha: Double, val afterClip: ClipShape)

object Easings {
    fun apply(easing: Easing, t: Double): Double {
        val x = t.coerceIn(0.0, 1.0)
        return when (easing) {
            Easing.LINEAR -> x
            Easing.EASE_IN_OUT_CUBIC -> if (x < 0.5) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2
            Easing.EASE_OUT_CUBIC -> 1 - (1 - x).pow(3)
        }
    }
}

/**
 * Pure transition geometry shared by preview and export. Given eased progress p in 0..1, returns
 * the AFTER photograph's opacity and clip. Preview and export must call this same function so that
 * the two always agree.
 */
object TransitionMath {

    fun frameAt(definition: TransitionDefinition, linearProgress: Double, frame: PixelSize): TransitionFrame {
        val progress = Easings.apply(definition.parameters.easing, linearProgress)
        val reversed = definition.parameters.reversed
        val width = frame.width.toDouble()
        val height = frame.height.toDouble()

        if (progress <= 0.0) return TransitionFrame(afterAlpha = 0.0, afterClip = ClipShape.Empty)
        if (progress >= 1.0) return TransitionFrame(afterAlpha = 1.0, afterClip = ClipShape.Full)

        return when (definition.kind) {
            TransitionKind.HORIZONTAL_REVEAL -> {
                val revealed = width * progress
                val clip = if (reversed) {
                    ClipShape.Rect(width - revealed, 0.0, width, height)
                } else {
                    ClipShape.Rect(0.0, 0.0, revealed, height)
                }
                TransitionFrame(afterAlpha = 1.0, afterClip = clip)
            }

            TransitionKind.VERTICAL_REVEAL -> {
                val revealed = height * progress
                val clip = if (reversed) {
                    ClipShape.Rect(0.0, height - revealed, width, height)
                } else {
                    ClipShape.Rect(0.0, 0.0, width, revealed)
                }
                TransitionFrame(afterAlpha = 1.0, afterClip = clip)
            }

            TransitionKind.CROSSFADE -> TransitionFrame(afterAlpha = progress, afterClip = ClipShape.Full)

            TransitionKind.DIRECTIONAL_WIPE -> {
                val angle = Math.toRadians(definition.parameters.wipeAngleDegrees + if (reversed) 180.0 else 0.0)
                val nx = cos(angle)
                val ny = sin(angle)
                val corners = listOf(0.0 to 0.0, width to 0.0, 0.0 to height, width to height)
                val projections = corners.map { (x, y) -> nx * x + ny * y }
                val low = projections.min()
                val high = projections.max()
                val edge = low + progress * (high - low)
                if (abs(high - low) < 1e-9) {
                    TransitionFrame(afterAlpha = 1.0, afterClip = ClipShape.Full)
                } else {
                    TransitionFrame(afterAlpha = 1.0, afterClip = ClipShape.HalfPlane(nx, ny, edge))
                }
            }
        }
    }
}
