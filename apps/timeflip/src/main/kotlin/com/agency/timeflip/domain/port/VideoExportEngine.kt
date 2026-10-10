package com.agency.timeflip.domain.port

import com.agency.timeflip.domain.model.ExportProgress
import com.agency.timeflip.domain.model.ExportRequest
import kotlinx.coroutines.flow.Flow

/**
 * Renders and encodes a transformation video.
 *
 * Contract:
 * - Emits [ExportProgress.Rendering] for each rendered frame, then exactly one terminal event:
 *   Finished, Failed or Cancelled.
 * - Cancelling the collector stops rendering and deletes any partial output file.
 * - A Finished event is emitted only after the MP4 has been validated (dimensions, duration and
 * track format were read back from the file).
 */
interface VideoExportEngine {
    fun export(request: ExportRequest): Flow<ExportProgress>
}
