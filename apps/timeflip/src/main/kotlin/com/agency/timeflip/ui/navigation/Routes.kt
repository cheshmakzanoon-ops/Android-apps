package com.agency.timeflip.ui.navigation

import com.agency.timeflip.domain.model.ProjectId
import com.agency.timeflip.ui.common.ROUTE_ARG_PROJECT_ID

/**
 * Route patterns and builders. Each project screen takes its id as a route argument, so the
 * ViewModels read it from SavedStateHandle.
 */
object Routes {
    const val WELCOME = "welcome"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"

    /** True when manual alignment was opened from the alignment review, so Done continues to the editor. */
    const val ARG_WIZARD = "wizard"

    /** Optional project id. Without it the screen creates a new transformation. */
    const val NEW_PAIR = "newPair?$ROUTE_ARG_PROJECT_ID={$ROUTE_ARG_PROJECT_ID}"
    const val ALIGN_REVIEW = "alignReview/{$ROUTE_ARG_PROJECT_ID}"
    const val MANUAL_ALIGN = "manualAlign/{$ROUTE_ARG_PROJECT_ID}?$ARG_WIZARD={$ARG_WIZARD}"
    const val EDITOR = "editor/{$ROUTE_ARG_PROJECT_ID}"
    const val EXPORT = "export/{$ROUTE_ARG_PROJECT_ID}"

    fun newPair(projectId: ProjectId? = null): String =
        if (projectId == null) "newPair" else "newPair?$ROUTE_ARG_PROJECT_ID=${projectId.value}"

    fun alignReview(id: ProjectId): String = "alignReview/${id.value}"

    fun manualAlign(id: ProjectId, fromWizard: Boolean): String =
        "manualAlign/${id.value}?$ARG_WIZARD=$fromWizard"

    fun editor(id: ProjectId): String = "editor/${id.value}"

    fun export(id: ProjectId): String = "export/${id.value}"
}
