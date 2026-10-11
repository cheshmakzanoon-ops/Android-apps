package com.agency.photoduel.engine.pure

import com.agency.photoduel.domain.model.ErrorCode

/** Image container formats the server accepts. Detected by magic bytes only. */
enum class ImageFormat { JPEG, PNG, WEBP }

/** Why an image or an upload was rejected, with the API error code the server would answer with. */
enum class UploadViolation(val errorCode: ErrorCode) {
    /** Not JPEG, PNG or WebP by magic bytes. */
    UNSUPPORTED_FORMAT(ErrorCode.UNSUPPORTED_MEDIA),

    /** Width or height is zero or negative: the image could not be measured. */
    INVALID_DIMENSIONS(ErrorCode.UNSUPPORTED_MEDIA),

    /** More than [UploadPolicy.MAX_PIXELS] pixels. */
    TOO_MANY_PIXELS(ErrorCode.UNSUPPORTED_MEDIA),

    /** Short edge under [UploadPolicy.MIN_SHORT_EDGE_PX]. */
    SHORT_EDGE_TOO_SMALL(ErrorCode.UNSUPPORTED_MEDIA),

    /** Whole request body over [UploadPolicy.MAX_BODY_BYTES]. */
    BODY_TOO_LARGE(ErrorCode.PAYLOAD_TOO_LARGE),

    /** Full variant over [UploadPolicy.MAX_FULL_BYTES]. */
    FULL_TOO_LARGE(ErrorCode.PAYLOAD_TOO_LARGE),

    /** Thumbnail over [UploadPolicy.MAX_THUMBNAIL_BYTES]. */
    THUMBNAIL_TOO_LARGE(ErrorCode.PAYLOAD_TOO_LARGE),
}

/** Verdict on a source image (format and dimensions). */
sealed interface ImageCheck {
    data class Accepted(val format: ImageFormat, val size: PixelSize) : ImageCheck
    data class Rejected(val violation: UploadViolation) : ImageCheck
}

/** Verdict on byte sizes. */
sealed interface SizeCheck {
    data object Accepted : SizeCheck
    data class Rejected(val violation: UploadViolation) : SizeCheck
}

/**
 * The upload rules of the server (docs/api-contract.md section 7 and docs/architecture.md
 * sections 9 and 10), mirrored on the client so a bad photo is refused before any bytes are sent.
 * The server stays the authority: change a number here only together with the backend.
 *
 * Byte limits use binary megabytes and kilobytes (1 MB = 1024 * 1024 bytes). A megapixel is
 * 1 000 000 pixels.
 */
object UploadPolicy {
    /** Raw request body, all parts included. */
    const val MAX_BODY_BYTES: Long = 3L * 1024 * 1024

    /** Full variant (1600 px long edge). */
    const val MAX_FULL_BYTES: Int = 2 * 1024 * 1024

    /** Thumbnail variant (480 px long edge). */
    const val MAX_THUMBNAIL_BYTES: Int = 300 * 1024

    /** Input pixel ceiling: 40 megapixels. */
    const val MAX_PIXELS: Long = 40_000_000L

    /** Smallest allowed short edge, in pixels. */
    const val MIN_SHORT_EDGE_PX: Int = 200

    /** Bytes of file header needed by [detectFormat]. */
    const val HEADER_BYTES_NEEDED: Int = 12

    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val RIFF = byteArrayOf(0x52, 0x49, 0x46, 0x46)
    private val WEBP = byteArrayOf(0x57, 0x45, 0x42, 0x50)

    /**
     * Detects the format from the first bytes of a file, never from a name or a declared type.
     * JPEG is FF D8 FF, PNG is 89 50 4E 47 0D 0A 1A 0A, WebP is "RIFF", four size bytes, "WEBP".
     * Returns null for anything else, including headers too short to decide.
     */
    fun detectFormat(header: ByteArray, length: Int = header.size): ImageFormat? {
        val available = length.coerceIn(0, header.size)
        return when {
            available >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte() ->
                ImageFormat.JPEG
            available >= PNG_MAGIC.size && startsWith(header, PNG_MAGIC, 0) -> ImageFormat.PNG
            available >= 12 && startsWith(header, RIFF, 0) && startsWith(header, WEBP, 8) -> ImageFormat.WEBP
            else -> null
        }
    }

    /** Checks the format first, then the dimensions. The first violation found is returned. */
    fun checkImage(header: ByteArray, headerLength: Int, width: Int, height: Int): ImageCheck {
        val format = detectFormat(header, headerLength) ?: return ImageCheck.Rejected(UploadViolation.UNSUPPORTED_FORMAT)
        val violation = checkDimensions(width, height)
        return if (violation != null) ImageCheck.Rejected(violation) else ImageCheck.Accepted(format, PixelSize(width, height))
    }

    /** Null when the dimensions are acceptable, otherwise the violation. */
    fun checkDimensions(width: Int, height: Int): UploadViolation? = when {
        width <= 0 || height <= 0 -> UploadViolation.INVALID_DIMENSIONS
        width.toLong() * height.toLong() > MAX_PIXELS -> UploadViolation.TOO_MANY_PIXELS
        minOf(width, height) < MIN_SHORT_EDGE_PX -> UploadViolation.SHORT_EDGE_TOO_SMALL
        else -> null
    }

    /** Checks a raw request body size. */
    fun checkBody(bodyBytes: Long): SizeCheck =
        if (bodyBytes > MAX_BODY_BYTES) SizeCheck.Rejected(UploadViolation.BODY_TOO_LARGE) else SizeCheck.Accepted

    /**
     * Checks both encoded variants. [bodyBytes] is the whole multipart body when known; when it is
     * not known, an approximation (the sum of both parts) is used.
     */
    fun checkPrepared(fullBytes: Int, thumbnailBytes: Int, bodyBytes: Long = fullBytes.toLong() + thumbnailBytes): SizeCheck = when {
        fullBytes > MAX_FULL_BYTES -> SizeCheck.Rejected(UploadViolation.FULL_TOO_LARGE)
        thumbnailBytes > MAX_THUMBNAIL_BYTES -> SizeCheck.Rejected(UploadViolation.THUMBNAIL_TOO_LARGE)
        bodyBytes > MAX_BODY_BYTES -> SizeCheck.Rejected(UploadViolation.BODY_TOO_LARGE)
        else -> SizeCheck.Accepted
    }

    private fun startsWith(data: ByteArray, prefix: ByteArray, offset: Int): Boolean {
        if (data.size < offset + prefix.size) return false
        for (i in prefix.indices) if (data[offset + i] != prefix[i]) return false
        return true
    }
}
