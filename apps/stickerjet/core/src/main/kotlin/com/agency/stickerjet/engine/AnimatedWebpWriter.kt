package com.agency.stickerjet.engine

/**
 * One complete single-image WebP (VP8L, or ALPH plus VP8) and how long it stays on screen.
 * [durationMs] is written as a 24-bit value, so it must be 1..[MAX_DURATION_MS].
 */
data class WebpFrame(val staticWebp: ByteArray, val durationMs: Int) {
    init {
        require(durationMs in 1..MAX_DURATION_MS) {
            "Frame duration must be 1..$MAX_DURATION_MS ms, was $durationMs"
        }
    }

    companion object {
        const val MAX_DURATION_MS: Int = 0xFFFFFF
    }
}

/**
 * Muxes complete static WebP frames into one animated WebP that follows the WebP container spec.
 *
 * Layout for two or more frames (sizes are exact; odd chunks are padded with a zero byte):
 * ```
 * RIFF <size> "WEBP"
 *   "VP8X" 10 bytes  flags = 0x12 (animation 0x02 | alpha 0x10); canvas width-1, height-1 (24-bit LE)
 *   "ANIM"  6 bytes  background 0x00000000 (transparent, BGRA order); loop count (16-bit LE)
 *   "ANMF"  per frame: X/2 and Y/2 = 0; width-1 and height-1 = canvas; duration (24-bit LE);
 *                      flags = 0x02 (do not blend, dispose none); then the frame's ALPH? + VP8/VP8L chunks
 * ```
 * Every frame is a full canvas, so no-blend and dispose-none are exact. A single frame is written as a
 * still image: VP8X with the alpha flag and no animation flag, followed by the image chunks. A file is
 * therefore never labelled animated when it holds one frame.
 *
 * Frames must all be [canvasPx] by [canvasPx]. The writer copies chunks verbatim, so a frame encoded by
 * Bitmap.compress and a frame from Pillow are handled the same way.
 */
class AnimatedWebpWriter(private val canvasPx: Int) {
    init {
        require(canvasPx in 1..MAX_CANVAS_PX) { "Canvas must be 1..$MAX_CANVAS_PX px, was $canvasPx" }
    }

    /** Returns the complete file. [loopCount] 0 means loop forever. */
    fun write(frames: List<WebpFrame>, loopCount: Int = 0): ByteArray {
        require(frames.isNotEmpty()) { "At least one frame is required" }
        require(loopCount in 0..0xFFFF) { "Loop count must be 0..65535, was $loopCount" }
        val images = frames.mapIndexed { index, frame ->
            val image = StaticWebp.parse(frame.staticWebp)
            require(image.width == canvasPx && image.height == canvasPx) {
                "Frame $index is ${image.width}x${image.height}, expected ${canvasPx}x$canvasPx"
            }
            image
        }
        val body = ChunkWriter()
        if (images.size == 1) {
            body.chunk(FOURCC_VP8X, vp8xPayload(animated = false))
            body.bytes(images[0].imageChunks)
        } else {
            body.chunk(FOURCC_VP8X, vp8xPayload(animated = true))
            body.chunk(FOURCC_ANIM, animPayload(loopCount))
            for ((index, image) in images.withIndex()) {
                body.chunk(FOURCC_ANMF, anmfPayload(image, frames[index].durationMs))
            }
        }
        return riffWebp(body.toByteArray())
    }

    private fun vp8xPayload(animated: Boolean): ByteArray {
        val w = ChunkWriter()
        w.u8(if (animated) FLAG_ANIMATION or FLAG_ALPHA else FLAG_ALPHA)
        w.u24le(0)
        w.u24le(canvasPx - 1)
        w.u24le(canvasPx - 1)
        return w.toByteArray()
    }

    private fun animPayload(loopCount: Int): ByteArray {
        val w = ChunkWriter()
        w.u32le(0)
        w.u16le(loopCount)
        return w.toByteArray()
    }

    private fun anmfPayload(image: StaticImage, durationMs: Int): ByteArray {
        val w = ChunkWriter()
        w.u24le(0)
        w.u24le(0)
        w.u24le(canvasPx - 1)
        w.u24le(canvasPx - 1)
        w.u24le(durationMs)
        w.u8(ANMF_NO_BLEND)
        w.bytes(image.imageChunks)
        return w.toByteArray()
    }

    companion object {
        /** Largest canvas side a VP8 or VP8L frame can carry (14-bit dimensions). */
        const val MAX_CANVAS_PX: Int = 16_383

        private const val FLAG_ANIMATION = 0x02
        private const val FLAG_ALPHA = 0x10
        private const val ANMF_NO_BLEND = 0x02

        private const val FOURCC_VP8X = "VP8X"
        private const val FOURCC_ANIM = "ANIM"
        private const val FOURCC_ANMF = "ANMF"
    }
}
