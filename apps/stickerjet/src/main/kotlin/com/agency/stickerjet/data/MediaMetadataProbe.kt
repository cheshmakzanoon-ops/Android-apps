package com.agency.stickerjet.data

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.agency.stickerjet.domain.ProbeResult
import java.io.File
import java.io.IOException

private const val JPEG_QUALITY: Int = 85

/**
 * [MediaProbe] backed by [MediaMetadataRetriever]. It is only given files inside app-private storage,
 * never content URIs, so a revoked grant cannot break the probe.
 */
internal class MediaMetadataProbe : MediaProbe {

    override fun probe(file: File): ProbeResult {
        val size = file.length()
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            ProbeResult(
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull(),
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull(),
                rotationDegrees = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0,
                hasVideoTrack = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes",
                sizeBytes = size,
            )
        } catch (e: RuntimeException) {
            // setDataSource rejects files it cannot parse. Report that as "no video", not as a crash.
            ProbeResult(
                durationMs = null,
                width = null,
                height = null,
                rotationDegrees = 0,
                hasVideoTrack = false,
                sizeBytes = size,
            )
        } finally {
            retriever.release()
        }
    }

    override fun writeThumbnail(source: File, target: File, maxSidePx: Int): Boolean {
        val retriever = MediaMetadataRetriever()
        var frame: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            retriever.setDataSource(source.path)
            val first = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return false
            frame = first
            val (width, height) = ThumbnailSize.fit(first.width, first.height, maxSidePx)
            val small = Bitmap.createScaledBitmap(first, width, height, true)
            scaled = small
            target.outputStream().use { out -> small.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) }
        } catch (e: RuntimeException) {
            target.delete()
            false
        } catch (e: IOException) {
            target.delete()
            false
        } finally {
            if (scaled !== frame) scaled?.recycle()
            frame?.recycle()
            retriever.release()
        }
    }
}
