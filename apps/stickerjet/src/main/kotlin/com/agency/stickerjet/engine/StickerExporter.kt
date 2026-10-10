package com.agency.stickerjet.engine

import com.agency.stickerjet.data.ProjectFiles
import com.agency.stickerjet.domain.AlphaMask
import com.agency.stickerjet.domain.ExportPlan
import com.agency.stickerjet.domain.ExportRecord
import com.agency.stickerjet.domain.FrameSource
import com.agency.stickerjet.domain.ProjectRepository
import com.agency.stickerjet.domain.StickerFileNamer
import com.agency.stickerjet.domain.StickerLimits
import com.agency.stickerjet.domain.StickerProject
import com.agency.stickerjet.domain.SubjectSegmenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Renders a project to one animated WebP and records it in the project.
 *
 * Pipeline: frames are streamed one at a time from [frames] (the key passed is the project id), so no
 * decoded frame is kept. Masks are computed once in the first pass and cached, which is cheap because they
 * are source-sized bytes. Each pass composes onto a 512 px canvas, encodes each frame at the current quality
 * and muxes with [AnimatedWebpWriter]. The quality ladder 85, 70, 55, 40 is tried until the file fits
 * [StickerLimits.MAX_EXPORT_BYTES]. Later passes re-decode the frames but do not re-segment them.
 *
 * Publishing: the file is written to `<name>.part` in the exports directory of [files], renamed to its final name, and only
 * then is the record appended to the project. Cancellation or failure deletes the temporary and any published
 * file, and leaves the project draft as it was.
 *
 * [files] is the data layer's [ProjectFiles], as architecture section 3.4 specifies. It supplies the exports
 * directory. Frame timing comes from [ExportPlan] and file names from [StickerFileNamer].
 */
class StickerExporter(
    private val frames: FrameSource,
    private val segmenter: SubjectSegmenter,
    private val encoder: FrameEncoder,
    private val repository: ProjectRepository,
    private val files: ProjectFiles,
    private val clock: () -> Long,
) {
    private val captions = CaptionRenderer()
    private val writer = AnimatedWebpWriter(StickerLimits.CANVAS_PX)

    /**
     * Exports [project]. [onProgress] receives values from 0 to 1 while frames are processed, then 1 on success.
     * Coroutine cancellation rethrows after cleanup. Every other failure returns [ExportOutcome.Failed].
     */
    suspend fun export(project: StickerProject, onProgress: (Float) -> Unit): ExportOutcome =
        withContext(Dispatchers.Default) {
            val exportDir = files.exportsDir(project.id)
            var temp: File? = null
            var published: File? = null
            try {
                onProgress(0f)
                val timestamps = ExportPlan.frameTimestampsMs(project.trim)
                val durations = ExportPlan.frameDurationsMs(project.trim)
                val frameCount = timestamps.size
                val pipeline = StickerPipeline(segmenter, captions)
                val bytes = encodeWithinBudget(project, timestamps, durations, pipeline, onProgress)
                    ?: return@withContext ExportOutcome.Failed(ExportFailure.TOO_LARGE)

                exportDir.mkdirs()
                if (exportDir.usableSpace < bytes.size.toLong() * STORAGE_HEADROOM) {
                    return@withContext ExportOutcome.Failed(ExportFailure.LOW_STORAGE)
                }
                val now = clock()
                val name = StickerFileNamer.fileName(project.title, now, exportDir.list()?.toSet().orEmpty())
                val part = File(exportDir, "$name.part")
                temp = part
                withContext(Dispatchers.IO) { writeFile(part, bytes) }
                val target = File(exportDir, name)
                if (!part.renameTo(target)) throw IOException("Could not publish $name")
                temp = null
                published = target

                val record = ExportRecord(
                    fileName = name,
                    createdAtMs = now,
                    sizeBytes = bytes.size.toLong(),
                    frameCount = frameCount,
                    durationMs = durations.sum().toLong(),
                )
                repository.save(project.copy(exports = project.exports + record, updatedAtMs = now))
                onProgress(1f)
                ExportOutcome.Success(target, record, pipeline.subjectFrames, frameCount)
            } catch (cancelled: CancellationException) {
                discard(temp, published)
                throw cancelled
            } catch (missing: SourceMissingException) {
                discard(temp, published)
                ExportOutcome.Failed(ExportFailure.SOURCE_MISSING)
            } catch (failed: SegmentationFailedException) {
                discard(temp, published)
                ExportOutcome.Failed(ExportFailure.SEGMENTATION_FAILED)
            } catch (io: IOException) {
                discard(temp, published)
                val reason = if (isLowSpace(exportDir)) ExportFailure.LOW_STORAGE else ExportFailure.INTERNAL
                ExportOutcome.Failed(reason)
            } catch (error: Exception) {
                discard(temp, published)
                ExportOutcome.Failed(ExportFailure.INTERNAL)
            }
        }

    /** Returns the muxed file for the first ladder step that fits, or null when none does. */
    private suspend fun encodeWithinBudget(
        project: StickerProject,
        timestamps: List<Long>,
        durations: IntArray,
        pipeline: StickerPipeline,
        onProgress: (Float) -> Unit,
    ): ByteArray? {
        val count = timestamps.size
        val masks = arrayOfNulls<AlphaMask>(count)
        val masked = BooleanArray(count)
        pipeline.newCanvas(StickerLimits.CANVAS_PX).use { canvas ->
            for (quality in QUALITY_LADDER) {
                val encoded = ArrayList<WebpFrame>(count)
                var index = 0
                frames.frames(project.id, timestamps, StickerLimits.CANVAS_PX).collect { frame ->
                    currentCoroutineContext().ensureActive()
                    if (!masked[index]) {
                        masks[index] = pipeline.alphaFor(frame, project)
                        masked[index] = true
                    }
                    canvas.load(frame, masks[index], project)
                    encoded += WebpFrame(encoder.encode(canvas.bitmap, quality), durations[index])
                    index++
                    onProgress(PASS_PROGRESS * index / count)
                }
                check(index == count) { "Frame source returned $index of $count frames" }
                val muxed = writer.write(encoded)
                if (encoder.fits(muxed.size.toLong())) return muxed
            }
        }
        return null
    }

    private fun isLowSpace(dir: File): Boolean = dir.usableSpace < LOW_SPACE_BYTES

    private fun writeFile(target: File, bytes: ByteArray) {
        FileOutputStream(target).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
    }

    private fun discard(vararg files: File?) {
        for (file in files) file?.delete()
    }

    private companion object {
        val QUALITY_LADDER = listOf(85, 70, 55, 40)
        const val PASS_PROGRESS = 0.9f
        const val STORAGE_HEADROOM = 2L
        const val LOW_SPACE_BYTES = 1L shl 20
    }
}
