package com.agency.timeflip.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import com.agency.timeflip.domain.error.ErrorCategory
import com.agency.timeflip.domain.error.TimeFlipError
import com.agency.timeflip.domain.error.TimeFlipException
import com.agency.timeflip.domain.model.PixelSize
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Pure image step behind [PrivatePhotoStore]: validate the format, apply EXIF orientation, scale and
 * re-encode. Takes files and returns files, so Robolectric tests can drive it with real JPEGs.
 */
internal object ImageNormalizer {
    const val MAX_EDGE_PX = 4096

    /** Upper bound on the longest edge of a decoded bitmap, which bounds decode memory (about 113 MB at 4:3). */
    const val MAX_DECODE_EDGE_PX = 6144
    const val JPEG_QUALITY = 95
    const val THUMBNAIL_JPEG_QUALITY = 85

    /** Sources above this pixel count are rejected before decoding, to bound time and memory. */
    const val MAX_SOURCE_PIXELS = 100_000_000L

    private val acceptedMimeTypes = setOf("image/jpeg", "image/png", "image/webp")
    private val heifMimeTypes = setOf("image/heic", "image/heif")

    private class Probe(val mimeType: String, val width: Int, val height: Int)

    /**
     * Writes an oriented, downscaled JPEG of [input] to [output]. The longest edge of the result is at
     * most [maxEdge]. EXIF metadata is not copied. The write goes through a temp file and an atomic rename,
     * so [output] is either absent or complete. Returns the size of the written image.
     *
     * Throws [TimeFlipException] with UNSUPPORTED_FORMAT, DECODE_FAILED or STORAGE_FULL.
     */
    fun normalize(input: File, output: File, maxEdge: Int = MAX_EDGE_PX): PixelSize {
        require(maxEdge > 0) { "maxEdge must be positive" }
        val probe = probe(input)
        val heif = probe.mimeType in heifMimeTypes
        val orientation = readOrientation(input)
        val orientedSize = if (swapsAxes(orientation)) {
            PixelSize(probe.height, probe.width)
        } else {
            PixelSize(probe.width, probe.height)
        }
        val target = orientedSize.scaledToLongestEdge(maxEdge)

        // The sample size keeps the decoded longest edge at or below maxEdge, which bounds decoded memory.
        val decoded = decodeSampled(input, sampleSizeFor(maxOf(probe.width, probe.height), maxEdge))
            ?: throw decodeFailure(heif)
        val oriented = try {
            render(decoded, orientation, target)
        } finally {
            decoded.recycle()
        }
        try {
            writeJpegAtomically(oriented, output, JPEG_QUALITY)
        } finally {
            oriented.recycle()
        }
        return target
    }

