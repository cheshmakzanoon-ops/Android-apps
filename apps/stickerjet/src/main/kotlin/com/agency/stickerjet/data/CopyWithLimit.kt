package com.agency.stickerjet.data

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** Outcome of [copyWithLimit]. */
internal sealed interface CopyOutcome {
    /** The whole input was copied. [bytes] is its length, never above the limit. */
    data class Copied(val bytes: Long) : CopyOutcome

    /** The input is longer than the limit. The partial file has been deleted. */
    data object TooLarge : CopyOutcome
}

private const val BUFFER_BYTES: Int = 64 * 1024

/**
 * Copies [input] to [dest] and stops as soon as more than [maxBytes] bytes have arrived. An input of
 * exactly [maxBytes] is accepted. An oversize input deletes the partial file and returns
 * [CopyOutcome.TooLarge]. A read or write error deletes the partial file and rethrows. A successful
 * copy is flushed to the disk before the function returns.
 */
internal fun copyWithLimit(input: InputStream, dest: File, maxBytes: Long): CopyOutcome {
    require(maxBytes >= 0) { "maxBytes must not be negative" }
    var copied = 0L
    var tooLarge = false
    try {
        FileOutputStream(dest).use { out ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                // Ask for at most one byte past the limit, so an oversize input is noticed without copying it.
                val allowed = minOf(buffer.size.toLong(), maxBytes - copied + 1).toInt()
                val read = input.read(buffer, 0, allowed)
                if (read < 0) break
                copied += read
                if (copied > maxBytes) {
                    tooLarge = true
                    break
                }
                out.write(buffer, 0, read)
            }
            out.flush()
            out.fd.sync()
        }
    } catch (error: Throwable) {
        dest.delete()
        throw error
    }
    if (tooLarge) {
        dest.delete()
        return CopyOutcome.TooLarge
    }
    return CopyOutcome.Copied(copied)
}
