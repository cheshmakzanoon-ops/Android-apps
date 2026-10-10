package com.agency.timeflip.domain.model

import com.agency.timeflip.domain.geometry.AffineMatrix
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Placement of the AFTER photograph relative to the output frame, applied after cover-fit.
 *
 * - [scale] is a uniform zoom factor.
 * - [rotationDegrees] rotates about the frame centre.
 * - [offsetX] and [offsetY] are fractions of the frame width and height, so the same transform
 *   works at every output resolution.
 *
 * Mapping in frame pixels: p' = R(θ)·s·(p − c) + c + t, with t = (offsetX·W, offsetY·H).
 */
data class AlignmentTransform(
    val scale: Double = 1.0,
    val rotationDegrees: Double = 0.0,
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0,
) {
    init {
        require(scale.isFinite() && scale in MIN_SCALE..MAX_SCALE) {
            "scale must be within $MIN_SCALE..$MAX_SCALE, was $scale"
        }
        require(rotationDegrees.isFinite() && abs(rotationDegrees) <= MAX_ROTATION_DEGREES) {
            "rotation must be within ±$MAX_ROTATION_DEGREES degrees, was $rotationDegrees"
        }
        require(offsetX.isFinite() && abs(offsetX) <= MAX_OFFSET) {
            "offsetX must be within ±$MAX_OFFSET, was $offsetX"
        }
        require(offsetY.isFinite() && abs(offsetY) <= MAX_OFFSET) {
            "offsetY must be within ±$MAX_OFFSET, was $offsetY"
        }
    }

    /** Affine matrix in frame pixels for an output frame of [frame] size. */
    fun toMatrix(frame: PixelSize): AffineMatrix {
        val radians = Math.toRadians(rotationDegrees)
        val cosine = cos(radians) * scale
        val sine = sin(radians) * scale
        val centreX = frame.width / 2.0
        val centreY = frame.height / 2.0
        val translateX = centreX - (cosine * centreX - sine * centreY) + offsetX * frame.width
        val translateY = centreY - (sine * centreX + cosine * centreY) + offsetY * frame.height
        return AffineMatrix(a = cosine, b = sine, c = -sine, d = cosine, e = translateX, f = translateY)
    }

    companion object {
        const val MIN_SCALE = 0.5
        const val MAX_SCALE = 4.0
        const val MAX_ROTATION_DEGREES = 45.0
        const val MAX_OFFSET = 1.0

        val IDENTITY = AlignmentTransform()

        /** Clamps every component into its legal range. Use for values coming from gestures. */
        fun clamped(
            scale: Double,
            rotationDegrees: Double,
            offsetX: Double,
            offsetY: Double,
        ): AlignmentTransform = AlignmentTransform(
            scale = finiteOr(scale, 1.0).coerceIn(MIN_SCALE, MAX_SCALE),
            rotationDegrees = finiteOr(rotationDegrees, 0.0).coerceIn(-MAX_ROTATION_DEGREES, MAX_ROTATION_DEGREES),
            offsetX = finiteOr(offsetX, 0.0).coerceIn(-MAX_OFFSET, MAX_OFFSET),
            offsetY = finiteOr(offsetY, 0.0).coerceIn(-MAX_OFFSET, MAX_OFFSET),
        )

        private fun finiteOr(value: Double, fallback: Double) = if (value.isFinite()) value else fallback
    }
}
