package com.agency.stickerjet.engine

import com.agency.stickerjet.domain.AlphaMask
import com.google.mlkit.vision.segmentation.SegmentationMask

/**
 * Converts an ML Kit selfie mask to an [AlphaMask] in source-frame coordinates.
 *
 * The buffer holds one float per mask pixel, row-major. The value is the FOREGROUND confidence, which is
 * what the ML Kit sample computes by reading `1 - value` as the background likelihood. Values are read with
 * absolute indices so the buffer's position is not changed.
 *
 * A null result means "no confident person": the peak confidence is below [MIN_PEAK_CONFIDENCE], or fewer than
 * [MIN_FOREGROUND_FRACTION] of pixels reach 0.5. The caller then keeps the original background.
 */
internal object SegmentationMasks {
    const val MIN_PEAK_CONFIDENCE: Float = 0.6f
    const val MIN_FOREGROUND_FRACTION: Float = 0.02f
    private const val FOREGROUND_LEVEL: Float = 0.5f

    fun toAlphaMask(mask: SegmentationMask, frameWidth: Int, frameHeight: Int): AlphaMask? {
        val maskW = mask.width
        val maskH = mask.height
        val count = maskW * maskH
        val buffer = mask.buffer
        require(maskW > 0 && maskH > 0 && buffer.capacity() >= count * Float.SIZE_BYTES) {
            "Segmentation mask is ${maskW}x$maskH with ${buffer.capacity()} bytes"
        }
        val confidence = FloatArray(count)
        var peak = 0f
        var foreground = 0
        for (i in 0 until count) {
            val value = buffer.getFloat(i * Float.SIZE_BYTES)
            val clamped = if (value.isNaN()) 0f else value.coerceIn(0f, 1f)
            confidence[i] = clamped
            if (clamped > peak) peak = clamped
            if (clamped >= FOREGROUND_LEVEL) foreground++
        }
        if (peak < MIN_PEAK_CONFIDENCE) return null
        if (foreground.toFloat() / count < MIN_FOREGROUND_FRACTION) return null

        val bytes = ByteArray(frameWidth * frameHeight)
        for (y in 0 until frameHeight) {
            val srcRow = (y * maskH / frameHeight).coerceAtMost(maskH - 1) * maskW
            for (x in 0 until frameWidth) {
                val srcX = (x * maskW / frameWidth).coerceAtMost(maskW - 1)
                val level = confidence[srcRow + srcX] * 255f
                bytes[y * frameWidth + x] = (level + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
        }
        return AlphaMask(frameWidth, frameHeight, bytes)
    }
}
