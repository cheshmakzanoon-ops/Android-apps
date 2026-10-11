package com.agency.stickerjet.engine

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.agency.stickerjet.domain.Caption
import com.agency.stickerjet.domain.CaptionPosition
import kotlin.math.max

/**
 * Draws a one-line caption that stays readable on light and dark backgrounds, in three layers:
 * 1. a translucent rounded plate, dark behind light text and light behind dark text;
 * 2. an outline in the contrasting colour;
 * 3. the caption fill.
 *
 * Sizes and positions come from [CaptionGeometry] (pure, unit-tested in core): text is 9 percent of the
 * canvas side, shrunk to fit inside the 5 percent margins, and placed at the top or bottom with the plate kept
 * inside the canvas. An empty caption draws nothing.
 *
 * Device behaviour (contrast on real backgrounds, plate alpha) is unverified until run on a device.
 */
class CaptionRenderer {

    /** Draws [caption] onto [canvas], which is [sizePx] pixels square. */
    fun draw(canvas: Canvas, caption: Caption, sizePx: Int) {
        val text = caption.text.trim()
        if (text.isEmpty() || sizePx <= 0) return
        val fill = caption.argb.toInt()
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            color = fill
            textSize = sizePx * TEXT_FRACTION
        }
        val minSize = sizePx * MIN_TEXT_FRACTION
        val maxWidth = CaptionGeometry.maxTextWidth(sizePx)
        while (fillPaint.measureText(text) > maxWidth && fillPaint.textSize > minSize) {
            fillPaint.textSize = max(minSize, fillPaint.textSize * SHRINK_STEP)
        }

        val metrics = fillPaint.fontMetrics
        val top = caption.position == CaptionPosition.TOP
        val plate = CaptionGeometry.plate(sizePx, fillPaint.measureText(text), metrics.ascent, metrics.descent, top)
        val radius = CaptionGeometry.margin(sizePx) * PLATE_RADIUS_FRACTION
        val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = CaptionGeometry.plateArgb(fill)
        }
        canvas.drawRoundRect(RectF(plate.left, plate.top, plate.right, plate.bottom), radius, radius, platePaint)

        val strokePaint = Paint(fillPaint).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = fillPaint.textSize * OUTLINE_FRACTION
            color = CaptionGeometry.outlineArgb(fill)
        }
        val baseline = CaptionGeometry.baseline(sizePx, metrics.ascent, metrics.descent, top)
        val x = sizePx / 2f
        canvas.drawText(text, x, baseline, strokePaint)
        canvas.drawText(text, x, baseline, fillPaint)
    }

    private companion object {
        const val TEXT_FRACTION = 0.09f
        const val MIN_TEXT_FRACTION = 0.04f
        const val SHRINK_STEP = 0.92f
        const val OUTLINE_FRACTION = 0.2f
        const val PLATE_RADIUS_FRACTION = 0.25f
    }
}
