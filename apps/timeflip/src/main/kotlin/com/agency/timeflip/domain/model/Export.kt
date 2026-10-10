package com.agency.timeflip.domain.model

import com.agency.timeflip.domain.error.TimeFlipError

/** Output frame presets. Dimensions are the exact encoded frame size (portrait is height > width). */
enum class OutputPreset(val width: Int, val height: Int, val label: String) {
    PORTRAIT_720(720, 1280, "720p portrait"),
    PORTRAIT_1080(1080, 1920, "1080p portrait"),
    SQUARE_1080(1080, 1080, "1080p square"),
    LANDSCAPE_1080(1920, 1080, "1080p landscape"),
    ;

    val frame: PixelSize get() = PixelSize(width, height)

    /** Lower-resolution preset with the same orientation, used when a device cannot encode this one. */
    fun fallback(): OutputPreset? = when (this) {
        PORTRAIT_720 -> null
        PORTRAIT_1080 -> PORTRAIT_720
        SQUARE_1080 -> null
        LANDSCAPE_1080 -> null
    }
}

data class ExportProfile(
    val preset: OutputPreset = OutputPreset.PORTRAIT_1080,
    val fps: Int = TimelinePlan.DEFAULT_FPS,
) {
    init {
        require(fps in setOf(24, 25, 30, 60)) { "Unsupported fps $fps" }
    }

    /** Constant-bitrate target scaled by pixel count and frame rate, clamped to a safe range. */
    val bitrateBps: Int
        get() {
            val raw = preset.width.toDouble() * preset.height * fps * BITS_PER_PIXEL_FRAME
            return raw.coerceIn(MIN_BITRATE_BPS.toDouble(), MAX_BITRATE_BPS.toDouble()).toInt()
        }

    companion object {
        const val BITS_PER_PIXEL_FRAME = 0.1
        const val MIN_BITRATE_BPS = 4_000_000
        const val MAX_BITRATE_BPS = 16_000_000
    }
}

data class ExportRequest(
    val projectId: ProjectId,
    val projectName: String,
    val pair: ImagePair,
    val alignment: AlignmentTransform,
    val transition: TransitionDefinition,
    val timeline: TimelinePlan,
    val profile: ExportProfile,
) {
    init {
        require(timeline.fps == profile.fps) { "Timeline and export profile must use the same fps" }
        require(timeline.transitionMs == transition.parameters.durationMs) {
            "Timeline transition duration must match the transition definition"
        }
    }
}

/** A finished MP4 recorded in the project library. The file location is resolved by the data layer. */
data class ExportedVideo(
    val projectId: ProjectId,
    val fileName: String,
    val sizeBytes: Long,
    val durationMs: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val createdAtEpochMs: Long,
)

sealed interface ExportProgress {
    data class Rendering(val framesDone: Int, val framesTotal: Int) : ExportProgress {
        init {
            require(framesTotal > 0 && framesDone in 0..framesTotal) { "invalid frame counts" }
        }

        val fraction: Float get() = framesDone.toFloat() / framesTotal
    }

    data class Finished(val video: ExportedVideo) : ExportProgress

    data class Failed(val error: TimeFlipError) : ExportProgress

    data object Cancelled : ExportProgress
}
