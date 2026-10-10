package com.agency.stickerjet.domain

/**
 * What the container probe reported for a picked file. The data layer fills it in. Any field may be
 * missing, because some containers do not expose it.
 */
data class ProbeResult(
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val rotationDegrees: Int,
    val hasVideoTrack: Boolean,
    val sizeBytes: Long,
)

/** Why an import was refused. Every value needs a user-visible message in the UI. */
enum class ImportRejection { NOT_A_VIDEO, UNREADABLE, TOO_SHORT, TOO_LONG, TOO_LARGE }

sealed interface ImportDecision {
    /**
     * The clip can be imported. [orientedWidth] and [orientedHeight] are the display size after rotation.
     * [rotationDegrees] is normalized to 0..359.
     */
    data class Accepted(
        val durationMs: Long,
        val orientedWidth: Int,
        val orientedHeight: Int,
        val rotationDegrees: Int,
    ) : ImportDecision

    data class Rejected(val reason: ImportRejection) : ImportDecision
}

/**
 * Accepts or refuses a probed source clip.
 *
 * The checks run in this order, and the first failure is the reported reason:
 * 1. NOT_A_VIDEO: the file has no video track.
 * 2. UNREADABLE: duration, width or height is missing or not positive.
 * 3. TOO_SHORT: duration < [StickerLimits.MIN_STICKER_MS]. A clip of exactly the minimum is accepted.
 * 4. TOO_LONG: duration > [StickerLimits.MAX_SOURCE_MS]. A clip of exactly the maximum is accepted.
 * 5. TOO_LARGE: sizeBytes > [StickerLimits.MAX_SOURCE_BYTES]. A file of exactly the maximum is accepted.
 *
 * Rotation is normalized modulo 360 first, so -90 counts as 270. For 90 and 270 the oriented size swaps
 * width and height. The container reports only multiples of 90, and any other value does not swap.
 */
object ImportPolicy {

    fun evaluate(probe: ProbeResult): ImportDecision {
        if (!probe.hasVideoTrack) return ImportDecision.Rejected(ImportRejection.NOT_A_VIDEO)

        val duration = probe.durationMs
        val width = probe.width
        val height = probe.height
        if (duration == null || duration <= 0L ||
            width == null || width <= 0 ||
            height == null || height <= 0
        ) {
            return ImportDecision.Rejected(ImportRejection.UNREADABLE)
        }
        if (duration < StickerLimits.MIN_STICKER_MS) return ImportDecision.Rejected(ImportRejection.TOO_SHORT)
        if (duration > StickerLimits.MAX_SOURCE_MS) return ImportDecision.Rejected(ImportRejection.TOO_LONG)
        if (probe.sizeBytes > StickerLimits.MAX_SOURCE_BYTES) return ImportDecision.Rejected(ImportRejection.TOO_LARGE)

        val rotation = Math.floorMod(probe.rotationDegrees, FULL_TURN_DEGREES)
        val swapsAxes = rotation == 90 || rotation == 270
        return ImportDecision.Accepted(
            durationMs = duration,
            orientedWidth = if (swapsAxes) height else width,
            orientedHeight = if (swapsAxes) width else height,
            rotationDegrees = rotation,
        )
    }

    private const val FULL_TURN_DEGREES = 360
}
