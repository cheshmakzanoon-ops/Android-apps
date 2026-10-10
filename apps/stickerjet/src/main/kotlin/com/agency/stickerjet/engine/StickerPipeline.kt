package com.agency.stickerjet.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import com.agency.stickerjet.domain.AlphaMask
import com.agency.stickerjet.domain.ArgbFrame
import com.agency.stickerjet.domain.BackgroundMode
import com.agency.stickerjet.domain.StickerProject
import com.agency.stickerjet.domain.SubjectSegmenter
import kotlinx.coroutines.CancellationException

/**
 * The per-frame stage shared by the exporter and the preview renderer, so both produce the same pixels.
 *
 * [alphaFor] decides the mask. Segmentation runs only for REMOVE_BACKGROUND, and a null segmentation keeps
 * the original background. The stored strokes then apply in order through [MaskRefiner]. With no mask and no
 * strokes the result is null, meaning fully opaque. [subjectFrames] counts frames where a person was found.
 */
internal class StickerPipeline(
    private val segmenter: SubjectSegmenter,
    private val captions: CaptionRenderer,
) {
    var subjectFrames: Int = 0
        private set

    /** Returns the refined mask for [frame], or null for an opaque frame. Call once per frame and cache it. */
    suspend fun alphaFor(frame: ArgbFrame, project: StickerProject): AlphaMask? {
        var base: AlphaMask? = null
        if (project.background == BackgroundMode.REMOVE_BACKGROUND) {
            base = segment(frame)
            if (base != null) subjectFrames++
        }
        if (base == null && project.maskStrokes.isEmpty()) return null
        return MaskRefiner.refine(base, frame.width, frame.height, project.maskStrokes)
    }

    /** A segmenter exception is a failure; cancellation passes through untouched. */
    private suspend fun segment(frame: ArgbFrame): AlphaMask? = try {
        segmenter.segment(frame)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        throw SegmentationFailedException(error)
    }

    /** Creates the caption-ready canvas for this pipeline. Close it when the stage is finished. */
    fun newCanvas(canvasPx: Int): FrameCanvas = FrameCanvas(canvasPx, captions)
}

/**
 * One reusable square ARGB canvas: composed pixels, the bitmap that is encoded, and the caption layer.
 * [load] overwrites every pixel, so one instance serves all frames of a pass.
 */
internal class FrameCanvas(val canvasPx: Int, private val captions: CaptionRenderer) : AutoCloseable {
    val bitmap: Bitmap = Bitmap.createBitmap(canvasPx, canvasPx, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(canvasPx * canvasPx)
    private val drawTarget = Canvas(bitmap)

    /** Composes [frame] with [alpha] under the project's transform, then draws the caption on top. */
    fun load(frame: ArgbFrame, alpha: AlphaMask?, project: StickerProject) {
        StickerComposer.compose(frame, alpha, project.transform, canvasPx, pixels)
        bitmap.setPixels(pixels, 0, canvasPx, 0, 0, canvasPx, canvasPx)
        captions.draw(drawTarget, project.caption, canvasPx)
    }

    override fun close() {
        bitmap.recycle()
    }
}
