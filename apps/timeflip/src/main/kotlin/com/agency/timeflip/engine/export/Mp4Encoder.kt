package com.agency.timeflip.engine.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import com.agency.timeflip.domain.error.ErrorCategory
import com.agency.timeflip.domain.error.TimeFlipError
import com.agency.timeflip.domain.model.OutputPreset
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer

/** An AVC encoder that can handle one preset and frame rate, with the input layout it accepts. */
internal class EncoderChoice(
    val codecName: String,
    val colorFormat: Int,
    val bitrateMode: Int,
    val bitrateBps: Int,
) {
    companion object {
        const val MIME = "video/avc"

        private val COLOR_PREFERENCE = intArrayOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
        )

        /**
         * First hardware or software AVC encoder that supports the size and frame rate, a 4:2:0 input
         * layout and a bitrate range covering [requestedBitrateBps] (clamped into that range). Returns null
         * when no encoder qualifies. Prefers CBR, falling back to VBR.
         */
        fun find(preset: OutputPreset, fps: Int, requestedBitrateBps: Int): EncoderChoice? {
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (!info.isEncoder) continue
                if (info.supportedTypes.none { it.equals(MIME, ignoreCase = true) }) continue
                val caps = try {
                    info.getCapabilitiesForType(MIME)
                } catch (e: IllegalArgumentException) {
                    continue
                }
                val video = caps.videoCapabilities ?: continue
                if (!video.areSizeAndRateSupported(preset.width, preset.height, fps.toDouble())) continue
                val colorFormat = COLOR_PREFERENCE.firstOrNull { it in caps.colorFormats } ?: continue
                val encoder = caps.encoderCapabilities ?: continue
                val mode = if (encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) {
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                } else {
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                }
                val bitrate = requestedBitrateBps.coerceIn(video.bitrateRange.lower, video.bitrateRange.upper)
                return EncoderChoice(info.name, colorFormat, mode, bitrate)
            }
            return null
        }
    }
}

/**
 * Feeds YUV frames to an AVC encoder and muxes the output into an MPEG-4 file. Use once per export.
 * [close] releases the codec and the muxer on every path, including after a failure.
 */
