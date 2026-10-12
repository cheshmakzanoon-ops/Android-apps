package com.agency.photoduel.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Every destination of the signed-in app, as type-safe Navigation Compose routes. Arguments are ids
 * only; screens load everything else from the service.
 *
 * Onboarding, sign-in and the "server not configured" state are not routes: the root chooses them from
 * the session phase, so a signed-out user can never navigate into the signed-in graph.
 */
@Serializable
sealed interface Route {
    @Serializable
    data object Home : Route

    @Serializable
    data object CreateRoom : Route

    @Serializable
    data class Room(val roomId: String) : Route

    /** New challenge. [previousPromptId] is set for a rematch so the last prompt is not preselected. */
    @Serializable
    data class CreateChallenge(val roomId: String, val previousPromptId: String? = null) : Route

    @Serializable
    data class Challenge(val challengeId: String) : Route

    @Serializable
    data class Submission(val challengeId: String) : Route

    @Serializable
    data class Voting(val challengeId: String) : Route

    @Serializable
    data class Results(val challengeId: String) : Route

    /** Invitation landing. Reachable by the deep link `photoduel://invite/{token}`. */
    @Serializable
    data class Invitation(val token: String) : Route

    @Serializable
    data object History : Route

    @Serializable
    data object Profile : Route

    @Serializable
    data object Settings : Route

    /** One in-app guideline page. [topic] is a [GuidelineTopic] name. */
    @Serializable
    data class Guideline(val topic: String) : Route

    @Serializable
    data object BlockedUsers : Route
}

enum class GuidelineTopic { COMMUNITY, PRIVACY, VOTING }

object DeepLinks {
    /** Base of the invitation link. The token follows as the last path segment. */
    const val INVITE_BASE = "photoduel://invite"
}
