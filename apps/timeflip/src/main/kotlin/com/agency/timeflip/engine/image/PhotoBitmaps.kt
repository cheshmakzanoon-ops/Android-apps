package com.agency.timeflip.engine.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.agency.timeflip.domain.model.PhotoId
import com.agency.timeflip.domain.port.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Decodes the private, already-oriented photograph copies at a bounded size. Used by the auto-aligner
 * and the MP4 exporter. Nothing here logs file names, paths or image content.
 */
class PhotoBitmaps(private val photoStore: PhotoStore) {

    /**
     * Returns an ARGB_8888 bitmap whose longest edge is at least [maxEdge] when the source allows it.
     * A power-of-two sample size is chosen from the bounds first, so the full original is never
     * decoded when a smaller sample is enough. Returns null for a missing copy or a decode failure.
     */
    suspend fun decode(id: PhotoId, maxEdge: Int): Bitmap? {
        require(maxEdge > 0) { "maxEdge must be positive" }
        val path = photoStore.originalPath(id) ?: return null
        return withContext(Dispatchers.IO) { decodeSampled(path, maxEdge) }
    }

    private fun decodeSampled(path: String, maxEdge: Int): Bitmap? {
        if (!File(path).isFile) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(max(bounds.outWidth, bounds.outHeight), maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            BitmapFactory.decodeFile(path, options)
        } catch (outOfMemory: OutOfMemoryError) {
            null
        }
    }

    companion object {
        /**
         * Largest power of two that keeps the longest edge at or above [maxEdge]. Never upscales:
         * when the source is already smaller than [maxEdge] the result is 1.
         */
        internal fun sampleSizeFor(longestEdge: Int, maxEdge: Int): Int {
            var sample = 1
            while (longestEdge / (sample * 2) >= maxEdge) {
                sample *= 2
            }
            return sample
        }
    }
}
