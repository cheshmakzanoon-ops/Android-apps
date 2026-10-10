package com.agency.timeflip.domain.model

enum class TransitionKind {
    /** The AFTER photograph is uncovered left to right across the BEFORE photograph. */
    HORIZONTAL_REVEAL,
    /** The AFTER photograph is uncovered top to bottom. */
    VERTICAL_REVEAL,
    /** The AFTER photograph fades in over the BEFORE photograph. */
    CROSSFADE,
    /** A straight edge sweeps across the frame at [TransitionParameters.wipeAngleDegrees]. */
    DIRECTIONAL_WIPE,
}

enum class Easing { LINEAR, EASE_IN_OUT_CUBIC, EASE_OUT_CUBIC }

data class TransitionParameters(
    val durationMs: Int = DEFAULT_DURATION_MS,
    val easing: Easing = Easing.EASE_IN_OUT_CUBIC,
    /** Reverses the direction of motion: right-to-left, bottom-to-top, or opposite wipe direction. */
    val reversed: Boolean = false,
    /** Direction of wipe travel in degrees; 0 moves left to right, 90 moves top to bottom. */
    val wipeAngleDegrees: Double = 0.0,
) {
    init {
        require(durationMs in MIN_DURATION_MS..MAX_DURATION_MS) {
            "durationMs must be within $MIN_DURATION_MS..$MAX_DURATION_MS, was $durationMs"
        }
        require(wipeAngleDegrees.isFinite()) { "wipeAngleDegrees must be finite" }
    }

    companion object {
        const val MIN_DURATION_MS = 500
        const val MAX_DURATION_MS = 3000
        const val DEFAULT_DURATION_MS = 1200
    }
}

data class TransitionDefinition(
    val kind: TransitionKind,
    val parameters: TransitionParameters = TransitionParameters(),
)
