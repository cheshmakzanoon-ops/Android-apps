package com.agency.timeflip.engine.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.agency.timeflip.domain.error.ErrorCategory
import com.agency.timeflip.domain.error.TimeFlipError
import com.agency.timeflip.domain.error.TimeFlipException
import com.agency.timeflip.domain.model.ExportProgress
import com.agency.timeflip.domain.model.ExportRequest
import com.agency.timeflip.domain.model.ExportedVideo
import com.agency.timeflip.domain.model.OutputPreset
import com.agency.timeflip.domain.model.PhotoId
import com.agency.timeflip.domain.port.PhotoStore
import com.agency.timeflip.domain.port.VideoExportEngine
import com.agency.timeflip.engine.image.PhotoBitmaps
import com.agency.timeflip.engine.render.FrameRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.File

/**
 * Renders a transformation to an MP4 with the shared [FrameRenderer], encodes it as AVC, validates the file
 * by reading it back, and only then renames it into [exportsDir]. Emits exactly one terminal event.
 *
 * The collector can cancel at any point. The partial file and codec are then released and the partial file
 * deleted. No Cancelled event is sent to a collector that has already cancelled.
 */
class Mp4VideoExporter(
    private val photoStore: PhotoStore,
    private val exportsDir: File,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : VideoExportEngine {

    private val photoBitmaps = PhotoBitmaps(photoStore)

    override fun export(request: ExportRequest): Flow<ExportProgress> = flow {
        val video = encodeAndValidate(request) { done, total ->
            emit(ExportProgress.Rendering(framesDone = done, framesTotal = total))
        }
        emit(ExportProgress.Finished(video))
    }
        .flowOn(Dispatchers.Default)
        .catch { cause ->
            // catch sees only upstream failures, never exceptions thrown by the collector.
            when (cause) {
                is ExportFailure -> emit(ExportProgress.Failed(cause.error))
                is TimeFlipException -> emit(ExportProgress.Failed(cause.error))
                is CancellationException -> {
                    // The collector went away: nothing can be delivered. An internal cancellation is reported.
                    if (currentCoroutineContext().isActive) emit(ExportProgress.Cancelled) else throw cause
                }
                else -> emit(ExportProgress.Failed(TimeFlipError(ErrorCategory.EXPORT_FAILED, cause.javaClass.simpleName)))
            }
        }

    private suspend fun encodeAndValidate(
        request: ExportRequest,
        onRendered: suspend (done: Int, total: Int) -> Unit,
    ): ExportedVideo {
        val (preset, choice) = selectEncoder(request.profile.preset, request.profile.fps, request.profile.bitrateBps)
            ?: throw ExportFailure(TimeFlipError(ErrorCategory.ENCODER_UNAVAILABLE, "no-avc-encoder"))
        val frame = preset.frame
        val timeline = request.timeline
        val before = decodeOrFail(request.pair.before.id, frame.longestEdge)
        val after = decodeOrFail(request.pair.after.id, frame.longestEdge)

        val stamp = nowMs()
        exportsDir.mkdirs()
        val baseName = "$stamp-${request.projectId.value}"
        val partial = File(exportsDir, "$baseName.part")
        val target = File(exportsDir, "$baseName.mp4")
        partial.delete()

        var completed = false
        try {
            // One ARGB frame bitmap and one pixel buffer, reused for every frame, so memory stays bounded.
            val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val argb = IntArray(frame.width * frame.height)
            val total = timeline.frameCount

            Mp4Encoder(preset, request.profile.fps, choice, partial.absolutePath).use { encoder ->
                encoder.start()
                for (i in 0 until total) {
                    currentCoroutineContext().ensureActive()
                    bitmap.eraseColor(Color.BLACK)
                    FrameRenderer.draw(
                        canvas,
                        frame,
                        before,
                        after,
                        request.alignment,
                        request.transition,
                        timeline.linearProgress(i),
                    )
                    bitmap.getPixels(argb, 0, frame.width, 0, 0, frame.width, frame.height)
                    encoder.encodeFrame(argb, timeline.presentationTimeUs(i))
                    onRendered(i + 1, total)
                }
                encoder.finish(timeline.presentationTimeUs(total - 1))
            }

            when (val verdict = Mp4Validator.validate(partial, preset, timeline.totalDurationMs)) {
                is Mp4Validator.Verdict.Invalid ->
                    throw ExportFailure(TimeFlipError(ErrorCategory.EXPORT_FAILED, verdict.reason))
                is Mp4Validator.Verdict.Valid -> Unit
            }
            if (!partial.renameTo(target)) {
                throw ExportFailure(TimeFlipError(ErrorCategory.EXPORT_FAILED, "rename-failed"))
            }
            completed = true
            return ExportedVideo(
                projectId = request.projectId,
                fileName = target.name,
                sizeBytes = target.length(),
                durationMs = timeline.totalDurationMs,
                width = preset.width,
                height = preset.height,
                fps = request.profile.fps,
                createdAtEpochMs = stamp,
            )
        } finally {
            if (!completed) partial.delete()
        }
    }

    private suspend fun decodeOrFail(id: PhotoId, maxEdge: Int): Bitmap {
        if (photoStore.originalPath(id) == null) throw ExportFailure(TimeFlipError(ErrorCategory.SOURCE_MISSING))
        return photoBitmaps.decode(id, maxEdge) ?: throw ExportFailure(TimeFlipError(ErrorCategory.DECODE_FAILED))
    }

    private fun selectEncoder(
        requested: OutputPreset,
        fps: Int,
        bitrateBps: Int,
    ): Pair<OutputPreset, EncoderChoice>? {
        var candidate: OutputPreset? = requested
        while (candidate != null) {
            val choice = EncoderChoice.find(candidate, fps, bitrateBps)
            if (choice != null) return candidate to choice
            candidate = candidate.fallback()
        }
        return null
    }
}

/** Internal failure carrying a stable category. Converted to ExportProgress.Failed at the flow boundary. */
internal class ExportFailure(val error: TimeFlipError) : RuntimeException(error.category.name)
