package com.agency.stickerjet.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import com.agency.stickerjet.domain.ArgbFrame
import com.agency.stickerjet.domain.FrameSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Decodes frames from a stored source clip with [MediaMetadataRetriever], one flow per call.
 *
 * - [sourceFor] maps a project id to its source clip file, so the app wires
 *   `{ projectId -> files.sourceFile(projectId) }`.
 * - Each timestamp is decoded with OPTION_CLOSEST. The display rotation from METADATA_KEY_VIDEO_ROTATION is
 *   applied by this class, not assumed to be applied by the platform.
 * - The frame is scaled so its longer side is at most [maxSidePx] and copied into an [ArgbFrame]. The
 *   platform bitmap is recycled straight after the copy, so no decoded frame outlives its emission.
 * - The retriever is released in `finally` and cancellation is checked before every frame.
 *
 * Minimum SDK 26: getScaledFrameAtTime (API 27) is not used; scaling is done with a Canvas draw instead.
 */
class MediaFrameSource(private val sourceFor: (projectId: String) -> File) : FrameSource {

    override fun frames(projectId: String, timestampsMs: List<Long>, maxSidePx: Int): Flow<ArgbFrame> = flow {
        require(maxSidePx > 0) { "maxSidePx must be positive" }
        val file = sourceFor(projectId)
        if (!file.isFile) throw SourceMissingException(projectId)
        val retriever = MediaMetadataRetriever()
        try {
            try {
                retriever.setDataSource(file.absolutePath)
            } catch (error: IllegalArgumentException) {
                throw IOException("Source clip is unreadable", error)
            }
            val rotation = normalizeRotation(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION))
            for (timestampMs in timestampsMs) {
                currentCoroutineContext().ensureActive()
                val decoded = retriever.getFrameAtTime(timestampMs * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: throw IOException("No video frame at $timestampMs ms")
                try {
                    emit(orient(decoded, rotation, maxSidePx, timestampMs))
                } finally {
                    decoded.recycle()
                }
            }
        } finally {
            retriever.release()
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        /** Rounds the rotation to a quarter turn in 0..270 clockwise. Missing or odd values count as 0. */
        internal fun normalizeRotation(raw: String?): Int {
            val degrees = raw?.toIntOrNull() ?: 0
            val quarters = Math.round(degrees / 90f)
            return ((quarters * 90) % 360 + 360) % 360
        }

        /** Rotates [source] clockwise by [rotation] degrees and scales it into an ARGB_8888 copy. */
        internal fun orient(source: Bitmap, rotation: Int, maxSidePx: Int, timestampMs: Long): ArgbFrame {
            val swapped = rotation == 90 || rotation == 270
            val rotatedW = if (swapped) source.height else source.width
            val rotatedH = if (swapped) source.width else source.height
            val scale = min(1f, maxSidePx.toFloat() / max(rotatedW, rotatedH))
            val outW = max(1, (rotatedW * scale).roundToInt())
            val outH = max(1, (rotatedH * scale).roundToInt())
            val matrix = Matrix().apply {
                postTranslate(-source.width / 2f, -source.height / 2f)
                postRotate(rotation.toFloat())
                postTranslate(rotatedW / 2f, rotatedH / 2f)
                postScale(outW / rotatedW.toFloat(), outH / rotatedH.toFloat())
            }
            val target = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            try {
                Canvas(target).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
                val pixels = IntArray(outW * outH)
                target.getPixels(pixels, 0, outW, 0, 0, outW, outH)
                return ArgbFrame(outW, outH, pixels, timestampMs)
            } finally {
                target.recycle()
            }
        }
    }
}
