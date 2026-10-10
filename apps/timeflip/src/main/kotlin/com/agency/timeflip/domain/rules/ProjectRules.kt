package com.agency.timeflip.domain.rules

import com.agency.timeflip.domain.model.AlignmentEstimate
import com.agency.timeflip.domain.model.AlignmentSource
import com.agency.timeflip.domain.model.AlignmentTransform
import com.agency.timeflip.domain.model.ExportProfile
import com.agency.timeflip.domain.model.ImagePair
import com.agency.timeflip.domain.model.ProjectId
import com.agency.timeflip.domain.model.SourcePhoto
import com.agency.timeflip.domain.model.TimeFlipProject
import com.agency.timeflip.domain.model.TransitionDefinition
import com.agency.timeflip.domain.model.TransitionKind
import com.agency.timeflip.domain.model.TransitionParameters

/** Pure, non-destructive edits to a project. Every function returns a new project value. */
object ProjectRules {

    const val MAX_NAME_LENGTH = 60
    const val DEFAULT_NAME = "Untitled transformation"

    fun normalizeName(raw: String): String {
        val collapsed = raw.trim().replace(Regex("\\s+"), " ")
        if (collapsed.isEmpty()) return DEFAULT_NAME
        return if (collapsed.length <= MAX_NAME_LENGTH) collapsed else collapsed.take(MAX_NAME_LENGTH).trimEnd()
    }

    fun newProject(
        id: ProjectId,
        rawName: String,
        pair: ImagePair,
        nowEpochMs: Long,
    ): TimeFlipProject = TimeFlipProject(
        id = id,
        name = normalizeName(rawName),
        pair = pair,
        alignment = AlignmentTransform.IDENTITY,
        alignmentSource = AlignmentSource.UNALIGNED,
        alignmentConfidence = null,
        transition = TransitionDefinition(kind = TransitionKind.CROSSFADE),
        exportProfile = ExportProfile(),
        createdAtEpochMs = nowEpochMs,
        updatedAtEpochMs = nowEpochMs,
    )

    fun rename(project: TimeFlipProject, rawName: String, nowEpochMs: Long): TimeFlipProject =
        project.copy(name = normalizeName(rawName), updatedAtEpochMs = nowEpochMs)

    /** Applies an automatic estimate. Estimates below the acceptance threshold are still stored for review. */
    fun applyEstimate(project: TimeFlipProject, estimate: AlignmentEstimate, nowEpochMs: Long): TimeFlipProject =
        project.copy(
            alignment = estimate.transform,
            alignmentSource = AlignmentSource.AUTOMATIC,
            alignmentConfidence = estimate.confidence,
            updatedAtEpochMs = nowEpochMs,
        )

    fun applyManualAlignment(project: TimeFlipProject, transform: AlignmentTransform, nowEpochMs: Long): TimeFlipProject =
        project.copy(
            alignment = transform,
            alignmentSource = AlignmentSource.MANUAL,
            updatedAtEpochMs = nowEpochMs,
        )

    fun resetAlignment(project: TimeFlipProject, nowEpochMs: Long): TimeFlipProject =
        project.copy(
            alignment = AlignmentTransform.IDENTITY,
            alignmentSource = AlignmentSource.UNALIGNED,
            alignmentConfidence = null,
            updatedAtEpochMs = nowEpochMs,
        )

    /** Swapping changes the reference frame, so the alignment is cleared rather than guessed. */
    fun swapPhotos(project: TimeFlipProject, nowEpochMs: Long): TimeFlipProject =
        resetAlignment(project.copy(pair = project.pair.swapped()), nowEpochMs)

    /** Replacing either photograph also clears the alignment, which depended on the old image. */
    fun replacePhoto(project: TimeFlipProject, photo: SourcePhoto, nowEpochMs: Long): TimeFlipProject =
        resetAlignment(project.copy(pair = project.pair.withReplaced(photo)), nowEpochMs)

    fun setTransition(project: TimeFlipProject, transition: TransitionDefinition, nowEpochMs: Long): TimeFlipProject =
        project.copy(transition = transition, updatedAtEpochMs = nowEpochMs)

    fun setTransitionDuration(project: TimeFlipProject, durationMs: Int, nowEpochMs: Long): TimeFlipProject {
        val clamped = durationMs.coerceIn(TransitionParameters.MIN_DURATION_MS, TransitionParameters.MAX_DURATION_MS)
        val parameters = project.transition.parameters.copy(durationMs = clamped)
        return setTransition(project, project.transition.copy(parameters = parameters), nowEpochMs)
    }

    fun setExportProfile(project: TimeFlipProject, profile: ExportProfile, nowEpochMs: Long): TimeFlipProject =
        project.copy(exportProfile = profile, updatedAtEpochMs = nowEpochMs)
}
