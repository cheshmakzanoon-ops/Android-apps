package com.agency.stickerjet.engine

/**
 * What [WebpInspector] found in a WebP file.
 * - [frameCount] counts `ANMF` chunks. A still image reports 1 frame and the single duration 0.
 * - [frameDurationsMs] has exactly [frameCount] entries, in display order.
 * - [hasAlphaChunks] is true when any `ALPH` chunk is present (lossy alpha). `VP8L` alpha is intrinsic
 *   and is not counted here.
 * - [isAnimated] is true only for two or more frames, so a single frame is never reported as animated.
 */
data class WebpInfo(
    val canvasWidth: Int,
    val canvasHeight: Int,
    val frameCount: Int,
    val frameDurationsMs: List<Int>,
    val hasAlphaChunks: Boolean,
    val loopCount: Int,
) {
    val isAnimated: Boolean get() = frameCount > 1
}

/**
 * Parses WebP containers (still and animated) without decoding pixels. Used by unit tests and by the
 * export self-check. Structural problems raise IllegalArgumentException with the offending offset.
 */
object WebpInspector {

    /** Reads canvas, frame timing, alpha chunks and loop count from [bytes]. */
    fun inspect(bytes: ByteArray): WebpInfo {
        val chunks = walkChunks(bytes, 12, riffEnd(bytes))
        var canvasWidth = 0
        var canvasHeight = 0
        var loopCount = 0
        var hasAlpha = false
        val durations = ArrayList<Int>()
        var stillSize: Pair<Int, Int>? = null
        for (chunk in chunks) {
            when (chunk.fourcc) {
                "VP8X" -> {
                    require(chunk.payloadSize >= 10) { "VP8X chunk too short" }
                    canvasWidth = WebpBytes.u24le(bytes, chunk.payloadAt + 4) + 1
                    canvasHeight = WebpBytes.u24le(bytes, chunk.payloadAt + 7) + 1
                }
                "ANIM" -> {
                    require(chunk.payloadSize >= 6) { "ANIM chunk too short" }
                    loopCount = WebpBytes.u16le(bytes, chunk.payloadAt + 4)
                }
                "ANMF" -> durations += inspectFrame(bytes, chunk).also { if (it.hasAlpha) hasAlpha = true }.durationMs
                "ALPH" -> hasAlpha = true
                "VP8 ", "VP8L" -> stillSize = StaticWebp.bitstreamSize(bytes, chunk)
                else -> Unit
            }
        }
        if (durations.isEmpty()) {
            val (w, h) = requireNotNull(stillSize) { "No image frames found" }
            if (canvasWidth == 0) canvasWidth = w
            if (canvasHeight == 0) canvasHeight = h
            return WebpInfo(canvasWidth, canvasHeight, 1, listOf(0), hasAlpha, loopCount)
        }
        return WebpInfo(canvasWidth, canvasHeight, durations.size, durations.toList(), hasAlpha, loopCount)
    }

    private class FrameFacts(val durationMs: Int, val hasAlpha: Boolean)

    /** Reads one ANMF payload: a 16-byte header, then ALPH? and VP8/VP8L sub-chunks. */
    private fun inspectFrame(bytes: ByteArray, anmf: RiffChunk): FrameFacts {
        require(anmf.payloadSize >= 16 + 10) { "ANMF chunk at offset ${anmf.headerAt} is too short" }
        val p = anmf.payloadAt
        val width = WebpBytes.u24le(bytes, p + 6) + 1
        val height = WebpBytes.u24le(bytes, p + 9) + 1
        val duration = WebpBytes.u24le(bytes, p + 12)
        var hasAlpha = false
        var image = false
        for (sub in walkChunks(bytes, p + 16, anmf.payloadAt + anmf.payloadSize)) {
            when (sub.fourcc) {
                "ALPH" -> hasAlpha = true
                "VP8 ", "VP8L" -> {
                    require(!image) { "ANMF at offset ${anmf.headerAt} holds two image bitstreams" }
                    image = true
                    val (w, h) = StaticWebp.bitstreamSize(bytes, sub)
                    require(w == width && h == height) {
                        "ANMF at offset ${anmf.headerAt} declares ${width}x$height but its bitstream is ${w}x$h"
                    }
                }
                else -> Unit
            }
        }
        require(image) { "ANMF at offset ${anmf.headerAt} has no image bitstream" }
        return FrameFacts(duration, hasAlpha)
    }
}
