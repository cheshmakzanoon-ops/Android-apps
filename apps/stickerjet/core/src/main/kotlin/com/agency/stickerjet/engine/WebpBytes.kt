package com.agency.stickerjet.engine

import java.io.ByteArrayOutputStream

/** Little-endian readers used by the WebP container code. All offsets are absolute into the array. */
internal object WebpBytes {
    fun u8(b: ByteArray, at: Int): Int = b[at].toInt() and 0xFF

    fun u16le(b: ByteArray, at: Int): Int = u8(b, at) or (u8(b, at + 1) shl 8)

    fun u24le(b: ByteArray, at: Int): Int = u16le(b, at) or (u8(b, at + 2) shl 16)

    fun u32le(b: ByteArray, at: Int): Long =
        u16le(b, at).toLong() or (u16le(b, at + 2).toLong() shl 16)

    fun fourcc(b: ByteArray, at: Int): String = String(b, at, 4, Charsets.US_ASCII)
}

/**
 * One RIFF chunk. [headerAt] is the offset of the FourCC, [payloadAt] the first payload byte and
 * [payloadSize] the size written in the header, without the pad byte that follows an odd payload.
 */
internal class RiffChunk(
    val fourcc: String,
    val headerAt: Int,
    val payloadAt: Int,
    val payloadSize: Int,
) {
    /** Offset just past the pad byte, which is only present when [payloadSize] is odd. */
    val endAt: Int get() = payloadAt + payloadSize + (payloadSize and 1)
}

/** Validates the RIFF/WEBP header and returns the exclusive end of the RIFF payload. */
internal fun riffEnd(bytes: ByteArray): Int {
    require(bytes.size >= 12) { "Too short to be a RIFF file: ${bytes.size} bytes" }
    require(WebpBytes.fourcc(bytes, 0) == "RIFF" && WebpBytes.fourcc(bytes, 8) == "WEBP") {
        "Not a RIFF/WEBP file"
    }
    val declared = WebpBytes.u32le(bytes, 4) + 8
    require(declared in 12..bytes.size.toLong()) {
        "RIFF size declares $declared bytes but the file has ${bytes.size}"
    }
    return declared.toInt()
}

/** Lists the chunks in [start, end). Throws when a header or payload runs past [end]. */
internal fun walkChunks(bytes: ByteArray, start: Int, end: Int): List<RiffChunk> {
    val chunks = ArrayList<RiffChunk>()
    var at = start
    while (at < end) {
        require(end - at >= 8) { "Truncated chunk header at offset $at" }
        val fourcc = WebpBytes.fourcc(bytes, at)
        val size = WebpBytes.u32le(bytes, at + 4)
        val payloadAt = at + 8
        require(size <= (end - payloadAt).toLong()) {
            "Chunk $fourcc at offset $at declares $size bytes but only ${end - payloadAt} remain"
        }
        val chunk = RiffChunk(fourcc, at, payloadAt, size.toInt())
        chunks += chunk
        at = minOf(chunk.endAt, end)
    }
    return chunks
}

/** Copies the payload of [chunk] out of [bytes]. */
internal fun payloadOf(bytes: ByteArray, chunk: RiffChunk): ByteArray =
    bytes.copyOfRange(chunk.payloadAt, chunk.payloadAt + chunk.payloadSize)

/** Little-endian byte builder for RIFF bodies. Every [chunk] call writes the pad byte itself. */
internal class ChunkWriter {
    private val out = ByteArrayOutputStream()

    fun u8(v: Int) = out.write(v and 0xFF)

    fun u16le(v: Int) {
        u8(v)
        u8(v shr 8)
    }

    fun u24le(v: Int) {
        u16le(v)
        u8(v shr 16)
    }

    fun u32le(v: Int) {
        u16le(v)
        u16le(v shr 16)
    }

    fun bytes(b: ByteArray) = out.write(b, 0, b.size)

    /** Writes `FourCC`, the 32-bit payload size, the payload and a zero pad byte when the size is odd. */
    fun chunk(fourcc: String, payload: ByteArray) {
        require(fourcc.length == 4 && fourcc.all { it.code in 0x20..0x7E }) { "Bad FourCC '$fourcc'" }
        bytes(fourcc.toByteArray(Charsets.US_ASCII))
        u32le(payload.size)
        bytes(payload)
        if ((payload.size and 1) == 1) u8(0)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Wraps a RIFF body (everything after `WEBP`) into a complete `RIFF` file with an exact size. */
internal fun riffWebp(body: ByteArray): ByteArray {
    val writer = ChunkWriter()
    writer.bytes("RIFF".toByteArray(Charsets.US_ASCII))
    writer.u32le(4 + body.size)
    writer.bytes("WEBP".toByteArray(Charsets.US_ASCII))
    writer.bytes(body)
    return writer.toByteArray()
}
