package com.agency.timeflip.domain.geometry

import com.agency.timeflip.domain.model.AlignmentTransform
import com.agency.timeflip.domain.model.PixelSize
import kotlin.math.max

/**
 * Base cover-fit placement of a photograph inside the output frame, then the user or
 * automatic alignment. Shared by the engine, the preview and the coverage check.
 */
object PhotoPlacement {

    /** Scales the photograph so it fills [frame] completely, centred (cover fit). */
    fun coverBase(source: PixelSize, frame: PixelSize): AffineMatrix {
        val scale = max(
            frame.width.toDouble() / source.width,
            frame.height.toDouble() / source.height,
        )
        val dx = (frame.width - source.width * scale) / 2.0
        val dy = (frame.height - source.height * scale) / 2.0
        return AffineMatrix(scale, 0.0, 0.0, scale, dx, dy)
    }

    /** Maps source pixels to frame pixels for the AFTER photograph. */
    fun placement(source: PixelSize, frame: PixelSize, alignment: AlignmentTransform): AffineMatrix =
        coverBase(source, frame).then(alignment.toMatrix(frame))

    /**
     * Fraction (0..1) of the frame that is covered by the placed photograph, sampled on a
     * [gridSteps] × [gridSteps] grid at cell centres. 1.0 means no empty edges would be exported.
     */
    fun coverage(
        source: PixelSize,
        frame: PixelSize,
        alignment: AlignmentTransform,
        gridSteps: Int = 24,
    ): Double {
        require(gridSteps > 0) { "gridSteps must be positive" }
        val inverse = placement(source, frame, alignment).inverseOrNull() ?: return 0.0
        var covered = 0
        for (row in 0 until gridSteps) {
            for (column in 0 until gridSteps) {
                val x = (column + 0.5) * frame.width / gridSteps
                val y = (row + 0.5) * frame.height / gridSteps
                val source2 = inverse.transformPoint(x, y)
                if (source2.x >= 0 && source2.y >= 0 && source2.x <= source.width && source2.y <= source.height) {
                    covered++
                }
            }
        }
        return covered.toDouble() / (gridSteps * gridSteps)
    }
}
