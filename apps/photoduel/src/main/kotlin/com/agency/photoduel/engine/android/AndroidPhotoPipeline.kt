package com.agency.photoduel.engine.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.agency.photoduel.domain.model.PreparedPhoto
import com.agency.photoduel.engine.pure.CompressionPlanner
import com.agency.photoduel.engine.pure.ImageCheck
import com.agency.photoduel.engine.pure.ImageGeometry
import com.agency.photoduel.engine.pure.JpegMetadata
import com.agency.photoduel.engine.pure.JpegStripResult
import com.agency.photoduel.engine.pure.OrientationTransform
import com.agency.photoduel.engine.pure.PhotoPreparationException
import com.agency.photoduel.engine.pure.PhotoPreparationFailure
import com.agency.photoduel.engine.pure.PixelSize
import com.agency.photoduel.engine.pure.SizeCheck
import com.agency.photoduel.engine.pure.UploadPolicy
import com.agency.photoduel.ui.PhotoProcessing
import com.agency.photoduel.ui.ProcessingStage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Tunable numbers of [AndroidPhotoPipeline]. The defaults follow docs/architecture.md section 9. */
data class PhotoPipelineLimits(
    /** Largest source file the pipeline will copy into the cache directory. */
    val maxSourceBytes: Long = 64L * 1024 * 1024,
    /** Encoded size budget of the full variant. The server limit is 2 MB; 1.5 MB leaves headroom. */
    val fullBudgetBytes: Int = 1_572_864,
    /** Encoded size budget of the thumbnail. Equal to the server limit of 300 KB. */
    val thumbnailBudgetBytes: Int = 300 * 1024,
    /** Highest JPEG quality tried. Lower qualities are used only when the budget needs them. */
    val preferredQuality: Int = 85,
    /**
     * Ceiling for a decoded bitmap, in pixels. Null derives it from the heap: one eighth of the
     * maximum heap, as ARGB_8888, clamped to 2 MP..[ImageGeometry.DEFAULT_DECODE_PIXEL_BUDGET].
     */
    val maxDecodedPixels: Long? = null,
)

/**
 * Prepares a chosen photo for upload: decodes it with a memory-safe sample size, applies the EXIF
 * orientation, scales to the full (1600 px) and thumbnail (480 px) variants, and encodes both as JPEG
 * within a byte budget. Re-encoding writes no EXIF, XMP, IPTC or GPS data; as a second line of
 * defence the encoder output is also passed through the pure [JpegMetadata] stripper.
 *
 * Failures are [PhotoPreparationException]s (their `error` maps to `UNSUPPORTED_MEDIA`,
 * `PAYLOAD_TOO_LARGE` or `UNKNOWN`). Cancellation is thrown as `CancellationException`.
 *
 * The [context] is used for `contentResolver` and `cacheDir` only. Nothing here logs: not bytes, not
 * file names, not URIs. Every temporary file lives in `cacheDir/photoduel-prepare` and is deleted in a
 * `finally` block, including when the call is cancelled. Bitmaps are recycled in `finally` blocks.
 *
 * Several `prepare` calls may run at the same time; each uses its own temporary file.
 */
