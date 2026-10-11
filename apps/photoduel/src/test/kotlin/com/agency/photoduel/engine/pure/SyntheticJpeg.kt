package com.agency.photoduel.engine.pure

import java.io.ByteArrayOutputStream
import java.util.Random

/**
 * Builds JPEG byte arrays segment by segment for tests. No binary fixture files are used: every byte
 * of every test input is constructed here, so a reader can see exactly what the parser is given.
 *
 * The segments are structurally valid (marker, length, payload) but not decodable images; the
 * metadata code never decodes pixels.
 */
internal object SyntheticJpeg {
    fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (part in parts) out.write(part)
        return out.toByteArray()
    }

    val soi: ByteArray = bytes(0xFF, 0xD8)
    val eoi: ByteArray = bytes(0xFF, 0xD9)

    /** A length-prefixed segment: FF, marker, 16-bit length (payload + 2), payload. */
    fun segment(marker: Int, payload: ByteArray): ByteArray {
        require(payload.size + 2 <= 0xFFFF) { "payload too long" }
        val length = payload.size + 2
        return concat(bytes(0xFF, marker, length shr 8, length and 0xFF), payload)
    }

    fun app0Jfif(): ByteArray =
        segment(0xE0, concat("JFIF".toByteArray(), bytes(0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00)))

    fun dqt(): ByteArray = segment(0xDB, concat(bytes(0x00), ByteArray(64) { 16 }))

    fun sof0(width: Int = 1600, height: Int = 1200): ByteArray =
        segment(0xC0, bytes(8, height shr 8, height and 0xFF, width shr 8, width and 0xFF, 1, 1, 0x11, 0))

    fun dht(): ByteArray {
        val counts = ByteArray(16).also { it[1] = 1 }
        return segment(0xC4, concat(bytes(0x00), counts, bytes(0x00)))
    }

    fun sosHeader(): ByteArray = segment(0xDA, bytes(1, 1, 0x00, 0, 63, 0))

    fun com(text: String): ByteArray = segment(0xFE, text.toByteArray(Charsets.US_ASCII))

    fun app13Photoshop(): ByteArray =
        segment(0xED, concat("Photoshop 3.0".toByteArray(), bytes(0x00), "8BIM".toByteArray(), bytes(0x04, 0x04, 0, 0, 0, 0, 0, 4, 1, 2, 3, 4)))

    fun app2Icc(): ByteArray =
        segment(0xE2, concat("ICC_PROFILE".toByteArray(), bytes(0x00, 0x01, 0x01), ByteArray(40) { (it * 3).toByte() }))

    fun app2Mpf(): ByteArray =
        segment(0xE2, concat("MPF".toByteArray(), bytes(0x00), ByteArray(24) { (it + 1).toByte() }))

    fun app14Adobe(): ByteArray =
        segment(0xEE, concat("Adobe".toByteArray(), bytes(0x00, 0x64, 0x00, 0x00, 0x00, 0x00, 0x01)))

    fun app1Exif(layout: TiffLayout, bigEndian: Boolean = false): ByteArray =
        segment(0xE1, concat("Exif".toByteArray(), bytes(0x00, 0x00), tiff(layout, bigEndian)))

    /** A vendor-style "Meta" segment (APP3) that carries a TIFF block, as some cameras write. */
    fun app3Meta(layout: TiffLayout): ByteArray =
        segment(0xE3, concat("Meta".toByteArray(), bytes(0x00, 0x00), tiff(layout, bigEndian = false)))

    fun app1Xmp(withGps: Boolean): ByteArray {
        val body = buildString {
            append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF><rdf:Description exif:Make=\"Test\"")
            if (withGps) append(" exif:GPSLatitude=\"52,30.5N\" exif:GPSLongitude=\"13,24.1E\"")
            append("/></rdf:RDF></x:xmpmeta>")
        }
        return segment(0xE1, concat("http://ns.adobe.com/xap/1.0/".toByteArray(), bytes(0x00), body.toByteArray()))
    }

    /** Where the GPS IFD pointer (tag 0x8825) sits in the TIFF block, if anywhere. */
    enum class TiffLayout { NO_GPS, GPS_IN_IFD0, GPS_IN_IFD1, GPS_IN_EXIF_SUB_IFD }

    fun tiff(layout: TiffLayout, bigEndian: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) {
            if (bigEndian) {
                out.write(v shr 8); out.write(v and 0xFF)
            } else {
                out.write(v and 0xFF); out.write(v shr 8)
            }
        }
        fun u32(v: Int) {
            if (bigEndian) {
                out.write(v ushr 24); out.write((v ushr 16) and 0xFF); out.write((v ushr 8) and 0xFF); out.write(v and 0xFF)
            } else {
                out.write(v and 0xFF); out.write((v ushr 8) and 0xFF); out.write((v ushr 16) and 0xFF); out.write(v ushr 24)
            }
        }
        fun entryShort(tag: Int, value: Int) {
            u16(tag); u16(3); u32(1); u16(value); u16(0)
        }
        fun entryLong(tag: Int, value: Int) {
            u16(tag); u16(4); u32(1); u32(value)
        }
        fun gpsIfd() {
            u16(1)
            u16(0x0000); u16(1); u32(4); out.write(2); out.write(3); out.write(0); out.write(0) // GPSVersionID 2.3.0.0
            u32(0)
        }

        out.write(if (bigEndian) 'M'.code else 'I'.code)
        out.write(if (bigEndian) 'M'.code else 'I'.code)
        u16(0x002A)
        u32(8)
        when (layout) {
            TiffLayout.NO_GPS -> {
                u16(1); entryShort(0x0112, 1); u32(0)
            }
            TiffLayout.GPS_IN_IFD0 -> {
                // IFD0 at 8: 2 + 2*12 + 4 = 30 bytes, so the GPS IFD starts at 38.
                u16(2); entryShort(0x0112, 1); entryLong(0x8825, 38); u32(0)
                gpsIfd()
            }
            TiffLayout.GPS_IN_IFD1 -> {
                // IFD0 at 8 is 2 + 12 + 4 = 18 bytes, so IFD1 starts at 26. IFD1 is 18 bytes, GPS IFD at 44.
                u16(1); entryShort(0x0112, 1); u32(26)
                u16(1); entryLong(0x8825, 44); u32(0)
                gpsIfd()
            }
            TiffLayout.GPS_IN_EXIF_SUB_IFD -> {
                // IFD0 holds only the Exif IFD pointer (to 26); the Exif IFD holds the GPS pointer (to 44).
                u16(1); entryLong(0x8769, 26); u32(0)
                u16(1); entryLong(0x8825, 44); u32(0)
                gpsIfd()
            }
        }
        return out.toByteArray()
    }

    /** Entropy-coded bytes with a stuffed FF 00, a restart marker FF D0 and a trailing stuffed byte. */
    fun smallEntropy(): ByteArray =
        bytes(0x12, 0x34, 0xFF, 0x00, 0x56, 0xFF, 0xD0, 0x78, 0x9A, 0xFF, 0x00)

    /** Random entropy-coded data: every 0xFF data byte is followed by a 0x00 stuffing byte. */
    fun randomEntropy(random: Random, dataBytes: Int, restartEvery: Int = 0): ByteArray {
        val out = ByteArrayOutputStream(dataBytes + dataBytes / 64)
        var untilRestart = restartEvery
        var restartIndex = 0
        for (i in 0 until dataBytes) {
            val b = random.nextInt(256)
            out.write(b)
            if (b == 0xFF) out.write(0x00)
            if (restartEvery > 0 && --untilRestart == 0) {
                out.write(0xFF); out.write(0xD0 + (restartIndex++ and 7))
                untilRestart = restartEvery
            }
        }
        return out.toByteArray()
    }
}
