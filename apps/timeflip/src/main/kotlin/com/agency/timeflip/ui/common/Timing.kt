package com.agency.timeflip.ui.common

import com.agency.timeflip.domain.model.OutputPreset
import com.agency.timeflip.domain.model.PixelSize
import com.agency.timeflip.domain.model.TimelinePlan

/** Longest edge of the live preview. Keeps the preview bitmaps and the Canvas bounded on phones. */
const val PREVIEW_MAX_EDGE = 720

/** Preview frame with the same aspect ratio as the output preset. */
fun OutputPreset.previewFrame(): PixelSize = frame.scaledToLongestEdge(PREVIEW_MAX_EDGE)

/** Index of the frame presented at [positionMs] on the timeline, clamped to the last frame. */
fun TimelinePlan.frameIndexAt(positionMs: Int): Int {
    val index = (positionMs.toLong() * fps / 1000L).toInt()
    return index.coerceIn(0, frameCount - 1)
}

/** Linear transition progress at [positionMs]; the exporter uses the same value for the same frame. */
fun TimelinePlan.progressAt(positionMs: Int): Double = linearProgress(frameIndexAt(positionMs))
