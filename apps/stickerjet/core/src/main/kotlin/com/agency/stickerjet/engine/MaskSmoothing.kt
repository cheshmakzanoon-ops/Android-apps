package com.agency.stickerjet.engine

/**
 * Edge smoothing for a float alpha buffer: two separable box-blur passes (horizontal and vertical, twice)
 * with replicated borders, then a smoothstep soft threshold from [LOW] to [HIGH].
 *
 * Why this shape: the blur spreads an edge over at most `2 * radius` pixels, and the threshold removes
 * the faint tail below [LOW], which is where colour halos come from. Values above [HIGH] become opaque
 * and values below [LOW] transparent, so flicker from one-pixel confidence noise between frames is
 * suppressed. The 0.5 crossing is preserved, so the outline does not move. Radius 1 is used because a
 * radius of 2 or more erases discs of 2 to 4 px; see the simulation notes in MaskRefiner.
 */
internal object MaskSmoothing {
    const val LOW: Float = 0.35f
    const val HIGH: Float = 0.65f

    fun smooth(alpha: FloatArray, width: Int, height: Int, radius: Int) {
        require(radius >= 1) { "Blur radius must be at least 1 pixel" }
        val first = FloatArray(alpha.size)
        val second = FloatArray(alpha.size)
        boxPass(alpha, first, width, height, radius, horizontal = true)
        boxPass(first, second, width, height, radius, horizontal = false)
        boxPass(second, first, width, height, radius, horizontal = true)
        boxPass(first, second, width, height, radius, horizontal = false)
        for (i in alpha.indices) {
            alpha[i] = softThreshold(second[i])
        }
    }

    /** smoothstep over [LOW]..[HIGH]: 0 below, 1 above, cubic in between. */
    fun softThreshold(value: Float): Float {
        val t = ((value - LOW) / (HIGH - LOW)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** One box pass along rows (horizontal) or columns (vertical), using a running sum. */
    private fun boxPass(src: FloatArray, dst: FloatArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val step = if (horizontal) 1 else width
        val lineStep = if (horizontal) width else 1
        val norm = 1f / (2 * radius + 1)
        val last = length - 1
        for (line in 0 until lines) {
            val base = line * lineStep
            var sum = 0f
            for (k in -radius..radius) {
                sum += src[base + k.coerceIn(0, last) * step]
            }
            for (i in 0 until length) {
                dst[base + i * step] = sum * norm
                sum += src[base + (i + radius + 1).coerceIn(0, last) * step] -
                    src[base + (i - radius).coerceIn(0, last) * step]
            }
        }
    }
}
