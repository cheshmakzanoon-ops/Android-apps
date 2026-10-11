package com.agency.stickerjet.engine

/** A rectangle in canvas pixels, used for the caption plate. */
data class CaptionPlate(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Caption layout and colour rules as pure math, so the Android renderer and the unit tests share one
 * definition. All lengths are canvas pixels for a square canvas of [sizePx].
 *
 * - The margin is 5 percent of the side. Text stays inside it, and the plate pads the text by half a margin,
 *   so the plate stays inside the canvas at the top or bottom.
 * - [plate] centres a translucent plate on the line, which keeps the text readable on light and dark
 *   backgrounds.
 * - [plateArgb] and [outlineArgb] take the opposite of the fill's luminance: dark plates and outlines behind
 *   light text, light plates and outlines behind dark text.
 */
object CaptionGeometry {
    const val MARGIN_FRACTION: Float = 0.05f
    private const val PAD_FRACTION_OF_MARGIN: Float = 0.5f
    private const val PLATE_ALPHA: Int = 0x8C
    private val PLATE_DARK: Int = (PLATE_ALPHA shl 24)
    private val PLATE_LIGHT: Int = (PLATE_ALPHA shl 24) or 0xFFFFFF
    private val OPAQUE_BLACK: Int = 0xFF000000.toInt()
    private val OPAQUE_WHITE: Int = 0xFFFFFFFF.toInt()

    fun margin(sizePx: Int): Float = sizePx * MARGIN_FRACTION

    /** Width available for one line of text between the two margins. */
    fun maxTextWidth(sizePx: Int): Float = sizePx - 2 * margin(sizePx)

    /**
     * Baseline of one line. [ascent] is negative and [descent] positive, as in Android font metrics. At the top
     * the ascent line sits on the top margin. At the bottom the descent line sits on the bottom margin.
     */
    fun baseline(sizePx: Int, ascent: Float, descent: Float, top: Boolean): Float =
        if (top) margin(sizePx) - ascent else sizePx - margin(sizePx) - descent

    /** Plate behind one line of [textWidth] pixels, centred horizontally and clamped to the canvas. */
    fun plate(sizePx: Int, textWidth: Float, ascent: Float, descent: Float, top: Boolean): CaptionPlate {
        val pad = margin(sizePx) * PAD_FRACTION_OF_MARGIN
        val line = baseline(sizePx, ascent, descent, top)
        val half = textWidth / 2f + pad
        val centre = sizePx / 2f
        val side = sizePx.toFloat()
        return CaptionPlate(
            left = (centre - half).coerceIn(0f, side),
            top = (line + ascent - pad).coerceIn(0f, side),
            right = (centre + half).coerceIn(0f, side),
            bottom = (line + descent + pad).coerceIn(0f, side),
        )
    }

    /** Relative luminance from 0 to 1 of an 0xAARRGGBB colour, using Rec. 709 weights. Alpha is ignored. */
    fun luminance(argb: Int): Float {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
    }

    /** True when the fill is light. The plate and outline are then dark. */
    fun isLightFill(argb: Int): Boolean = luminance(argb) > 0.5f

    /** Translucent plate colour: black over light fills, white over dark fills. */
    fun plateArgb(fill: Int): Int = if (isLightFill(fill)) PLATE_DARK else PLATE_LIGHT

    /** Outline colour, opposite to the fill: black for light fills, white for dark fills. */
    fun outlineArgb(fill: Int): Int = if (isLightFill(fill)) OPAQUE_BLACK else OPAQUE_WHITE
}
