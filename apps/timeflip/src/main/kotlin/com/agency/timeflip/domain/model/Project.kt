package com.agency.timeflip.domain.model

/** Where the current alignment came from. */
enum class AlignmentSource {
    /** The photographs have not been aligned yet. */
    UNALIGNED,
    /** Placed by feature-based estimation. */
    AUTOMATIC,
    /** Placed by the user, possibly starting from an automatic estimate. */
    MANUAL,
}

enum class AlignmentStatus {
    /** Confidence is high enough to use the estimate directly. */
    ACCEPTED,
    /** The estimate is too uncertain. The user must adjust manually. */
    NEEDS_MANUAL,
    /** No usable estimate could be produced. */
    FAILED,
}

data class AlignmentEstimate(
    val transform: AlignmentTransform,
    val confidence: Double,
    val inlierCount: Int,
    val status: AlignmentStatus,
) {
    init {
        require(confidence.isFinite() && confidence in 0.0..1.0) { "confidence must be within 0..1" }
        require(inlierCount >= 0) { "inlierCount must not be negative" }
    }

    companion object {
        /** Minimum confidence at which an automatic estimate is accepted without manual review. */
        const val ACCEPT_CONFIDENCE = 0.6
    }
}

data class TimeFlipProject(
    val id: ProjectId,
    val name: String,
    val pair: ImagePair,
    val alignment: AlignmentTransform,
    val alignmentSource: AlignmentSource,
    val alignmentConfidence: Double?,
    val transition: TransitionDefinition,
    val exportProfile: ExportProfile,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
) {
    init {
        require(name.isNotBlank()) { "Project name must not be blank" }
        require(updatedAtEpochMs >= createdAtEpochMs) { "updatedAt must not precede createdAt" }
    }
}

/** Lightweight row for the project library. */
data class ProjectSummary(
    val id: ProjectId,
    val name: String,
    val updatedAtEpochMs: Long,
    val exportCount: Int,
    val thumbnailPhotoId: PhotoId?,
)