class AndroidPhotoPipeline(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val limits: PhotoPipelineLimits = PhotoPipelineLimits(),
) : PhotoProcessing {

    private val appContext: Context = context.applicationContext ?: context

    override suspend fun prepare(source: Uri, onStage: (ProcessingStage) -> Unit): PreparedPhoto =
        withContext(dispatcher) { prepareOnWorker(source, onStage) }

    private suspend fun prepareOnWorker(source: Uri, onStage: (ProcessingStage) -> Unit): PreparedPhoto {
        val workDir = workDirectory()
        sweepStaleFiles(workDir)

        var sourceFile: File? = null
        var decoded: Bitmap? = null
        var full: Bitmap? = null
        var thumbnail: Bitmap? = null
        try {
            // Stage 1: READING. Copy the stream into the cache and look at it.
            onStage(ProcessingStage.READING)
            currentCoroutineContext().ensureActive()
            val file = copyToCache(source, workDir)
            sourceFile = file
            currentCoroutineContext().ensureActive()
            val stored = inspect(file)
            val orientation = readExifOrientation(file)
            currentCoroutineContext().ensureActive()

            // Stage 2: DECODING. Sampled decode, then orientation and scaling in one canvas pass.
            onStage(ProcessingStage.DECODING)
            decoded = decode(file, stored)
            currentCoroutineContext().ensureActive()
            val transform = ImageGeometry.transformFor(orientation)
            val upright = transform.orientedSize(decoded.width, decoded.height)
            val fullSize = ImageGeometry.fitFull(upright.width, upright.height)
            val thumbnailSize = ImageGeometry.fitThumbnail(upright.width, upright.height)
            full = renderUpright(decoded, transform, fullSize)
            decoded.recycleQuietly()
            decoded = null
            currentCoroutineContext().ensureActive()

            // Stage 3: ENCODING. Full first, then the thumbnail derived from the full bitmap.
            onStage(ProcessingStage.ENCODING)
            val fullJpeg = scrub(encodeWithinBudget(full, limits.fullBudgetBytes))
            currentCoroutineContext().ensureActive()
            thumbnail = downscale(full, thumbnailSize)
            if (thumbnail !== full) {
                full.recycleQuietly()
            }
            currentCoroutineContext().ensureActive()
            val thumbnailJpeg = scrub(encodeWithinBudget(thumbnail, limits.thumbnailBudgetBytes))
            currentCoroutineContext().ensureActive()

            if (UploadPolicy.checkPrepared(fullJpeg.size, thumbnailJpeg.size) is SizeCheck.Rejected) {
                throw PhotoPreparationException(PhotoPreparationFailure.OUTPUT_TOO_LARGE)
            }
            onStage(ProcessingStage.DONE)
            return PreparedPhoto(
                fullJpeg = fullJpeg,
                thumbnailJpeg = thumbnailJpeg,
                width = fullSize.width,
                height = fullSize.height,
            )
        } catch (e: OutOfMemoryError) {
            throw PhotoPreparationException(PhotoPreparationFailure.OUT_OF_MEMORY, e)
        } finally {
            decoded.recycleQuietly()
            full.recycleQuietly()
            thumbnail.recycleQuietly()
            sourceFile?.delete()
        }
    }

    // ---- stage 1: reading ------------------------------------------------------------------------

    private fun workDirectory(): File {
        val dir = File(appContext.cacheDir, WORK_DIRECTORY_NAME)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw PhotoPreparationException(PhotoPreparationFailure.SOURCE_UNREADABLE)
        }
        return dir
    }

    /** Removes files a killed process may have left behind. Fresh files of running calls are untouched. */
    private fun sweepStaleFiles(dir: File) {
        val cutoff = System.currentTimeMillis() - STALE_FILE_AGE_MILLIS
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
        }
    }

    private suspend fun copyToCache(uri: Uri, dir: File): File {
        val target = try {
            File.createTempFile("src-", ".tmp", dir)
        } catch (e: IOException) {
            throw PhotoPreparationException(PhotoPreparationFailure.SOURCE_UNREADABLE, e)
        }
        var succeeded = false
        try {
            openSource(uri).use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > limits.maxSourceBytes) {
                            throw PhotoPreparationException(PhotoPreparationFailure.SOURCE_TOO_LARGE)
                        }
                        output.write(buffer, 0, read)
                        currentCoroutineContext().ensureActive()
                    }
                }
            }
            succeeded = true
            return target
        } catch (e: IOException) {
            throw PhotoPreparationException(PhotoPreparationFailure.SOURCE_UNREADABLE, e)
        } finally {
            if (!succeeded) target.delete()
        }
    }

    private fun openSource(uri: Uri): InputStream {
        val stream = try {
            appContext.contentResolver.openInputStream(uri)
        } catch (e: FileNotFoundException) {
            null
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: UnsupportedOperationException) {
            null
        }
        return stream ?: throw PhotoPreparationException(PhotoPreparationFailure.SOURCE_UNREADABLE)
    }

    /** Checks the magic bytes and the stored dimensions against [UploadPolicy]. Returns the stored size. */
    private fun inspect(file: File): PixelSize {
        val header = ByteArray(UploadPolicy.HEADER_BYTES_NEEDED)
        val headerLength = try {
            FileInputStream(file).use { stream ->
                var filled = 0
                while (filled < header.size) {
                    val read = stream.read(header, filled, header.size - filled)
                    if (read < 0) break
                    filled += read
                }
                filled
            }
        } catch (e: IOException) {
            throw PhotoPreparationException(PhotoPreparationFailure.SOURCE_UNREADABLE, e)
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)

        return when (val check = UploadPolicy.checkImage(header, headerLength, bounds.outWidth, bounds.outHeight)) {
            is ImageCheck.Accepted -> check.size
            is ImageCheck.Rejected -> throw PhotoPreparationException(PhotoPreparationFailure.from(check.violation))
        }
    }

    private fun readExifOrientation(file: File): Int = try {
        FileInputStream(file).use { stream ->
            ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    } catch (e: IOException) {
        ExifInterface.ORIENTATION_NORMAL
    } catch (e: RuntimeException) {
        ExifInterface.ORIENTATION_NORMAL
    }

    // ---- stage 2: decoding, orientation, scaling --------------------------------------------------

    private fun decodePixelBudget(): Long =
        limits.maxDecodedPixels
            ?: (Runtime.getRuntime().maxMemory() / HEAP_FRACTION / BYTES_PER_PIXEL)
                .coerceIn(MIN_DECODE_PIXEL_BUDGET, ImageGeometry.DEFAULT_DECODE_PIXEL_BUDGET)

    private fun decode(file: File, stored: PixelSize): Bitmap {
        var sampleSize = ImageGeometry.chooseSampleSize(
            width = stored.width,
            height = stored.height,
            targetLongEdge = ImageGeometry.FULL_MAX_LONG_EDGE,
            maxDecodedPixels = decodePixelBudget(),
        )
        repeat(MAX_DECODE_ATTEMPTS) {
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                inMutable = false
            }
            try {
                val bitmap = BitmapFactory.decodeFile(file.path, options)
                if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
                    bitmap?.recycle()
                    throw PhotoPreparationException(PhotoPreparationFailure.DECODE_FAILED)
                }
                return bitmap
            } catch (e: OutOfMemoryError) {
                sampleSize *= 2
            }
        }
        throw PhotoPreparationException(PhotoPreparationFailure.OUT_OF_MEMORY)
    }

    /**
     * Draws [source] upright and at [target] size into a new bitmap in one pass. Transparent areas of
     * PNG and WebP sources are flattened onto white, because JPEG has no alpha channel.
     */
    private fun renderUpright(source: Bitmap, transform: OrientationTransform, target: PixelSize): Bitmap {
        val output = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(source, BitmapTransforms.uprightMatrix(source.width, source.height, transform, target), paint)
            return output
        } catch (t: Throwable) {
            output.recycle()
            throw t
        }
    }

    /**
     * Shrinks [source] to [target] in steps of at most 2x so bilinear filtering does not alias.
     * [source] itself is never recycled here; intermediate bitmaps are. Returns [source] when no
     * scaling is needed.
     */
    private fun downscale(source: Bitmap, target: PixelSize): Bitmap {
        var current = source
        try {
            for (step in ImageGeometry.scaleSteps(PixelSize(source.width, source.height), target)) {
                val next = Bitmap.createScaledBitmap(current, step.width, step.height, true)
                if (current !== source && current !== next) current.recycle()
                current = next
            }
            return current
        } catch (t: Throwable) {
            if (current !== source) current.recycle()
            throw t
        }
    }

    // ---- stage 3: encoding ------------------------------------------------------------------------

    /**
     * Encodes [bitmap] at the highest quality (up to the preferred quality) whose output fits
     * [budgetBytes]. The bytes of the best fitting probe are kept so the common case encodes once.
     */
    private suspend fun encodeWithinBudget(bitmap: Bitmap, budgetBytes: Int): ByteArray {
        val job: Job? = currentCoroutineContext()[Job]
        var bestQuality = -1
        var bestBytes: ByteArray? = null
        val plan = CompressionPlanner.plan(
            budgetBytes = budgetBytes,
            maxQuality = limits.preferredQuality,
        ) { quality ->
            job?.ensureActive()
            val bytes = compress(bitmap, quality)
            if (bytes.size <= budgetBytes && quality > bestQuality) {
                bestQuality = quality
                bestBytes = bytes
            }
            bytes.size
        }
        val cached = bestBytes
        val result = if (plan.budgetMet && cached != null && bestQuality == plan.quality) {
            cached
        } else {
            compress(bitmap, plan.quality)
        }
        if (result.size > budgetBytes) {
            throw PhotoPreparationException(PhotoPreparationFailure.OUTPUT_TOO_LARGE)
        }
        return result
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray {
        val output = ByteArrayOutputStream(INITIAL_ENCODE_CAPACITY)
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
            throw PhotoPreparationException(PhotoPreparationFailure.ENCODE_FAILED)
        }
        return output.toByteArray()
    }

    /** Second line of defence: guarantees the bytes carry no application metadata and no trailing data. */
    private fun scrub(jpeg: ByteArray): ByteArray = when (val result = JpegMetadata.strip(jpeg)) {
        is JpegStripResult.Stripped ->
            if (result.removedSegments.isEmpty() && result.trailingBytesDropped == 0) jpeg else result.bytes
        is JpegStripResult.Rejected -> throw PhotoPreparationException(PhotoPreparationFailure.ENCODE_FAILED)
    }

    private fun Bitmap?.recycleQuietly() {
        if (this != null && !isRecycled) recycle()
    }

    private companion object {
        const val WORK_DIRECTORY_NAME = "photoduel-prepare"
        const val STALE_FILE_AGE_MILLIS = 60L * 60L * 1000L
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val INITIAL_ENCODE_CAPACITY = 256 * 1024
        const val MAX_DECODE_ATTEMPTS = 3
        const val HEAP_FRACTION = 8L
        const val BYTES_PER_PIXEL = 4L
        const val MIN_DECODE_PIXEL_BUDGET = 2_000_000L
    }
}

