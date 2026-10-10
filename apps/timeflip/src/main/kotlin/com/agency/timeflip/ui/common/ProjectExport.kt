package com.agency.timeflip.ui.common

import com.agency.timeflip.domain.model.ExportRequest
import com.agency.timeflip.domain.model.TimeFlipProject
import com.agency.timeflip.domain.model.TimelinePlan

/**
 * The single timeline definition used by the live preview and by export. Both screens must call
 * this so that a preview always shows the frames the exporter will write.
 */
fun TimeFlipProject.timeline(): TimelinePlan = TimelinePlan(
    transitionMs = transition.parameters.durationMs,
    fps = exportProfile.fps,
)

fun TimeFlipProject.toExportRequest(): ExportRequest = ExportRequest(
    projectId = id,
    projectName = name,
    pair = pair,
    alignment = alignment,
    transition = transition,
    timeline = timeline(),
    profile = exportProfile,
)
