package com.agency.stickerjet.engine

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import com.agency.stickerjet.domain.StickerLimits
import java.io.ByteArrayOutputStream

/**
 * Encodes one composed frame as a complete static WebP with Bitmap.compress. The result is a single image
 * (VP8X + ALPH + VP8 for lossy with alpha), which [com.agency.stickerjet.engine.AnimatedWebpWriter] accepts.
 *
 * [budgetBytes] is the whole-file budget. The exporter's quality ladder uses [fits] on the muxed total.
 */
class FrameEncoder(val budgetBytes: Long = StickerLimits.MAX_EXPORT_BYTES) {

    init {
        require(budgetBytes > 0) { "Budget must be positive" }
    }

    /** Returns the static WebP bytes of [frame] at [quality] (0..100). Throws if the platform encoder fails. */
    fun encode(frame: Bitmap, quality: Int): ByteArray {
        require(quality in 0..100) { "Quality must be 0..100, was $quality" }
        val out = ByteArrayOutputStream()
        val ok = compress(frame, quality, out)
        check(ok && out.size() > 0) { "Bitmap.compress produced no WebP output" }
        return out.toByteArray()
    }

    /** True when [totalBytes] fits the budget. */
    fun fits(totalBytes: Long): Boolean = totalBytes <= budgetBytes

    // WEBP_LOSSY exists from API 30. Below that, WEBP is the lossy format, but it is deprecated from API 30 on;
    // it is referenced only on the branch where WEBP_LOSSY does not exist, so the deprecation is expected.
    @Suppress("DEPRECATION")
    @SuppressLint("Deprecated")
    private fun compress(frame: Bitmap, quality: Int, out: ByteArrayOutputStream): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            frame.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out)
        } else {
            frame.compress(Bitmap.CompressFormat.WEBP, quality, out)
        }
}
