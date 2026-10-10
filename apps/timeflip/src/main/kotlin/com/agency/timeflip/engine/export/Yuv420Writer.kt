package com.agency.timeflip.engine.export

import java.nio.ByteBuffer

/**
 * Converts packed ARGB_8888 pixels to 4:2:0 YUV with BT.601 coefficients and limited range
 * (Y in 16..235, U and V in 16..240). Chroma is the average of each 2×2 block of RGB.
 *
 * Output goes through [Plane] descriptors, so the same code writes codec Image planes (with their
 * row and pixel strides) and packed input buffers (planar or semi-planar).
 */
internal object Yuv420Writer {

    /** Where one plane starts in [buffer], and how its rows and samples are laid out. */
    class Plane(val buffer: ByteBuffer, val offset: Int, val rowStride: Int, val pixelStride: Int)

    /** [width] and [height] must be even. Presets always are. */
    fun write(argb: IntArray, width: Int, height: Int, yPlane: Plane, uPlane: Plane, vPlane: Plane) {
        for (row in 0 until height) {
            val source = row * width
            val target = yPlane.offset + row * yPlane.rowStride
            for (col in 0 until width) {
                val p = argb[source + col]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val luma = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yPlane.buffer.put(target + col * yPlane.pixelStride, luma.toByte())
            }
        }

        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val i0 = (row * 2) * width + col * 2
                val p0 = argb[i0]
                val p1 = argb[i0 + 1]
                val p2 = argb[i0 + width]
                val p3 = argb[i0 + width + 1]
                val r = (channelSum(p0, p1, p2, p3, 16) + 2) shr 2
                val g = (channelSum(p0, p1, p2, p3, 8) + 2) shr 2
                val b = (channelSum(p0, p1, p2, p3, 0) + 2) shr 2
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                uPlane.buffer.put(uPlane.offset + row * uPlane.rowStride + col * uPlane.pixelStride, u.toByte())
                vPlane.buffer.put(vPlane.offset + row * vPlane.rowStride + col * vPlane.pixelStride, v.toByte())
            }
        }
    }

    private fun channelSum(p0: Int, p1: Int, p2: Int, p3: Int, shift: Int): Int =
        ((p0 shr shift) and 0xFF) + ((p1 shr shift) and 0xFF) + ((p2 shr shift) and 0xFF) + ((p3 shr shift) and 0xFF)
}
