package com.agency.timeflip.domain.geometry

import kotlin.math.abs

data class Point(val x: Double, val y: Double)

/**
 * 2D affine transform mapping (x, y) to (a·x + c·y + e, b·x + d·y + f).
 * Immutable and free of platform types so the same math drives preview, export and tests.
 */
data class AffineMatrix(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
    val e: Double,
    val f: Double,
) {
    fun transformPoint(x: Double, y: Double): Point =
        Point(a * x + c * y + e, b * x + d * y + f)

    /** Applies this transform first, then [next]. */
    fun then(next: AffineMatrix): AffineMatrix = AffineMatrix(
        a = next.a * a + next.c * b,
        b = next.b * a + next.d * b,
        c = next.a * c + next.c * d,
        d = next.b * c + next.d * d,
        e = next.a * e + next.c * f + next.e,
        f = next.b * e + next.d * f + next.f,
    )

    /** Returns the inverse, or null when the transform is singular. */
    fun inverseOrNull(): AffineMatrix? {
        val determinant = a * d - b * c
        if (abs(determinant) < 1e-12) return null
        return AffineMatrix(
            a = d / determinant,
            b = -b / determinant,
            c = -c / determinant,
            d = a / determinant,
            e = (c * f - d * e) / determinant,
            f = (b * e - a * f) / determinant,
        )
    }

    companion object {
        val IDENTITY = AffineMatrix(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

        fun translation(dx: Double, dy: Double) = AffineMatrix(1.0, 0.0, 0.0, 1.0, dx, dy)

        fun scale(sx: Double, sy: Double) = AffineMatrix(sx, 0.0, 0.0, sy, 0.0, 0.0)
    }
}
