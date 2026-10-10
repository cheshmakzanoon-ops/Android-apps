package com.agency.timeflip.ui.screens.manualalign

import com.agency.timeflip.domain.geometry.PhotoPlacement
import com.agency.timeflip.domain.model.AlignmentTransform
import com.agency.timeflip.domain.model.PixelSize
import kotlin.math.max
import kotlin.math.min

/**
 * "Fit" for the manual alignment screen. It zooms in only as far as needed to remove empty edges
 * and keeps the rotation and position the user chose. If the frame cannot be covered even at the
 * maximum scale, the transform is returned unchanged and the coverage warning remains visible.
 */
internal object AlignmentFit {

    private const val SEARCH_STEPS = 24

    fun fitToCover(transform: AlignmentTransform, afterSize: PixelSize, frame: PixelSize): AlignmentTransform {
        fun coverageAt(scale: Double): Double = PhotoPlacement.coverage(
            source = afterSize,
            frame = frame,
            alignment = transform.withScale(scale),
        )

        if (coverageAt(transform.scale) >= FULL_COVERAGE) return transform
        val maxScale = AlignmentTransform.MAX_SCALE
        if (coverageAt(maxScale) < FULL_COVERAGE) return transform

        var low = transform.scale
        var high = maxScale
        repeat(SEARCH_STEPS) {
            val middle = (low + high) / 2.0
            if (coverageAt(middle) >= FULL_COVERAGE) high = middle else low = middle
        }
        return transform.withScale(max(min(high, maxScale), AlignmentTransform.MIN_SCALE))
    }

    private fun AlignmentTransform.withScale(scale: Double): AlignmentTransform =
        AlignmentTransform(
            scale = scale.coerceIn(AlignmentTransform.MIN_SCALE, AlignmentTransform.MAX_SCALE),
            rotationDegrees = rotationDegrees,
            offsetX = offsetX,
            offsetY = offsetY,
        )
}

internal const val FULL_COVERAGE = 1.0
