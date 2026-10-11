package com.agency.photoduel.engine.pure

/** Why [JpegMetadata.strip] refused an input. */
enum class JpegRejection {
    /** Zero bytes. */
    EMPTY,

    /** Does not start with the JPEG signature FF D8 FF. */
    NOT_A_JPEG,

    /** The data ends inside a segment, inside the entropy-coded data, or before the End Of Image marker. */
    TRUNCATED,

    /** Marker structure that no JPEG can have (stray bytes between segments, a segment length under 2). */
    MALFORMED,

    /** Well-formed markers but no frame header or no scan, so there is no image to keep. */
    NO_IMAGE_DATA,
}

/** Why a segment was left out of the copy. */
enum class RemovalReason {
    /** APP1: EXIF or XMP. */
    EXIF_OR_XMP,

    /** APP13: IPTC and Photoshop resources. */
    IPTC_PHOTOSHOP,

    /** COM: free-text comment. */
    COMMENT,

    /** Any other application segment that contains a GPS IFD pointer or XMP GPS properties. */
    GPS_IFD,

    /** APP2 Multi-Picture Format index. It points at images after the End Of Image marker, which are dropped. */
    MULTI_PICTURE_INDEX,
}

/**
 * One removed segment. [marker] is the second marker byte (0xE1 for APP1), [totalBytes] counts the
 * marker, the length field and the payload.
 */
data class RemovedSegment(val marker: Int, val totalBytes: Int, val reason: RemovalReason)

sealed interface JpegStripResult {
    /**
     * @property bytes a new array holding the cleaned JPEG.
     * @property containedGps true when the input held a GPS IFD pointer in any application segment,
     *   or XMP GPS properties, whether or not that segment was one that is always removed.
     * @property removedSegments the segments left out, in file order.
     * @property trailingBytesDropped bytes found after the End Of Image marker. They are never part
     *   of the image and may hold appended data such as extra photos or video, so they are dropped.
     */
    class Stripped(
        val bytes: ByteArray,
        val containedGps: Boolean,
        val removedSegments: List<RemovedSegment>,
        val trailingBytesDropped: Int,
    ) : JpegStripResult

    data class Rejected(val reason: JpegRejection) : JpegStripResult
}

/**
 * Pure JVM JPEG segment reader and writer that copies a JPEG without its metadata.
 *
 * Removed: APP1 (EXIF, XMP), APP13 (IPTC, Photoshop), COM, any other application segment that
 * contains a GPS IFD, the APP2 Multi-Picture index, and everything after the End Of Image marker.
 *
 * Kept byte for byte: SOI, EOI, every frame header (SOFn), Huffman table (DHT), quantization table
 * (DQT), restart interval (DRI), scan header (SOS), and all entropy-coded data. No compressed byte is
 * decoded, re-encoded or moved within its segment. Other application segments (JFIF in APP0, an ICC
 * profile in APP2, Adobe in APP14) are kept, because decoders need them to show the right colours.
 *
 * The cost is linear in the input size: one pass finds the segments, one pass copies the kept ranges.
 *
 * Note for the Android pipeline: the pipeline re-encodes the bitmap, which already writes a file with
 * no metadata. This class is a second line of defence on the encoder output and the tool for any code
 * path that copies JPEG bytes instead of re-encoding them.
 */
object JpegMetadata {
    private const val FF = 0xFF
    private const val SOI = 0xD8
    private const val EOI = 0xD9
    private const val SOS = 0xDA
    private const val TEM = 0x01
    private const val APP0 = 0xE0
    private const val APP1 = 0xE1
    private const val APP2 = 0xE2
    private const val APP13 = 0xED
    private const val APP15 = 0xEF
    private const val COM = 0xFE

    private const val TIFF_GPS_INFO_TAG = 0x8825
    private const val TIFF_EXIF_IFD_TAG = 0x8769
    private const val TIFF_INTEROP_IFD_TAG = 0xA005
    private const val MAX_IFDS_PER_TIFF = 16
    private const val MAX_TIFF_CANDIDATES_PER_SEGMENT = 16

    private val XMP_GPS_LATITUDE = "GPSLatitude".toByteArray(Charsets.US_ASCII)
    private val XMP_GPS_LONGITUDE = "GPSLongitude".toByteArray(Charsets.US_ASCII)
    private val MPF_IDENTIFIER = byteArrayOf(0x4D, 0x50, 0x46, 0x00)