/** Android matrices that realise the pure geometry of `engine/pure`. */
object BitmapTransforms {
    /**
     * Matrix that draws a stored bitmap of [storedWidth] x [storedHeight] upright and scaled to
     * [target]. [target] must have the aspect ratio of the upright image (as [ImageGeometry.fit]
     * returns it). Steps: mirror flips about the centre, clockwise rotation about the centre, move
     * the rotated bounds to the origin, then scale.
     *
     * With `target` equal to the upright size the matrix maps the centre of the stored pixel (x, y)
     * to inside the upright pixel given by `OrientationTransform.mapPixel(x, y, ...)`.
     */
    fun uprightMatrix(storedWidth: Int, storedHeight: Int, transform: OrientationTransform, target: PixelSize): Matrix {
        val centerX = storedWidth / 2f
        val centerY = storedHeight / 2f
        val matrix = Matrix()
        if (transform.flipHorizontal) matrix.postScale(-1f, 1f, centerX, centerY)
        if (transform.flipVertical) matrix.postScale(1f, -1f, centerX, centerY)
        if (transform.rotationDegrees != 0) matrix.postRotate(transform.rotationDegrees.toFloat(), centerX, centerY)
        val bounds = RectF(0f, 0f, storedWidth.toFloat(), storedHeight.toFloat())
        matrix.mapRect(bounds)
        matrix.postTranslate(-bounds.left, -bounds.top)
        matrix.postScale(target.width / bounds.width(), target.height / bounds.height())
        return matrix
    }
}
