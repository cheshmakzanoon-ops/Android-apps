package com.agency.photoduel.engine.pure

import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.engine.pure.SyntheticJpeg.bytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadPolicyTest {

    private val jpegHeader = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01)
    private val pngHeader = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D)
    private val webpHeader = bytes(0x52, 0x49, 0x46, 0x46, 0x24, 0x08, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50)

    // ---- magic bytes -----------------------------------------------------------------------------

    @Test
    fun detectsJpegPngAndWebpByMagicBytes() {
        assertEquals(ImageFormat.JPEG, UploadPolicy.detectFormat(jpegHeader))
        assertEquals(ImageFormat.PNG, UploadPolicy.detectFormat(pngHeader))
        assertEquals(ImageFormat.WEBP, UploadPolicy.detectFormat(webpHeader))
    }

    @Test
    fun minimalHeadersAreEnough() {
        assertEquals(ImageFormat.JPEG, UploadPolicy.detectFormat(bytes(0xFF, 0xD8, 0xFF)))
        assertEquals(ImageFormat.PNG, UploadPolicy.detectFormat(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals(ImageFormat.WEBP, UploadPolicy.detectFormat("RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun rejectsEverythingElse() {
        assertNull(UploadPolicy.detectFormat(ByteArray(0)))
        assertNull(UploadPolicy.detectFormat(bytes(0xFF, 0xD8))) // missing third byte
        assertNull(UploadPolicy.detectFormat(bytes(0xFF, 0xD8, 0xE0))) // third byte must be FF
        assertNull(UploadPolicy.detectFormat(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A))) // PNG cut short
        assertNull(UploadPolicy.detectFormat(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0B))) // last byte wrong
        assertNull(UploadPolicy.detectFormat("GIF89a\u0001\u0000\u0001\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)))
        assertNull(UploadPolicy.detectFormat("BM......".toByteArray())) // BMP
        assertNull(UploadPolicy.detectFormat("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray()))
        assertNull(UploadPolicy.detectFormat("%PDF-1.7\n%".toByteArray()))
        // RIFF container that is not WebP (a WAV file), and WebP-looking text without RIFF.
        assertNull(UploadPolicy.detectFormat("RIFF\u0000\u0000\u0000\u0000WAVEfmt ".toByteArray(Charsets.ISO_8859_1)))
        assertNull(UploadPolicy.detectFormat("XXXX\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
        assertNull(UploadPolicy.detectFormat("RIFF....WEB".toByteArray())) // 11 bytes: too short to decide
        // HEIC: an ISO base media file is not on the list.
        assertNull(UploadPolicy.detectFormat(bytes(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70, 0x68, 0x65, 0x69, 0x63)))
    }

    @Test
    fun lengthParameterLimitsHowManyBytesAreTrusted() {
        val padded = ByteArray(64)
        System.arraycopy(jpegHeader, 0, padded, 0, jpegHeader.size)
        assertEquals(ImageFormat.JPEG, UploadPolicy.detectFormat(padded, 12))
        assertEquals(ImageFormat.JPEG, UploadPolicy.detectFormat(padded, 3))
        assertNull(UploadPolicy.detectFormat(padded, 2))
        assertNull(UploadPolicy.detectFormat(padded, 0))
        assertNull(UploadPolicy.detectFormat(padded, -5))
        // A length larger than the array never reads out of bounds.
        assertEquals(ImageFormat.JPEG, UploadPolicy.detectFormat(jpegHeader, 5_000))
        // PNG needs eight bytes even if the array is longer.
        assertNull(UploadPolicy.detectFormat(pngHeader, 7))
        assertNull(UploadPolicy.detectFormat(webpHeader, 11))
        assertEquals(ImageFormat.WEBP, UploadPolicy.detectFormat(webpHeader, 12))
    }

    // ---- limits ----------------------------------------------------------------------------------

    @Test
    fun limitsMirrorTheServer() {
        assertEquals(3L * 1024 * 1024, UploadPolicy.MAX_BODY_BYTES)
        assertEquals(2 * 1024 * 1024, UploadPolicy.MAX_FULL_BYTES)
        assertEquals(300 * 1024, UploadPolicy.MAX_THUMBNAIL_BYTES)
        assertEquals(40_000_000L, UploadPolicy.MAX_PIXELS)
        assertEquals(200, UploadPolicy.MIN_SHORT_EDGE_PX)
    }

    @Test
    fun pixelLimitIsInclusiveAtFortyMegapixels() {
        assertNull(UploadPolicy.checkDimensions(8000, 5000)) // exactly 40 000 000
        assertEquals(UploadViolation.TOO_MANY_PIXELS, UploadPolicy.checkDimensions(8001, 5000))
        assertEquals(UploadViolation.TOO_MANY_PIXELS, UploadPolicy.checkDimensions(10_000, 4_001))
        assertNull(UploadPolicy.checkDimensions(40_000, 1000)) // 40 MP at an extreme aspect
        // 100 000 x 100 000 overflows Int multiplication; it must still be rejected.
        assertEquals(UploadViolation.TOO_MANY_PIXELS, UploadPolicy.checkDimensions(100_000, 100_000))
        assertEquals(UploadViolation.TOO_MANY_PIXELS, UploadPolicy.checkDimensions(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun shortEdgeLimitIsInclusiveAt200() {
        assertNull(UploadPolicy.checkDimensions(200, 200))
        assertNull(UploadPolicy.checkDimensions(200, 4000))
        assertNull(UploadPolicy.checkDimensions(4000, 200))
        assertEquals(UploadViolation.SHORT_EDGE_TOO_SMALL, UploadPolicy.checkDimensions(199, 4000))
        assertEquals(UploadViolation.SHORT_EDGE_TOO_SMALL, UploadPolicy.checkDimensions(4000, 199))
        assertEquals(UploadViolation.SHORT_EDGE_TOO_SMALL, UploadPolicy.checkDimensions(1, 1))
    }

    @Test
    fun unmeasurableDimensionsAreInvalid() {
        assertEquals(UploadViolation.INVALID_DIMENSIONS, UploadPolicy.checkDimensions(0, 1000))
        assertEquals(UploadViolation.INVALID_DIMENSIONS, UploadPolicy.checkDimensions(1000, 0))
        assertEquals(UploadViolation.INVALID_DIMENSIONS, UploadPolicy.checkDimensions(-1, -1))
    }

    @Test
    fun bodyLimitIsInclusiveAtThreeMegabytes() {
        assertEquals(SizeCheck.Accepted, UploadPolicy.checkBody(0))
        assertEquals(SizeCheck.Accepted, UploadPolicy.checkBody(3L * 1024 * 1024))
        assertEquals(
            SizeCheck.Rejected(UploadViolation.BODY_TOO_LARGE),
            UploadPolicy.checkBody(3L * 1024 * 1024 + 1),
        )
    }

    @Test
    fun preparedVariantLimitsAreInclusive() {
        assertEquals(SizeCheck.Accepted, UploadPolicy.checkPrepared(2 * 1024 * 1024, 300 * 1024))
        assertEquals(
            SizeCheck.Rejected(UploadViolation.FULL_TOO_LARGE),
            UploadPolicy.checkPrepared(2 * 1024 * 1024 + 1, 1000),
        )
        assertEquals(
            SizeCheck.Rejected(UploadViolation.THUMBNAIL_TOO_LARGE),
            UploadPolicy.checkPrepared(1000, 300 * 1024 + 1),
        )
        // Both parts are within their own limits but the body is not.
        assertEquals(
            SizeCheck.Rejected(UploadViolation.BODY_TOO_LARGE),
            UploadPolicy.checkPrepared(2 * 1024 * 1024, 300 * 1024, bodyBytes = 3L * 1024 * 1024 + 1),
        )
    }

    // ---- combined check and error mapping ----------------------------------------------------------

    @Test
    fun checkImageReturnsTheFormatAndSizeWhenAcceptable() {
        val result = UploadPolicy.checkImage(jpegHeader, jpegHeader.size, 4032, 3024)
        assertEquals(ImageCheck.Accepted(ImageFormat.JPEG, PixelSize(4032, 3024)), result)
        val png = UploadPolicy.checkImage(pngHeader, pngHeader.size, 1080, 1920)
        assertEquals(ImageCheck.Accepted(ImageFormat.PNG, PixelSize(1080, 1920)), png)
    }

    @Test
    fun checkImageReportsTheFormatBeforeTheDimensions() {
        val result = UploadPolicy.checkImage("GIF89a......".toByteArray(), 12, 10, 10)
        assertEquals(ImageCheck.Rejected(UploadViolation.UNSUPPORTED_FORMAT), result)
        val small = UploadPolicy.checkImage(webpHeader, webpHeader.size, 150, 3000)
        assertEquals(ImageCheck.Rejected(UploadViolation.SHORT_EDGE_TOO_SMALL), small)
        val huge = UploadPolicy.checkImage(jpegHeader, jpegHeader.size, 9000, 9000)
        assertEquals(ImageCheck.Rejected(UploadViolation.TOO_MANY_PIXELS), huge)
    }

    @Test
    fun violationsMapToTheDocumentedApiErrorCodes() {
        val unsupportedMedia = listOf(
            UploadViolation.UNSUPPORTED_FORMAT,
            UploadViolation.INVALID_DIMENSIONS,
            UploadViolation.TOO_MANY_PIXELS,
            UploadViolation.SHORT_EDGE_TOO_SMALL,
        )
        val payloadTooLarge = listOf(
            UploadViolation.BODY_TOO_LARGE,
            UploadViolation.FULL_TOO_LARGE,
            UploadViolation.THUMBNAIL_TOO_LARGE,
        )
        for (v in unsupportedMedia) assertEquals("$v", ErrorCode.UNSUPPORTED_MEDIA, v.errorCode)
        for (v in payloadTooLarge) assertEquals("$v", ErrorCode.PAYLOAD_TOO_LARGE, v.errorCode)
        assertEquals(UploadViolation.entries.size, unsupportedMedia.size + payloadTooLarge.size)
    }

    @Test
    fun everyViolationHasAPreparationFailureWithTheSameApiCodeFamily() {
        for (v in UploadViolation.entries) {
            val failure = PhotoPreparationFailure.from(v)
            if (v.errorCode == ErrorCode.PAYLOAD_TOO_LARGE) {
                assertEquals("$v", ErrorCode.PAYLOAD_TOO_LARGE, failure.code)
            } else {
                assertEquals("$v", ErrorCode.UNSUPPORTED_MEDIA, failure.code)
            }
        }
    }

    @Test
    fun preparationErrorsCarryASafeMessageAndTheRightCode() {
        for (failure in PhotoPreparationFailure.entries) {
            val error = PhotoPreparationException(failure).error
            assertEquals(failure.code, error.code)
            assertEquals(failure.retryable, error.retryable)
            assertTrue(error.message.isNotBlank())
            val lower = error.message.lowercase()
            assertTrue("message must not mention uris or files: ${error.message}", "content://" !in lower && "file:" !in lower && ".jpg" !in lower)
        }
        assertEquals(ErrorCode.UNSUPPORTED_MEDIA, PhotoPreparationFailure.UNSUPPORTED_FORMAT.code)
        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, PhotoPreparationFailure.OUTPUT_TOO_LARGE.code)
        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, PhotoPreparationFailure.SOURCE_TOO_LARGE.code)
    }
}
