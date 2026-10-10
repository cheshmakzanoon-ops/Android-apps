package com.agency.stickerjet.engine

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.agency.stickerjet.domain.Caption
import com.agency.stickerjet.domain.CaptionPosition
import kotlin.math.max

/**
 * Draws a one-line caption as an outline with a contrasting stroke, so it reads over any background.
 * The text is sized from the canvas (9 percent of its side), shrunk until it fits the width inside a
 * 5 percent margin, and placed at the top or bottom with that margin as a safe area.
 *
 * The outline is black for light fills and white for dark fills, judged by relative luminance. An empty
 * caption draws nothing.
 */
class CaptionRenderer {

    /** Draws [caption] onto [canvas], which is [sizePx] pixels square. */
    fun draw(canvas: Canvas, caption: Caption, sizePx: Int) {
        val text = caption.text.trim()
        if (text.isEmpty() || sizePx <= 0) return
        val fill = caption.argb.toInt()
        val outline = if (luminance(fill) > 0.5f) Color.BLACK else Color.WHITE
        val margin = sizePx * MARGIN_FRACTION
        val maxWidth = sizePx - 2 * margin
        val minSize = sizePx * MIN_TEXT_FRACTION

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            color = fill
            textSize = sizePx * TEXT_FRACTION
        }
        while (fillPaint.measureText(text) > maxWidth && fillPaint.textSize > minSize) {
            fillPaint.textSize = max(minSize, fillPaint.textSize * SHRINK_STEP)
        }
        val strokePaint = Paint(fillPaint).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = fillPaint.textSize * OUTLINE_FRACTION
            color = outline
        }

        val metrics = fillPaint.fontMetrics
        val baseline = when (caption.position) {
            CaptionPosition.TOP -> margin - metrics.ascent
            CaptionPosition.BOTTOM -> sizePx - margin - metrics.descent
        }
        val x = sizePx / 2f
        canvas.drawText(text, x, baseline, strokePaint)
        canvas.drawText(text, x, baseline, fillPaint)
    }

    private fun luminance(argb: Int): Float =
        (LUMA_R * Color.red(argb) + LUMA_G * Color.green(argb) + LUMA_B * Color.blue(argb)) / 255f

    private companion object {
        const val MARGIN_FRACTION = 0.05f
        const val TEXT_FRACTION = 0.09f
        const val MIN_TEXT_FRACTION = 0.04f
        const val OUTLINE_FRACTION = 0.2f
        const val SHRINK_STEP = 0.92f
        const val LUMA_R = 0.2126f
        const val LUMA_G = 0.7152f
        const val LUMA_B = 0.0722f
    }
}
