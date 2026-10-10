package com.agency.stickerjet.engine

import com.agency.stickerjet.domain.AlphaMask
import com.agency.stickerjet.domain.MaskStroke
import kotlin.math.roundToInt

/**
 * Applies manual mask corrections on top of a segmentation mask. Pure math, no Android types.
 *
 * - [base] is the segmentation result, or null for a fully opaque frame.
 * - Strokes apply in list order. RESTORE sets alpha to 255 inside the stroke, ERASE sets it to 0, so the
 *   last stroke that touches a pixel decides it. Dropping the last stroke is an exact undo.
 * - A stroke is a polyline of normalized points drawn as a capsule. Its radius is normalized to the
 *   shorter side of the frame: `pixels = radius * min(width, height)`, floored at 2 px so that a tap
 *   survives smoothing. Points are clamped to 0..1 and pixels outside the frame are skipped.
 * - After at least one stroke, the edge is smoothed (see [MaskSmoothing]): a 1-pixel separable blur, then a
 *   smoothstep soft threshold. Faint halo values below 0.35 drop to zero, and the 0.5 edge stays where the
 *   stroke put it, so the cut-out does not grow or shrink. The blur is deliberately one pixel: a larger
 *   blur erases strokes that are only a few pixels wide.
 *
 * Empty strokes are idempotent: the result is a copy of [base] (or all 255 when [base] is null), with no
 * smoothing applied. An unedited segmentation mask therefore passes through unchanged.
 */
object MaskRefiner {

    /** Returns a new mask of [width] x [height]. [base], when given, must have the same dimensions. */
    fun refine(base: AlphaMask?, width: Int, height: Int, strokes: List<MaskStroke>): AlphaMask {
        require(width > 0 && height > 0) { "Mask dimensions must be positive" }
        if (base != null) {
            require(base.width == width && base.height == height) {
                "Base mask is ${base.width}x${base.height}, expected ${width}x$height"
            }
        }
        val count = width * height
        if (strokes.isEmpty()) {
            return if (base == null) {
                AlphaMask(width, height, ByteArray(count) { OPAQUE })
            } else {
                AlphaMask(width, height, base.alpha.copyOf())
            }
        }

        val alpha = FloatArray(count) { i ->
            if (base == null) 1f else (base.alpha[i].toInt() and 0xFF) / 255f
        }
        val shorterSide = minOf(width, height).toFloat()
        for (stroke in strokes) {
            MaskStrokePainter.paint(stroke, width, height, shorterSide, alpha)
        }
        MaskSmoothing.smooth(alpha, width, height, BLUR_RADIUS_PX)

        val out = ByteArray(count) { i -> (alpha[i] * 255f).roundToInt().coerceIn(0, 255).toByte() }
        return AlphaMask(width, height, out)
    }

    /** Blur radius in pixels, independent of frame size (see the class comment). */
    internal const val BLUR_RADIUS_PX: Int = 1

    private const val OPAQUE: Byte = -1 // 0xFF as a signed byte
}
