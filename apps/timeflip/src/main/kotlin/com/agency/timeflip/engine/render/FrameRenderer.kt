package com.agency.timeflip.engine.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import com.agency.timeflip.domain.geometry.PhotoPlacement
import com.agency.timeflip.domain.model.AlignmentTransform
import com.agency.timeflip.domain.model.PixelSize
import com.agency.timeflip.domain.model.TransitionDefinition
import com.agency.timeflip.domain.transition.ClipShape
import com.agency.timeflip.domain.transition.TransitionMath
import kotlin.math.roundToInt

/**
 * The single Android drawing path for a transformation frame. The Compose preview and the MP4 exporter
 * both call [draw], so the preview and the encoded video cannot drift apart. Geometry and opacity come
 * from the domain (PhotoPlacement and TransitionMath).
 *
 * Scratch objects (Matrix, Path, Paint, polygon buffers) live in a ThreadLocal. The preview on the main
 * thread and the exporter on a worker can therefore call [draw] at the same time without sharing state.
 * The only per-frame allocations are small domain value objects.
 */
object FrameRenderer {

    /**
     * Draws one frame into [canvas], which must be [frame] pixels in size.
     *
     * BEFORE is cover-fitted and opaque. AFTER is cover-fitted, then placed by [alignment], and drawn
     * with the alpha and clip from [TransitionMath.frameAt] at [linearProgress] (0..1 on the timeline).
     */
    fun draw(
        canvas: Canvas,
        frame: PixelSize,
        before: Bitmap,
        after: Bitmap,
        alignment: AlignmentTransform,
        transition: TransitionDefinition,
        linearProgress: Double,
    ) {
        val scratch = SCRATCH.get()
        canvas.save()
        try {
            canvas.clipRect(0f, 0f, frame.width.toFloat(), frame.height.toFloat())

            scratch.paint.alpha = OPAQUE
            AndroidMatrix.set(
                scratch.matrix,
                scratch.values,
                PhotoPlacement.coverBase(PixelSize(before.width, before.height), frame),
            )
            canvas.drawBitmap(before, scratch.matrix, scratch.paint)

            val state = TransitionMath.frameAt(transition, linearProgress, frame)
            scratch.paint.alpha = (state.afterAlpha.coerceIn(0.0, 1.0) * OPAQUE).roundToInt()
            when (val clip = state.afterClip) {
                ClipShape.Empty -> return
                ClipShape.Full -> Unit
                is ClipShape.Rect -> canvas.clipRect(
                    clip.left.toFloat(),
                    clip.top.toFloat(),
                    clip.right.toFloat(),
                    clip.bottom.toFloat(),
                )
                is ClipShape.HalfPlane -> if (!clipToHalfPlane(canvas, scratch, frame, clip)) return
            }

            AndroidMatrix.set(
                scratch.matrix,
                scratch.values,
                PhotoPlacement.placement(PixelSize(after.width, after.height), frame, alignment),
            )
            canvas.drawBitmap(after, scratch.matrix, scratch.paint)
        } finally {
            canvas.restore()
        }
    }

    /** Clips to the part of the frame where n·q ≤ offset. Returns false when that part is empty. */
    private fun clipToHalfPlane(canvas: Canvas, s: Scratch, frame: PixelSize, plane: ClipShape.HalfPlane): Boolean {
        val count = clipFrameToHalfPlane(s, frame.width.toDouble(), frame.height.toDouble(), plane)
        if (count < 3) return false
        s.path.reset()
        s.path.moveTo(s.polyX[0].toFloat(), s.polyY[0].toFloat())
        for (k in 1 until count) {
            s.path.lineTo(s.polyX[k].toFloat(), s.polyY[k].toFloat())
        }
        s.path.close()
        canvas.clipPath(s.path)
        return true
    }

    /**
     * Sutherland–Hodgman clip of the frame rectangle against one half-plane. Writes the polygon to
     * s.polyX and s.polyY and returns its vertex count, which is 0 when nothing is left.
     */
    private fun clipFrameToHalfPlane(s: Scratch, width: Double, height: Double, plane: ClipShape.HalfPlane): Int {
        s.rectX[0] = 0.0
        s.rectY[0] = 0.0
        s.rectX[1] = width
        s.rectY[1] = 0.0
        s.rectX[2] = width
        s.rectY[2] = height
        s.rectX[3] = 0.0
        s.rectY[3] = height

        var count = 0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val di = plane.normalX * s.rectX[i] + plane.normalY * s.rectY[i] - plane.offset
            val dj = plane.normalX * s.rectX[j] + plane.normalY * s.rectY[j] - plane.offset
            val iInside = di <= 0.0
            val jInside = dj <= 0.0
            if (iInside) {
                s.polyX[count] = s.rectX[i]
                s.polyY[count] = s.rectY[i]
                count++
            }
            if (iInside != jInside) {
                val t = di / (di - dj)
                s.polyX[count] = s.rectX[i] + t * (s.rectX[j] - s.rectX[i])
                s.polyY[count] = s.rectY[i] + t * (s.rectY[j] - s.rectY[i])
                count++
            }
        }
        return count
    }

    private class Scratch {
        val matrix = Matrix()
        val values = FloatArray(9)
        val path = Path()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val rectX = DoubleArray(4)
        val rectY = DoubleArray(4)
        val polyX = DoubleArray(MAX_VERTICES)
        val polyY = DoubleArray(MAX_VERTICES)
    }

    private const val OPAQUE = 255
    private const val MAX_VERTICES = 8

    private val SCRATCH: ThreadLocal<Scratch> = ThreadLocal.withInitial { Scratch() }
}
