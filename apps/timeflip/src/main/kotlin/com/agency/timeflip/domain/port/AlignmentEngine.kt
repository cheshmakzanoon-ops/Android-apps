package com.agency.timeflip.domain.port

import com.agency.timeflip.domain.image.GrayRaster
import com.agency.timeflip.domain.model.AlignmentEstimate
import com.agency.timeflip.domain.model.PixelSize

/**
 * Estimates how the AFTER photograph should be placed over the BEFORE photograph.
 *
 * Both rasters are already cover-fit into the same working frame. The returned transform is
 * expressed in frame fractions, so it applies unchanged at any output resolution.
 */
interface AlignmentEngine {
    suspend fun estimate(before: GrayRaster, after: GrayRaster, frame: PixelSize): AlignmentEstimate
}
