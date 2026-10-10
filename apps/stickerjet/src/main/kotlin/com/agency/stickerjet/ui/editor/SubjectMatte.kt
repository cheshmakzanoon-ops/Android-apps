package com.agency.stickerjet.ui.editor

import com.agency.stickerjet.domain.AlphaMask
import com.agency.stickerjet.domain.ArgbFrame

/** Radius presets for the brush, normalized to the shorter side of the frame (see MaskStroke.radius). */
enum class BrushSize(val radius: Float) {
    SMALL(0.02f),
    MEDIUM(0.05f),
    LARGE(0.10f),
}

/**
 * Builds the matte preview: the frame's pixels with the refined mask applied as alpha. Areas the mask
 * removes become transparent, so the checkerboard shows through them. Pure and deterministic.
 */
object SubjectMatte {

    /**
     * Returns [frame]'s pixels with each alpha multiplied by the mask. A null mask means nothing is
     * removed. The mask must be the same size as the frame.
     */
    fun compose(frame: ArgbFrame, alpha: AlphaMask?): IntArray {
        val source = frame.pixels
        if (alpha == null) return source.copyOf()
        require(alpha.width == frame.width && alpha.height == frame.height) {
            "Mask ${alpha.width}x${alpha.height} does not match frame ${frame.width}x${frame.height}"
        }
        val out = IntArray(source.size)
        for (i in source.indices) {
            val maskAlpha = alpha.alpha[i].toInt() and 0xFF
            val frameAlpha = (source[i] ushr 24) and 0xFF
            val combined = (maskAlpha * frameAlpha + HALF) / MAX_ALPHA
            out[i] = (combined shl 24) or (source[i] and RGB_MASK)
        }
        return out
    }

    private const val MAX_ALPHA = 255
    private const val HALF = 127
    private const val RGB_MASK = 0x00FFFFFF
}