internal class Mp4Encoder(
    preset: OutputPreset,
    private val fps: Int,
    private val choice: EncoderChoice,
    private val outputPath: String,
) : Closeable {

    private val width = preset.width
    private val height = preset.height
    private val frameBytes = width * height * 3 / 2
    private val info = MediaCodec.BufferInfo()

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var muxerStarted = false

    fun start() {
        val format = MediaFormat.createVideoFormat(EncoderChoice.MIME, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, choice.colorFormat)
        format.setInteger(MediaFormat.KEY_BIT_RATE, choice.bitrateBps)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, choice.bitrateMode)

        val created = try {
            MediaCodec.createByCodecName(choice.codecName)
        } catch (e: IOException) {
            throw ExportFailure(TimeFlipError(ErrorCategory.ENCODER_UNAVAILABLE, "create"))
        }
        codec = created
        try {
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            created.start()
        } catch (e: IllegalArgumentException) {
            throw ExportFailure(TimeFlipError(ErrorCategory.ENCODER_UNAVAILABLE, "configure"))
        } catch (e: IllegalStateException) {
            throw ExportFailure(TimeFlipError(ErrorCategory.ENCODER_UNAVAILABLE, "configure-state"))
        }

        val mux = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        mux.setOrientationHint(0)
        muxer = mux
    }

    /** Converts one ARGB frame and queues it with its presentation time in microseconds. */
    fun encodeFrame(argb: IntArray, presentationTimeUs: Long) {
        val c = checkNotNull(codec) { "encoder not started" }
        val index = dequeueInputIndex(c)
        val image = c.getInputImage(index)
        if (image != null) {
            val planes = image.planes
            Yuv420Writer.write(
                argb,
                width,
                height,
                Yuv420Writer.Plane(planes[0].buffer, 0, planes[0].rowStride, planes[0].pixelStride),
                Yuv420Writer.Plane(planes[1].buffer, 0, planes[1].rowStride, planes[1].pixelStride),
                Yuv420Writer.Plane(planes[2].buffer, 0, planes[2].rowStride, planes[2].pixelStride),
            )
        } else {
            writePacked(argb, checkNotNull(c.getInputBuffer(index)) { "no input buffer" })
        }
        c.queueInputBuffer(index, 0, frameBytes, presentationTimeUs, 0)
        drain(c, 0L)
    }

    /** Sends end-of-stream, waits for the encoder to flush, and finalises the MP4. */
    fun finish(lastPresentationTimeUs: Long) {
        val c = checkNotNull(codec) { "encoder not started" }
        val index = dequeueInputIndex(c)
        c.queueInputBuffer(index, 0, 0, lastPresentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drainUntilEndOfStream(c)
        if (!muxerStarted) throw ExportFailure(TimeFlipError(ErrorCategory.EXPORT_FAILED, "no-encoded-output"))
        val m = checkNotNull(muxer) { "muxer missing" }
        m.stop()
        m.release()
        muxer = null
    }

    override fun close() {
        codec?.let { c ->
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        codec = null
        muxer?.let { m ->
            if (muxerStarted) runCatching { m.stop() }
            runCatching { m.release() }
        }
        muxer = null
        muxerStarted = false
    }

    /** Packed input for a codec that does not expose Image planes. Layout depends on the chosen format. */
    private fun writePacked(argb: IntArray, buffer: ByteBuffer) {
        val lumaSize = width * height
        val chromaSize = lumaSize / 4
        val yPlane = Yuv420Writer.Plane(buffer, 0, width, 1)
        when (choice.colorFormat) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> Yuv420Writer.write(
                argb,
                width,
                height,
                yPlane,
                Yuv420Writer.Plane(buffer, lumaSize, width, 2),
                Yuv420Writer.Plane(buffer, lumaSize + 1, width, 2),
            )
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> Yuv420Writer.write(
                argb,
                width,
                height,
                yPlane,
                Yuv420Writer.Plane(buffer, lumaSize, width / 2, 1),
                Yuv420Writer.Plane(buffer, lumaSize + chromaSize, width / 2, 1),
            )
            else -> throw ExportFailure(TimeFlipError(ErrorCategory.ENCODER_UNAVAILABLE, "no-input-layout"))
        }
    }

    private fun dequeueInputIndex(c: MediaCodec): Int {
        var waits = 0
        while (true) {
            val index = c.dequeueInputBuffer(WAIT_US)
            if (index >= 0) return index
            // Keep the output side moving so the encoder can accept more input.
            drain(c, 0L)
            if (++waits > MAX_WAITS) throw ExportFailure(TimeFlipError(ErrorCategory.EXPORT_FAILED, "encoder-stalled"))
        }
    }

    private fun drainUntilEndOfStream(c: MediaCodec) {
        var waits = 0
        while (!drain(c, WAIT_US)) {
            if (++waits > MAX_WAITS) throw ExportFailure(TimeFlipError(ErrorCategory.EXPORT_FAILED, "encoder-stalled"))
        }
    }

    /** Writes available output to the muxer. Returns true once the end-of-stream buffer has been seen. */
    private fun drain(c: MediaCodec, timeoutUs: Long): Boolean {
        while (true) {
            val index = c.dequeueOutputBuffer(info, timeoutUs)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> startMuxer(c.outputFormat)
                index < 0 -> Unit
                else -> {
                    val buffer = checkNotNull(c.getOutputBuffer(index)) { "no output buffer" }
                    val isCodecConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (!isCodecConfig && info.size > 0) {
                        check(muxerStarted) { "output before muxer start" }
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        checkNotNull(muxer).writeSampleData(track, buffer, info)
                    }
                    c.releaseOutputBuffer(index, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true
                }
            }
        }
    }

    private fun startMuxer(format: MediaFormat) {
        if (muxerStarted) return
        val m = checkNotNull(muxer) { "muxer missing" }
        track = m.addTrack(format)
        m.start()
        muxerStarted = true
    }

    private companion object {
        const val WAIT_US = 10_000L
        const val MAX_WAITS = 1_000
    }
}