    /**
     * Writes a JPEG thumbnail of [source] whose longest edge is at most [maxEdge]. [source] is already oriented
     * (it is our own copy), so no EXIF handling is needed here.
     */
    fun writeThumbnail(source: File, output: File, maxEdge: Int) {
        require(maxEdge > 0) { "maxEdge must be positive" }
        val probe = probe(source)
        val target = PixelSize(probe.width, probe.height).scaledToLongestEdge(maxEdge)
        val decoded = decodeSampled(source, sampleSizeFor(maxOf(probe.width, probe.height), maxEdge))
            ?: throw decodeFailure(heif = false)
        val scaled = if (decoded.width == target.width && decoded.height == target.height) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, target.width, target.height, true)
        }
        try {
            writeJpegAtomically(scaled, output, THUMBNAIL_JPEG_QUALITY)
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    /**
     * Power-of-two sample size for decoding. Two bounds apply:
     * - memory: the decoded longest edge stays at or below [MAX_DECODE_EDGE_PX], or at the request if that is larger;
     * - quality: the decoded longest edge stays at or above [maxEdge] where possible, so the final scale
     *   is a reduction and never an upscale. When the two conflict, memory wins, and the result is then
     *   at most one sample step below the target.
     */
    internal fun sampleSizeFor(longestEdge: Int, maxEdge: Int): Int {
        val memoryCap = maxOf(MAX_DECODE_EDGE_PX, maxEdge)
        var memorySample = 1
        while (ceilDiv(longestEdge, memorySample) > memoryCap) memorySample *= 2
        var qualitySample = 1
        while (qualitySample * 2 <= longestEdge && ceilDiv(longestEdge, qualitySample * 2) >= maxEdge) {
            qualitySample *= 2
        }
        return maxOf(memorySample, qualitySample)
    }

    private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

    /** EXIF orientations 5 to 8 transpose the image, so width and height swap. */
    internal fun swapsAxes(orientation: Int): Boolean =
        orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270

    private fun probe(file: File): Probe {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val mimeType = options.outMimeType
        if (mimeType == null || (mimeType !in acceptedMimeTypes && mimeType !in heifMimeTypes)) {
            throw TimeFlipException(TimeFlipError(ErrorCategory.UNSUPPORTED_FORMAT, "image type is not JPEG, PNG, WebP or HEIF"))
        }
        val width = options.outWidth
        val height = options.outHeight
        if (width <= 0 || height <= 0) {
            throw TimeFlipException(TimeFlipError(ErrorCategory.DECODE_FAILED, "image bounds are unreadable"))
        }
        if (width.toLong() * height > MAX_SOURCE_PIXELS) {
            throw TimeFlipException(TimeFlipError(ErrorCategory.UNSUPPORTED_FORMAT, "image has too many pixels"))
        }
        return Probe(mimeType, width, height)
    }

    /** A missing or unreadable orientation tag means the stored pixels are already upright. */
    private fun readOrientation(file: File): Int = try {
        ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (error: IOException) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun decodeSampled(file: File, sampleSize: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        // Out-of-memory on a very large photo becomes a clean DECODE_FAILED rather than a process crash.
        return try {
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (error: OutOfMemoryError) {
            null
        }
    }

    private fun decodeFailure(heif: Boolean): TimeFlipException =
        if (heif) {
            TimeFlipException(TimeFlipError(ErrorCategory.UNSUPPORTED_FORMAT, "HEIF image cannot be decoded on this device"))
        } else {
            TimeFlipException(TimeFlipError(ErrorCategory.DECODE_FAILED, "image could not be decoded"))
        }

    /** Maps the source onto a [target]-sized bitmap, applying the EXIF transform first. */
    private fun render(source: Bitmap, orientation: Int, target: PixelSize): Bitmap {
        val matrix = orientationMatrix(orientation)
        // Shift the transformed bounds to the origin, then scale them exactly onto the target size.
        val bounds = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
        matrix.mapRect(bounds)
        matrix.postTranslate(-bounds.left, -bounds.top)
        matrix.postScale(target.width / bounds.width(), target.height / bounds.height())

        val output = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        // JPEG has no alpha. Transparent pixels from PNG sources become white, not black.
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return output
    }

    /** Mapping follows the EXIF specification and matches the transforms used by common image loaders. */
    internal fun orientationMatrix(orientation: Int): Matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                setRotate(180f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                setRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                setRotate(-90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            else -> Unit
        }
    }

    /** Encodes to a sibling temp file, syncs it, then renames it over [output] in one step. */
    private fun writeJpegAtomically(bitmap: Bitmap, output: File, quality: Int) {
        val temp = File(output.parentFile, output.name + StorageLayout.TEMP_SUFFIX)
        try {
            FileOutputStream(temp).use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)) {
                    throw TimeFlipException(TimeFlipError(ErrorCategory.DECODE_FAILED, "JPEG encoder rejected the image"))
                }
                stream.flush()
                stream.fd.sync()
            }
            // ATOMIC_MOVE replaces an existing target on the same filesystem; no other options apply.
            Files.move(temp.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (error: IOException) {
            if (error.isDiskFull()) throw storageFull("disk full while encoding", error)
            throw error
        } finally {
            temp.delete()
        }
    }
}