    /** Copies [input] without metadata, or says why it is not a usable JPEG. */
    fun strip(input: ByteArray): JpegStripResult {
        val n = input.size
        if (n == 0) return JpegStripResult.Rejected(JpegRejection.EMPTY)
        if (n < 3 || input.u8(0) != FF || input.u8(1) != SOI || input.u8(2) != FF) {
            return JpegStripResult.Rejected(JpegRejection.NOT_A_JPEG)
        }

        val runs = IntRuns()
        var runStart = 0
        val removed = ArrayList<RemovedSegment>()
        var containedGps = false
        var sawFrame = false
        var sawScan = false
        var imageEnd = -1
        var pos = 2

        while (imageEnd < 0) {
            if (pos >= n) return JpegStripResult.Rejected(JpegRejection.TRUNCATED)
            if (input.u8(pos) != FF) return JpegStripResult.Rejected(JpegRejection.MALFORMED)
            var markerIndex = pos + 1
            while (markerIndex < n && input.u8(markerIndex) == FF) markerIndex++ // fill bytes
            if (markerIndex >= n) return JpegStripResult.Rejected(JpegRejection.TRUNCATED)
            val marker = input.u8(markerIndex)
            val segmentStart = markerIndex - 1

            when {
                marker == 0x00 || marker == SOI -> return JpegStripResult.Rejected(JpegRejection.MALFORMED)
                marker == EOI -> imageEnd = markerIndex + 1
                marker == TEM || marker in 0xD0..0xD7 -> pos = markerIndex + 1
                else -> {
                    if (markerIndex + 2 >= n) return JpegStripResult.Rejected(JpegRejection.TRUNCATED)
                    val length = (input.u8(markerIndex + 1) shl 8) or input.u8(markerIndex + 2)
                    if (length < 2) return JpegStripResult.Rejected(JpegRejection.MALFORMED)
                    val segmentEnd = markerIndex + 1 + length
                    if (segmentEnd > n) return JpegStripResult.Rejected(JpegRejection.TRUNCATED)
                    val payloadStart = markerIndex + 3

                    val isApp = marker in APP0..APP15
                    val hasGps = isApp && payloadHasGps(input, payloadStart, segmentEnd)
                    if (hasGps) containedGps = true
                    val reason = removalReason(input, marker, hasGps, payloadStart, segmentEnd)
                    if (reason != null) {
                        removed += RemovedSegment(marker, segmentEnd - segmentStart, reason)
                        runs.add(runStart, segmentStart)
                        runStart = segmentEnd
                    }

                    if (isStartOfFrame(marker)) sawFrame = true
                    if (marker == SOS) {
                        if (!sawFrame) return JpegStripResult.Rejected(JpegRejection.MALFORMED)
                        sawScan = true
                        val next = findEndOfEntropyData(input, segmentEnd)
                        if (next < 0) return JpegStripResult.Rejected(JpegRejection.TRUNCATED)
                        pos = next
                    } else {
                        pos = segmentEnd
                    }
                }
            }
        }

        if (!sawFrame || !sawScan) return JpegStripResult.Rejected(JpegRejection.NO_IMAGE_DATA)

        runs.add(runStart, imageEnd)
        val output = ByteArray(runs.totalLength())
        var written = 0
        for (i in 0 until runs.size) {
            val length = runs.end(i) - runs.start(i)
            System.arraycopy(input, runs.start(i), output, written, length)
            written += length
        }
        return JpegStripResult.Stripped(
            bytes = output,
            containedGps = containedGps,
            removedSegments = removed,
            trailingBytesDropped = n - imageEnd,
        )
    }

    private fun removalReason(input: ByteArray, marker: Int, hasGps: Boolean, payloadStart: Int, payloadEnd: Int): RemovalReason? = when {
        marker == APP1 -> RemovalReason.EXIF_OR_XMP
        marker == APP13 -> RemovalReason.IPTC_PHOTOSHOP
        marker == COM -> RemovalReason.COMMENT
        hasGps -> RemovalReason.GPS_IFD
        marker == APP2 && startsWith(input, payloadStart, payloadEnd, MPF_IDENTIFIER) -> RemovalReason.MULTI_PICTURE_INDEX
        else -> null
    }

    /** SOF0..SOF15 except DHT (C4), JPG (C8) and DAC (CC). */
    private fun isStartOfFrame(marker: Int): Boolean =
        marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC

    /**
     * Returns the index of the 0xFF that starts the first real marker at or after [from], skipping
     * stuffed bytes (FF 00) and restart markers (FF D0..D7), or -1 when the data ends first. Every
     * byte before the returned index is entropy-coded data (or fill) and is copied untouched.
     */
    private fun findEndOfEntropyData(input: ByteArray, from: Int): Int {
        val n = input.size
        var i = from
        while (i < n) {
            if (input[i] != 0xFF.toByte()) {
                i++
                continue
            }
            var j = i + 1
            while (j < n && input[j] == 0xFF.toByte()) j++
            if (j >= n) return -1
            val next = input.u8(j)
            if (next == 0x00 || next in 0xD0..0xD7) {
                i = j + 1
            } else {
                return j - 1
            }
        }
        return -1
    }

    // ---- GPS detection -------------------------------------------------------------------------

