package com.agency.photoduel.engine.pure

import com.agency.photoduel.engine.pure.SyntheticJpeg.TiffLayout
import com.agency.photoduel.engine.pure.SyntheticJpeg.app0Jfif
import com.agency.photoduel.engine.pure.SyntheticJpeg.app13Photoshop
import com.agency.photoduel.engine.pure.SyntheticJpeg.app14Adobe
import com.agency.photoduel.engine.pure.SyntheticJpeg.app1Exif
import com.agency.photoduel.engine.pure.SyntheticJpeg.app1Xmp
import com.agency.photoduel.engine.pure.SyntheticJpeg.app2Icc
import com.agency.photoduel.engine.pure.SyntheticJpeg.app2Mpf
import com.agency.photoduel.engine.pure.SyntheticJpeg.app3Meta
import com.agency.photoduel.engine.pure.SyntheticJpeg.bytes
import com.agency.photoduel.engine.pure.SyntheticJpeg.com
import com.agency.photoduel.engine.pure.SyntheticJpeg.concat
import com.agency.photoduel.engine.pure.SyntheticJpeg.dht
import com.agency.photoduel.engine.pure.SyntheticJpeg.dqt
import com.agency.photoduel.engine.pure.SyntheticJpeg.eoi
import com.agency.photoduel.engine.pure.SyntheticJpeg.smallEntropy
import com.agency.photoduel.engine.pure.SyntheticJpeg.sof0
import com.agency.photoduel.engine.pure.SyntheticJpeg.soi
import com.agency.photoduel.engine.pure.SyntheticJpeg.sosHeader
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JpegMetadataTest {

    private fun stripped(input: ByteArray): JpegStripResult.Stripped {
        val result = JpegMetadata.strip(input)
        assertTrue("expected Stripped but was $result", result is JpegStripResult.Stripped)
        return result as JpegStripResult.Stripped
    }

    private fun rejected(input: ByteArray): JpegRejection {
        val result = JpegMetadata.strip(input)
        assertTrue("expected Rejected but was a successful strip", result is JpegStripResult.Rejected)
        return (result as JpegStripResult.Rejected).reason
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (k in needle.indices) if (haystack[i + k] != needle[k]) continue@outer
            return i
        }
        return -1
    }

    // ---- the main scenario: EXIF with GPS --------------------------------------------------------

    @Test
    fun removesExifWithGpsPointerAndKeepsEverythingElseByteForByte() {
        val entropy = smallEntropy()
        val image = concat(dqt(), sof0(), dht(), sosHeader(), entropy)
        val input = concat(
            soi, app0Jfif(), app1Exif(TiffLayout.GPS_IN_IFD0), app13Photoshop(), com("shot at home"), image, eoi,
        )
        val expected = concat(soi, app0Jfif(), image, eoi)

        val result = stripped(input)

        assertArrayEquals(expected, result.bytes)
        assertTrue("the input held a GPS IFD pointer", result.containedGps)
        assertEquals(
            listOf(RemovalReason.EXIF_OR_XMP, RemovalReason.IPTC_PHOTOSHOP, RemovalReason.COMMENT),
            result.removedSegments.map { it.reason },
        )
        assertEquals(listOf(0xE1, 0xED, 0xFE), result.removedSegments.map { it.marker })
        assertEquals(input.size - expected.size, result.removedSegments.sumOf { it.totalBytes })
        assertEquals(0, result.trailingBytesDropped)
    }

    @Test
    fun outputHasNoExifGpsOrCommentBytes() {
        val input = concat(
            soi, app1Exif(TiffLayout.GPS_IN_IFD0), com("secret place"), dqt(), sof0(), dht(), sosHeader(), smallEntropy(), eoi,
        )
        val output = stripped(input).bytes
        assertEquals("Exif marker text must be gone", -1, indexOf(output, "Exif".toByteArray()))
        assertEquals("comment text must be gone", -1, indexOf(output, "secret place".toByteArray()))
        assertEquals("no APP1 marker may remain", -1, indexOf(output, bytes(0xFF, 0xE1)))
        assertEquals("no COM marker may remain", -1, indexOf(output, bytes(0xFF, 0xFE)))
    }

    @Test
    fun scanDataIsCopiedWithoutChangingASingleByte() {
        val random = Random(42)
        val entropy = SyntheticJpeg.randomEntropy(random, dataBytes = 200_000, restartEvery = 1000)
        val input = concat(
            soi, app1Exif(TiffLayout.GPS_IN_IFD0), dqt(), sof0(), dht(), sosHeader(), entropy, eoi,
        )
        val output = stripped(input).bytes

        val scanStart = indexOf(output, sosHeader())
        assertTrue(scanStart > 0)
        val entropyStart = scanStart + sosHeader().size
        val copied = output.copyOfRange(entropyStart, entropyStart + entropy.size)
        assertArrayEquals("entropy-coded bytes must be identical", entropy, copied)
        assertArrayEquals("EOI must follow the entropy data", eoi, output.copyOfRange(output.size - 2, output.size))
        // SOF, DHT and DQT payloads survive unchanged.
        assertTrue(indexOf(output, sof0()) >= 0)
        assertTrue(indexOf(output, dht()) >= 0)
        assertTrue(indexOf(output, dqt()) >= 0)
    }

    @Test
    fun exifWithoutGpsIsRemovedAndReportedAsNoGps() {
        val input = concat(soi, app1Exif(TiffLayout.NO_GPS), dqt(), sof0(), dht(), sosHeader(), smallEntropy(), eoi)
        val result = stripped(input)
        assertFalse(result.containedGps)
        assertEquals(listOf(RemovalReason.EXIF_OR_XMP), result.removedSegments.map { it.reason })
    }

    @Test
    fun gpsPointerIsFoundInBigEndianTiffInIfd1AndInTheExifSubIfd() {
        for (bigEndian in listOf(false, true)) {
            for (layout in listOf(TiffLayout.GPS_IN_IFD0, TiffLayout.GPS_IN_IFD1, TiffLayout.GPS_IN_EXIF_SUB_IFD)) {
                val input = concat(soi, app1Exif(layout, bigEndian), dqt(), sof0(), dht(), sosHeader(), smallEntropy(), eoi)
                assertTrue("layout=$layout bigEndian=$bigEndian", stripped(input).containedGps)
            }
            val clean = concat(soi, app1Exif(TiffLayout.NO_GPS, bigEndian), dqt(), sof0(), dht(), sosHeader(), smallEntropy(), eoi)
            assertFalse("no-gps bigEndian=$bigEndian", stripped(clean).containedGps)
        }
    }

    @Test
    fun xmpGpsPropertiesAreReported() {
        val withGps = concat(soi, app1Xmp(withGps = true), dqt(), sof0(), dht(), sosHeader(), smallEntropy(), eoi)
        val withoutGps = concat(soi, app1Xmp(withGps = false), dqt(), sof0(), dht(), sosHeader(), smallEntropy(), eoi)
        assertTrue(stripped(withGps).containedGps)
        assertFalse(stripped(withoutGps).containedGps)
        assertEquals(-1, indexOf(stripped(withGps).bytes, "xmpmeta".toByteArray()))
    }

    // ---- other segments --------------------------------------------------------------------------

    @Test
    fun anotherApplicationSegmentThatContainsAGpsIfdIsRemoved() {
        val image = concat(dqt(), sof0(), dht(), sosHeader(), smallEntropy())
        val input = concat(soi, app0Jfif(), app3Meta(TiffLayout.GPS_IN_IFD0), image, eoi)
        val result = stripped(input)
        assertArrayEquals(concat(soi, app0Jfif(), image, eoi), result.bytes)
        assertTrue(result.containedGps)
        assertEquals(listOf(RemovalReason.GPS_IFD), result.removedSegments.map { it.reason })
    }

    @Test
    fun applicationSegmentsWithoutGpsAreKept() {
        val image = concat(dqt(), sof0(), dht(), sosHeader(), smallEntropy())
        val keepers = concat(app0Jfif(), app2Icc(), app3Meta(TiffLayout.NO_GPS), app14Adobe())
        val result = stripped(concat(soi, keepers, image, eoi))
        assertArrayEquals(concat(soi, keepers, image, eoi), result.bytes)
        assertFalse(result.containedGps)
        assertTrue(result.removedSegments.isEmpty())
    }

    @Test
    fun multiPictureIndexIsRemovedBecauseItsImagesAreDropped() {
        val image = concat(dqt(), sof0(), dht(), sosHeader(), smallEntropy())
        val secondPhoto = concat(soi, app1Exif(TiffLayout.GPS_IN_IFD0), eoi)
        val input = concat(soi, app2Mpf(), image, eoi, secondPhoto)
        val result = stripped(input)
        assertArrayEquals(concat(soi, image, eoi), result.bytes)
        assertEquals(secondPhoto.size, result.trailingBytesDropped)
        assertEquals(listOf(RemovalReason.MULTI_PICTURE_INDEX), result.removedSegments.map { it.reason })
    }

    @Test
    fun bytesAfterEndOfImageAreDropped() {
        val image = concat(dqt(), sof0(), dht(), sosHeader(), smallEntropy())
        val trailer = ByteArray(1234) { 0x5A }
        val result = stripped(concat(soi, image, eoi, trailer))
        assertArrayEquals(concat(soi, image, eoi), result.bytes)
        assertEquals(1234, result.trailingBytesDropped)
    }

    @Test
    fun multipleScansAndRestartMarkersArePreserved() {
        val scan1 = concat(sosHeader(), smallEntropy())
        val scan2 = concat(sosHeader(), SyntheticJpeg.bytes(0x01, 0x02, 0xFF, 0x00, 0xFF, 0xD1, 0x03))
        val image = concat(dqt(), sof0(), dht(), scan1, dht(), scan2)
        val input = concat(soi, app1Exif(TiffLayout.GPS_IN_IFD0), image, eoi)
        val result = stripped(input)
        assertArrayEquals(concat(soi, image, eoi), result.bytes)
    }

    @Test
    fun fillBytesBetweenSegmentsAndBeforeMarkersDoNotBreakParsing() {
        val fill = bytes(0xFF, 0xFF, 0xFF)
        // FF FF FF E1 ...: the fill bytes stay (they are not a segment), the APP1 goes.
        val input = concat(soi, fill, app1Exif(TiffLayout.GPS_IN_IFD0), dqt(), sof0(), dht(), sosHeader(), smallEntropy(), fill, eoi)
        val result = stripped(input)
        val expected = concat(soi, fill, dqt(), sof0(), dht(), sosHeader(), smallEntropy(), fill, eoi)
        assertEquals(expected.size, result.bytes.size)
        assertTrue(result.containedGps)
        // Everything the input held after the removed segment is intact, fill included.
        assertArrayEquals(expected, result.bytes)
    }

    // ---- rejection -------------------------------------------------------------------------------

    @Test
    fun rejectsInputThatIsNotAJpeg() {
        assertEquals(JpegRejection.EMPTY, rejected(ByteArray(0)))
        assertEquals(JpegRejection.NOT_A_JPEG, rejected(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals(JpegRejection.NOT_A_JPEG, rejected("GIF89a....".toByteArray()))
        assertEquals(JpegRejection.NOT_A_JPEG, rejected("RIFF....WEBPVP8 ".toByteArray()))
        assertEquals(JpegRejection.NOT_A_JPEG, rejected(bytes(0xFF, 0xD8)))
        assertEquals(JpegRejection.NOT_A_JPEG, rejected(bytes(0xFF, 0xD8, 0x00)))
        assertEquals(JpegRejection.NOT_A_JPEG, rejected(bytes(0xD8, 0xFF, 0xE0)))
        assertEquals(JpegRejection.NOT_A_JPEG, rejected(ByteArray(4096) { 0x20 }))
    }

    @Test
    fun rejectsEveryStrictPrefixOfAValidJpeg() {
        val input = concat(soi, app0Jfif(), app1Exif(TiffLayout.GPS_IN_IFD0), dqt(), sof0(), dht(), sosHeader(), smallEntropy(), eoi)
        assertTrue(JpegMetadata.strip(input) is JpegStripResult.Stripped)
        for (cut in 0 until input.size) {
            val result = JpegMetadata.strip(input.copyOf(cut))
            assertTrue("prefix of $cut bytes must be rejected", result is JpegStripResult.Rejected)
        }
    }

    @Test
    fun rejectsSegmentLengthsBelowTwoAndStrayBytesBetweenSegments() {
        val badLength = concat(soi, bytes(0xFF, 0xE0, 0x00, 0x01), dqt(), sof0(), sosHeader(), smallEntropy(), eoi)
        assertEquals(JpegRejection.MALFORMED, rejected(badLength))

        val stray = concat(soi, dqt(), bytes(0x00), sof0(), dht(), sosHeader(), smallEntropy(), eoi)
        assertEquals(JpegRejection.MALFORMED, rejected(stray))

        val stuffedOutsideScan = concat(soi, dqt(), bytes(0xFF, 0x00), sof0(), dht(), sosHeader(), smallEntropy(), eoi)
        assertEquals(JpegRejection.MALFORMED, rejected(stuffedOutsideScan))

        val nestedSoi = concat(soi, soi, dqt(), sof0(), dht(), sosHeader(), smallEntropy(), eoi)
        assertEquals(JpegRejection.MALFORMED, rejected(nestedSoi))
    }

    @Test
    fun rejectsSegmentsThatRunPastTheEndOfTheData() {
        val lyingLength = concat(soi, bytes(0xFF, 0xE0, 0xFF, 0xF0, 0x01, 0x02, 0x03))
        assertEquals(JpegRejection.TRUNCATED, rejected(lyingLength))
    }

    @Test
    fun rejectsJpegWithoutFrameHeaderOrScan() {
        assertEquals(JpegRejection.NO_IMAGE_DATA, rejected(concat(soi, app0Jfif(), app1Exif(TiffLayout.GPS_IN_IFD0), eoi)))
        assertEquals(JpegRejection.NO_IMAGE_DATA, rejected(concat(soi, dqt(), sof0(), dht(), eoi)))
        assertEquals(JpegRejection.MALFORMED, rejected(concat(soi, dqt(), dht(), sosHeader(), smallEntropy(), eoi)))
    }

    // ---- invariants ------------------------------------------------------------------------------

    @Test
    fun stripIsIdempotentAndDoesNotTouchItsInput() {
        val input = concat(
            soi, app0Jfif(), app1Exif(TiffLayout.GPS_IN_IFD0), app13Photoshop(), com("x"), app2Icc(),
            dqt(), sof0(), dht(), sosHeader(), smallEntropy(), eoi,
        )
        val snapshot = input.copyOf()
        val first = stripped(input)
        assertArrayEquals("input must not be modified", snapshot, input)
        assertNotSame(input, first.bytes)

        val second = stripped(first.bytes)
        assertArrayEquals(first.bytes, second.bytes)
        assertTrue(second.removedSegments.isEmpty())
        assertFalse(second.containedGps)
    }

    @Test
    fun cleanInputIsCopiedUnchanged() {
        val input = concat(soi, app0Jfif(), dqt(), sof0(), dht(), sosHeader(), smallEntropy(), eoi)
        val result = stripped(input)
        assertArrayEquals(input, result.bytes)
        assertNotSame(input, result.bytes)
    }

    @Test
    fun randomisedJpegsKeepExactlyTheNonMetadataSegments() {
        val random = Random(20261010)
        repeat(300) { round ->
            val dropped = ArrayList<ByteArray>()
            val parts = ArrayList<ByteArray>()
            parts += soi
            val optional = listOf<Pair<ByteArray, Boolean>>(
                app0Jfif() to true,
                app1Exif(TiffLayout.entries[random.nextInt(TiffLayout.entries.size)], random.nextBoolean()) to false,
                app1Xmp(random.nextBoolean()) to false,
                app13Photoshop() to false,
                com("c$round") to false,
                app2Icc() to true,
                app2Mpf() to false,
                app14Adobe() to true,
                app3Meta(TiffLayout.GPS_IN_IFD1) to false,
                app3Meta(TiffLayout.NO_GPS) to true,
            ).shuffled(random).take(1 + random.nextInt(8))
            for ((segment, keep) in optional) {
                parts += segment
                if (!keep) dropped += segment
            }
            val entropy = SyntheticJpeg.randomEntropy(random, 1 + random.nextInt(3000), restartEvery = random.nextInt(3) * 100)
            val image = concat(dqt(), sof0(random.nextInt(2000) + 1, random.nextInt(2000) + 1), dht(), sosHeader(), entropy)
            parts += image
            parts += eoi
            val input = concat(*parts.toTypedArray())

            val expectedParts = ArrayList<ByteArray>()
            expectedParts += soi
            for (segment in parts.drop(1).dropLast(2)) if (dropped.none { it === segment }) expectedParts += segment
            expectedParts += image
            expectedParts += eoi
            val expected = concat(*expectedParts.toTypedArray())

            val result = stripped(input)
            assertArrayEquals("round $round", expected, result.bytes)
            assertEquals("round $round", dropped.size, result.removedSegments.size)
        }
    }

    @Test
    fun largeInputIsProcessedAndEntropyDataSurvives() {
        val random = Random(7)
        val entropy = SyntheticJpeg.randomEntropy(random, dataBytes = 3_000_000)
        val input = concat(soi, app1Exif(TiffLayout.GPS_IN_IFD0), dqt(), sof0(), dht(), sosHeader(), entropy, eoi)
        val output = stripped(input).bytes
        assertEquals(input.size - app1Exif(TiffLayout.GPS_IN_IFD0).size, output.size)
        val start = output.size - 2 - entropy.size
        assertArrayEquals(entropy, output.copyOfRange(start, output.size - 2))
    }

    @Test
    fun adversarialTiffLookalikesDoNotCauseErrors() {
        // A kept application segment stuffed with TIFF headers whose offsets point nowhere or loop.
        val header = bytes(0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00)
        val noise = ByteArray(60_000)
        for (i in noise.indices) noise[i] = header[i % header.size]
        val image = concat(dqt(), sof0(), dht(), sosHeader(), smallEntropy())
        val input = concat(soi, SyntheticJpeg.segment(0xE4, noise), image, eoi)
        val result = JpegMetadata.strip(input)
        if (result !is JpegStripResult.Stripped) fail("expected Stripped, got $result")
    }
}
