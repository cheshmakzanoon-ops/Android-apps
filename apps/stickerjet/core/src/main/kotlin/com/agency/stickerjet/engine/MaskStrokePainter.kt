package com.agency.stickerjet.engine

import com.agency.stickerjet.domain.MaskStroke
import com.agency.stickerjet.domain.StrokeMode
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** Paints one [MaskStroke] into a float alpha buffer (0..1) as a union of capsules, one per segment. */
internal object MaskStrokePainter {

    /**
     * A tap is at least 2 px in radius. Smaller discs vanish under the 1-pixel edge blur in [MaskSmoothing],
     * so a single point would paint nothing.
     */
    private const val MIN_RADIUS_PX = 2f

    fun paint(stroke: MaskStroke, width: Int, height: Int, shorterSide: Float, alpha: FloatArray) {
        if (stroke.points.isEmpty()) return
        val value = if (stroke.mode == StrokeMode.RESTORE) 1f else 0f
        val radius = max(stroke.radius.coerceAtLeast(0f) * shorterSide, MIN_RADIUS_PX)
        val xs = FloatArray(stroke.points.size) { i -> stroke.points[i].x.coerceIn(0f, 1f) * width }
        val ys = FloatArray(stroke.points.size) { i -> stroke.points[i].y.coerceIn(0f, 1f) * height }
        if (xs.size == 1) {
            capsule(xs[0], ys[0], xs[0], ys[0], radius, width, height, value, alpha)
            return
        }
        for (i in 0 until xs.size - 1) {
            capsule(xs[i], ys[i], xs[i + 1], ys[i + 1], radius, width, height, value, alpha)
        }
    }

    /** Sets [value] on every pixel whose centre lies within [radius] of the segment a-b. */
    private fun capsule(
        ax: Float, ay: Float, bx: Float, by: Float,
        radius: Float, width: Int, height: Int, value: Float, alpha: FloatArray,
    ) {
        val x0 = floor(minOf(ax, bx) - radius).toInt().coerceIn(0, width - 1)
        val x1 = ceil(maxOf(ax, bx) + radius).toInt().coerceIn(0, width - 1)
        val y0 = floor(minOf(ay, by) - radius).toInt().coerceIn(0, height - 1)
        val y1 = ceil(maxOf(ay, by) + radius).toInt().coerceIn(0, height - 1)
        val r2 = radius * radius
        for (y in y0..y1) {
            val cy = y + 0.5f
            val row = y * width
            for (x in x0..x1) {
                if (distanceSquared(x + 0.5f, cy, ax, ay, bx, by) <= r2) {
                    alpha[row + x] = value
                }
            }
        }
    }

    private fun distanceSquared(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val vx = bx - ax
        val vy = by - ay
        val lengthSquared = vx * vx + vy * vy
        val t = if (lengthSquared == 0f) 0f else (((px - ax) * vx + (py - ay) * vy) / lengthSquared).coerceIn(0f, 1f)
        val dx = ax + t * vx - px
        val dy = ay + t * vy - py
        return dx * dx + dy * dy
    }
}