    /**
     * True when the application segment payload [start, end) holds a TIFF structure whose IFD0,
     * EXIF IFD or interoperability IFD has a GPS IFD pointer (tag 0x8825), or XMP text with GPS
     * latitude or longitude properties.
     */
    private fun payloadHasGps(data: ByteArray, start: Int, end: Int): Boolean {
        if (indexOf(data, XMP_GPS_LATITUDE, start, end) >= 0 || indexOf(data, XMP_GPS_LONGITUDE, start, end) >= 0) return true
        var candidates = 0
        var i = start
        while (i + 8 <= end && candidates < MAX_TIFF_CANDIDATES_PER_SEGMENT) {
            if (looksLikeTiffHeader(data, i)) {
                candidates++
                if (tiffHasGpsPointer(data, i, end)) return true
            }
            i++
        }
        return false
    }

    private fun looksLikeTiffHeader(d: ByteArray, i: Int): Boolean {
        val b0 = d.u8(i)
        val b1 = d.u8(i + 1)
        return when {
            b0 == 0x49 && b1 == 0x49 -> d.u8(i + 2) == 0x2A && d.u8(i + 3) == 0x00
            b0 == 0x4D && b1 == 0x4D -> d.u8(i + 2) == 0x00 && d.u8(i + 3) == 0x2A
            else -> false
        }
    }

    private fun tiffHasGpsPointer(d: ByteArray, base: Int, limit: Int): Boolean {
        val little = d.u8(base) == 0x49
        fun u16(at: Int): Int =
            if (little) d.u8(at) or (d.u8(at + 1) shl 8) else (d.u8(at) shl 8) or d.u8(at + 1)
        fun u32(at: Int): Long =
            if (little) {
                (d.u8(at).toLong()) or (d.u8(at + 1).toLong() shl 8) or (d.u8(at + 2).toLong() shl 16) or (d.u8(at + 3).toLong() shl 24)
            } else {
                (d.u8(at).toLong() shl 24) or (d.u8(at + 1).toLong() shl 16) or (d.u8(at + 2).toLong() shl 8) or d.u8(at + 3).toLong()
            }

        val pending = LongArray(MAX_IFDS_PER_TIFF * 2)
        var pendingCount = 0
        val seen = LongArray(MAX_IFDS_PER_TIFF)
        var seenCount = 0
        fun enqueue(offset: Long) {
            if (offset < 8L || base + offset + 2 > limit || pendingCount >= pending.size) return
            pending[pendingCount++] = offset
        }

        enqueue(u32(base + 4))
        while (pendingCount > 0 && seenCount < MAX_IFDS_PER_TIFF) {
            val offset = pending[--pendingCount]
            var alreadySeen = false
            for (k in 0 until seenCount) if (seen[k] == offset) alreadySeen = true
            if (alreadySeen) continue
            seen[seenCount++] = offset

            val ifd = base + offset.toInt()
            val declared = u16(ifd)
            val fitting = (limit - ifd - 2) / 12
            val entries = minOf(declared, fitting)
            for (e in 0 until entries) {
                val entry = ifd + 2 + 12 * e
                when (u16(entry)) {
                    TIFF_GPS_INFO_TAG -> return true
                    TIFF_EXIF_IFD_TAG, TIFF_INTEROP_IFD_TAG -> enqueue(u32(entry + 8))
                }
            }
            val nextPointer = ifd + 2 + 12 * declared
            if (declared == entries && nextPointer + 4 <= limit) enqueue(u32(nextPointer))
        }
        return false
    }

    private fun startsWith(data: ByteArray, start: Int, end: Int, prefix: ByteArray): Boolean {
        if (end - start < prefix.size) return false
        for (i in prefix.indices) if (data[start + i] != prefix[i]) return false
        return true
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray, start: Int, end: Int): Int {
        val last = end - pattern.size
        var i = start
        while (i <= last) {
            if (data[i] == pattern[0]) {
                var k = 1
                while (k < pattern.size && data[i + k] == pattern[k]) k++
                if (k == pattern.size) return i
            }
            i++
        }
        return -1
    }

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

    /** Growable list of [start, end) ranges to copy, kept in two parallel int arrays. */
    private class IntRuns {
        private var starts = IntArray(8)
        private var ends = IntArray(8)
        var size = 0
            private set

        fun add(start: Int, end: Int) {
            if (end <= start) return
            if (size == starts.size) {
                starts = starts.copyOf(size * 2)
                ends = ends.copyOf(size * 2)
            }
            starts[size] = start
            ends[size] = end
            size++
        }

        fun start(i: Int): Int = starts[i]
        fun end(i: Int): Int = ends[i]

        fun totalLength(): Int {
            var total = 0
            for (i in 0 until size) total += ends[i] - starts[i]
            return total
        }
    }
}
