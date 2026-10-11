package com.agency.photoduel.engine.pure

/**
 * Outcome of [CompressionPlanner.plan].
 *
 * @property quality the chosen JPEG quality. When [budgetMet] is true it is the highest quality in
 *   the searched range whose encoded size was within the budget. When false it is the lowest
 *   quality that was allowed, which is the closest the encoder can get.
 * @property budgetMet true when the encoder output at [quality] fits the budget.
 * @property sizeBytes the encoded size the encoder reported at [quality].
 * @property encoderCalls how many times the encoder was called.
 */
data class CompressionPlan(
    val quality: Int,
    val budgetMet: Boolean,
    val sizeBytes: Int,
    val encoderCalls: Int,
)

/**
 * Chooses a JPEG quality for a byte budget with a binary search.
 *
 * The encoder is any function from a quality (1 to 100) to the size in bytes of the image encoded at
 * that quality. Encoded size is assumed to grow with quality, which holds for JPEG up to noise of a
 * few bytes. For a monotone encoder the answer is exact. For a non-monotone one the search is still
 * deterministic and, when [CompressionPlan.budgetMet] is true, the chosen quality was actually
 * measured and fits.
 *
 * The first probe is the highest allowed quality, because most photos fit there and the search then
 * ends after one encode. Worst case for the full range 1..100 is 8 encoder calls.
 */
object CompressionPlanner {
    const val MIN_QUALITY: Int = 1
    const val MAX_QUALITY: Int = 100

    /**
     * @param budgetBytes the largest acceptable encoded size, positive.
     * @param minQuality lowest quality the search may return.
     * @param maxQuality highest quality the search may return (a preferred cap such as 85 is fine).
     * @param encoder returns the encoded size in bytes for a quality in [minQuality]..[maxQuality].
     */
    fun plan(
        budgetBytes: Int,
        minQuality: Int = MIN_QUALITY,
        maxQuality: Int = MAX_QUALITY,
        encoder: (quality: Int) -> Int,
    ): CompressionPlan {
        require(budgetBytes > 0) { "budgetBytes must be positive" }
        require(minQuality in MIN_QUALITY..MAX_QUALITY) { "minQuality out of range: $minQuality" }
        require(maxQuality in minQuality..MAX_QUALITY) { "maxQuality out of range: $maxQuality" }

        var calls = 0
        fun probe(quality: Int): Int {
            calls += 1
            return encoder(quality)
        }

        val topSize = probe(maxQuality)
        if (topSize <= budgetBytes) return CompressionPlan(maxQuality, true, topSize, calls)

        // Invariant: `low` fits (or is the virtual position just under minQuality), `high` does not.
        var low = minQuality - 1
        var lowSize = -1
        var high = maxQuality
        var highSize = topSize
        while (high - low > 1) {
            val mid = low + (high - low) / 2
            val size = probe(mid)
            if (size <= budgetBytes) {
                low = mid
                lowSize = size
            } else {
                high = mid
                highSize = size
            }
        }
        return if (low >= minQuality) {
            CompressionPlan(low, true, lowSize, calls)
        } else {
            // Even the lowest allowed quality (which `high` now equals) is over budget.
            CompressionPlan(minQuality, false, highSize, calls)
        }
    }
}
