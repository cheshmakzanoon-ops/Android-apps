package com.agency.stickerjet.ui.navigation

/**
 * Route patterns for the NavHost. The project id is a plain string argument, so no serialization
 * plugin is needed in the app module. Project ids are UUIDs and need no encoding.
 */
object Routes {
    const val WELCOME = "welcome"
    const val LIBRARY = "library"
    const val IMPORT = "import"
    const val SETTINGS = "settings"
    const val EDITOR = "editor/{projectId}"
    const val PROJECT_ID_ARG = "projectId"

    /** Editor route for one project. */
    fun editor(projectId: String): String = "editor/$projectId"
}
