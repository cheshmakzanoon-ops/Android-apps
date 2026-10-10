package com.agency.stickerjet.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.agency.stickerjet.domain.ExportPlan
import com.agency.stickerjet.domain.FrameSource
import com.agency.stickerjet.domain.StickerProject
import com.agency.stickerjet.domain.SubjectSegmenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Renders the editor preview: the same frames, masks and composition as the export, on a smaller canvas
 * (for example 256 px). Each frame is encoded and decoded at [PREVIEW_QUALITY], so the preview shows the
 * compression the exported file will have.
 *
 * The caller owns the returned bitmaps and must recycle them when the preview is replaced. Failures
 * propagate as exceptions: [SourceMissingException] or [SegmentationFailedException]. Cancellation is
 * rethrown after the partial bitmaps are recycled.
 */
class StickerPreviewRenderer(
    private val frames: FrameSource,
    private val segmenter: SubjectSegmenter,
    private val encoder: FrameEncoder,
) {
    private val captions = CaptionRenderer()

    /** Renders the trimmed loop of [project] on a [canvasPx]-pixel square canvas. */
    suspend fun render(project: StickerProject, canvasPx: Int): PreviewResult = withContext(Dispatchers.Default) {
        require(canvasPx > 0) { "Preview canvas must be positive" }
        val timestamps = ExportPlan.frameTimestampsMs(project.trim)
        val durations = ExportPlan.frameDurationsMs(project.trim)
        val frameCount = timestamps.size
        val pipeline = StickerPipeline(segmenter, captions)
        val rendered = ArrayList<Bitmap>(frameCount)
        try {
            pipeline.newCanvas(canvasPx).use { canvas ->
                var index = 0
                frames.frames(project.id, timestamps, canvasPx).collect { frame ->
                    currentCoroutineContext().ensureActive()
                    canvas.load(frame, pipeline.alphaFor(frame, project), project)
                    rendered += roundTrip(canvas.bitmap)
                    index++
                }
                check(index == frameCount) { "Frame source returned $index of $frameCount frames" }
            }
            PreviewResult(rendered, durations, pipeline.subjectFrames, frameCount)
        } catch (error: Throwable) {
            rendered.forEach { it.recycle() }
            throw error
        }
    }

    private fun roundTrip(bitmap: Bitmap): Bitmap {
        val bytes = encoder.encode(bitmap, PREVIEW_QUALITY)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("Preview frame did not decode")
    }

    private companion object {
        const val PREVIEW_QUALITY = 85
    }
}
