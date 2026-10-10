package com.agency.timeflip.engine.export

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.agency.timeflip.domain.model.OutputPreset
import java.io.File
import kotlin.math.abs

/**
 * Reads an encoded MP4 back and checks it against what was requested. The exporter emits Finished only
 * after this returns [Verdict.Valid]. Nothing is trusted from the encoder's own report.
 */
internal object Mp4Validator {

    sealed interface Verdict {
        data class Valid(val sampleCount: Int, val durationMs: Long) : Verdict

        /** [reason] is a fixed code for developer logs. It never contains file names or paths. */
        data class Invalid(val reason: String) : Verdict
    }

    /** Allowed difference between the file duration and the timeline duration, as a fraction. */
    private const val DURATION_TOLERANCE = 0.02

    fun validate(file: File, preset: OutputPreset, expectedDurationMs: Int): Verdict {
        if (!file.isFile || file.length() <= 0L) return Verdict.Invalid("file-missing-or-empty")
        val path = file.absolutePath

        var sampleCount = 0
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            if (extractor.trackCount != 1) return Verdict.Invalid("track-count")
            val format = extractor.getTrackFormat(0)
            if (format.getString(MediaFormat.KEY_MIME) != EncoderChoice.MIME) return Verdict.Invalid("mime")
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            if (width != preset.width || height != preset.height) return Verdict.Invalid("track-dimensions")

            extractor.selectTrack(0)
            var firstIsSync = false
            while (extractor.sampleTime >= 0) {
                if (sampleCount == 0) {
                    firstIsSync = (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                }
                sampleCount++
                if (!extractor.advance()) break
            }
            if (sampleCount < 1) return Verdict.Invalid("no-samples")
            if (!firstIsSync) return Verdict.Invalid("first-sample-not-sync")
        } catch (e: Exception) {
            return Verdict.Invalid("track-unreadable")
        } finally {
            extractor.release()
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: return Verdict.Invalid("duration-unreadable")
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            if (width != preset.width || height != preset.height) return Verdict.Invalid("container-dimensions")
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            if (rotation != null && rotation != "0") return Verdict.Invalid("rotation")
            val tolerance = expectedDurationMs * DURATION_TOLERANCE
            if (abs(durationMs - expectedDurationMs).toDouble() > tolerance) return Verdict.Invalid("duration")
            return Verdict.Valid(sampleCount, durationMs)
        } catch (e: Exception) {
            return Verdict.Invalid("metadata-unreadable")
        } finally {
            retriever.release()
        }
    }
}
