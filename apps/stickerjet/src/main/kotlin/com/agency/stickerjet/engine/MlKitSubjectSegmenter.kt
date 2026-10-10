package com.agency.stickerjet.engine

import android.graphics.Bitmap
import com.agency.stickerjet.domain.AlphaMask
import com.agency.stickerjet.domain.ArgbFrame
import com.agency.stickerjet.domain.SubjectSegmenter
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Person-focused foreground segmentation with ML Kit selfie segmentation, in single-image mode with the
 * raw-size mask. It is PERSON-FOCUSED: pets, objects and other subjects are NOT segmented, and a clip
 * without a confident person returns null.
 *
 * Each call converts the frame to a Bitmap, runs the detector, bridges the Task to a suspend function
 * (cancellation of the caller does not stop ML Kit, but the result is discarded), and recycles the bitmap.
 * Call [close] when the owner is torn down to release the detector.
 */
class MlKitSubjectSegmenter : SubjectSegmenter, java.io.Closeable {

    private val client: Segmenter by lazy {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .enableRawSizeMask()
            .build()
        Segmentation.getClient(options)
    }

    /** Returns the foreground mask, or null when no person is confident enough. */
    override suspend fun segment(frame: ArgbFrame): AlphaMask? {
        val bitmap = Bitmap.createBitmap(frame.pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
        try {
            val mask = process(InputImage.fromBitmap(bitmap, 0))
            return SegmentationMasks.toAlphaMask(mask, frame.width, frame.height)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun process(image: InputImage): SegmentationMask =
        suspendCancellableCoroutine { continuation ->
            client.process(image)
                .addOnSuccessListener { mask -> if (continuation.isActive) continuation.resume(mask) }
                .addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
                .addOnCanceledListener { if (continuation.isActive) continuation.cancel() }
        }

    override fun close() {
        (client as? AutoCloseable)?.close()
    }
}
