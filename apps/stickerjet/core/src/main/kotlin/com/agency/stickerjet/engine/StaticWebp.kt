package com.agency.stickerjet.engine

/**
 * The part of one complete single-image WebP that an animation frame needs: its size, whether it
 * carries an `ALPH` chunk, and the image chunks copied verbatim with their headers and pad bytes.
 * [imageChunks] is `ALPH` (only for `VP8 `) followed by `VP8 ` or `VP8L`.
 */
internal class StaticImage(
    val width: Int,
    val height: Int,
    val hasAlpha: Boolean,
    val imageChunks: ByteArray,
)

/**
 * Reads a static WebP produced by Bitmap.compress (or Pillow). `VP8X`, metadata (`ICCP`, `EXIF`, `XMP `)
 * and unknown chunks are dropped. Animated input is rejected because it cannot be a frame.
 */
internal object StaticWebp {

    fun parse(bytes: ByteArray): StaticImage {
        val chunks = walkChunks(bytes, 12, riffEnd(bytes))
        var alph: RiffChunk? = null
        var image: RiffChunk? = null
        for (chunk in chunks) {
            when (chunk.fourcc) {
                "ANIM", "ANMF" -> throw IllegalArgumentException("Frame input is already animated")
                "ALPH" -> alph = alph ?: chunk
                "VP8 ", "VP8L" -> {
                    require(image == null) { "Frame input contains more than one image bitstream" }
                    image = chunk
                }
                else -> Unit
            }
        }
        val picture = requireNotNull(image) { "Frame input has no VP8 or VP8L image chunk" }
        val (width, height) = bitstreamSize(bytes, picture)
        val useAlph = picture.fourcc == "VP8 " && alph != null
        val out = ChunkWriter()
        if (useAlph) {
            val alphaChunk = requireNotNull(alph)
            out.chunk("ALPH", payloadOf(bytes, alphaChunk))
        }
        out.chunk(picture.fourcc, payloadOf(bytes, picture))
        return StaticImage(width, height, hasAlpha = useAlph, imageChunks = out.toByteArray())
    }

    /**
     * Frame size from the bitstream header. VP8L: 0x2f signature, then 14-bit width-1 and height-1.
     * VP8 keyframe: 3-byte frame tag, start code 9d 01 2a, then 14-bit width and height.
     */
    fun bitstreamSize(bytes: ByteArray, chunk: RiffChunk): Pair<Int, Int> {
        val p = chunk.payloadAt
        return when (chunk.fourcc) {
            "VP8L" -> {
                require(chunk.payloadSize >= 5 && WebpBytes.u8(bytes, p) == 0x2F) { "Bad VP8L header" }
                val bits = WebpBytes.u32le(bytes, p + 1)
                val width = (bits and 0x3FFF).toInt() + 1
                val height = ((bits ushr 14) and 0x3FFF).toInt() + 1
                width to height
            }
            "VP8 " -> {
                require(chunk.payloadSize >= 10) { "VP8 chunk too short" }
                require((WebpBytes.u8(bytes, p) and 0x01) == 0) { "VP8 chunk is not a keyframe" }
                require(
                    WebpBytes.u8(bytes, p + 3) == 0x9D && WebpBytes.u8(bytes, p + 4) == 0x01 &&
                        WebpBytes.u8(bytes, p + 5) == 0x2A,
                ) { "VP8 start code missing" }
                val width = WebpBytes.u16le(bytes, p + 6) and 0x3FFF
                val height = WebpBytes.u16le(bytes, p + 8) and 0x3FFF
                width to height
            }
            else -> throw IllegalArgumentException("Not an image chunk: ${chunk.fourcc}")
        }
    }
}
