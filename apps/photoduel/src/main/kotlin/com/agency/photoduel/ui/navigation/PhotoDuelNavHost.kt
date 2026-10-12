package com.agency.photoduel.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import com.agency.photoduel.domain.model.UserProfile
import com.agency.photoduel.ui.common.AppEnv
import com.agency.photoduel.ui.screens.challenge.ChallengeDetailScreen
import com.agency.photoduel.ui.screens.challenge.CreateChallengeScreen
import com.agency.photoduel.ui.screens.history.HistoryScreen
import com.agency.photoduel.ui.screens.home.HomeScreen
import com.agency.photoduel.ui.screens.invitation.InvitationScreen
import com.agency.photoduel.ui.screens.profile.ProfileScreen
import com.agency.photoduel.ui.screens.results.ResultsScreen
import com.agency.photoduel.ui.screens.room.CreateRoomScreen
import com.agency.photoduel.ui.screens.room.RoomDetailScreen
import com.agency.photoduel.ui.screens.safety.BlockedUsersScreen
import com.agency.photoduel.ui.screens.settings.GuidelineScreen
import com.agency.photoduel.ui.screens.settings.SettingsScreen
import com.agency.photoduel.ui.screens.submission.SubmissionScreen
import com.agency.photoduel.ui.screens.voting.VotingScreen
import com.agency.photoduel.ui.theme.LocalReduceMotion
import com.agency.photoduel.ui.theme.PdMotion

/** What the signed-in graph reports back to the root. */
class SessionCallbacks(
    val onProfileChanged: (UserProfile) -> Unit,
    val onSignedOut: () -> Unit,
    val onAccountDeleted: () -> Unit,
)

/** Pops one screen, but never the start destination (which would leave an empty host). */
fun NavHostController.back() {
    if (previousBackStackEntry != null) popBackStack()
}

/** Opens a room as the next screen after Home, replacing whatever led to it (create, invitation). */
private fun NavHostController.openRoomFresh(roomId: String) {
    navigate(Route.Room(roomId)) {
        popUpTo<Route.Home> { inclusive = false }
        launchSingleTop = true
    }
}

/**
 * The signed-in graph. Start destination is [Route.Home]. The invitation route also answers the deep
 * link `photoduel://invite/{token}`; in the app, links normally arrive through
 * `UiDependencies.pendingInviteTokens` and the root navigates to [Route.Invitation].
 */
@Composable
fun PhotoDuelNavHost(navController: NavHostController, env: AppEnv, session: SessionCallbacks, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val duration = if (reduceMotion) 0 else PdMotion.MEDIUM_MS
    NavHost(
        navController = navController,
        startDestination = Route.Home,
        modifier = modifier,
        // A plain cross-fade, or an instant switch with reduced motion. Nothing slides.
        enterTransition = { if (reduceMotion) EnterTransition.None else fadeIn(tween(duration)) },
        exitTransition = { if (reduceMotion) ExitTransition.None else fadeOut(tween(duration)) },
        popEnterTransition = { if (reduceMotion) EnterTransition.None else fadeIn(tween(duration)) },
        popExitTransition = { if (reduceMotion) ExitTransition.None else fadeOut(tween(duration)) },
    ) {
        composable<Route.Home> {
            HomeScreen(
                env = env,
                onOpenRoom = { navController.navigate(Route.Room(it)) },
                onCreateRoom = { navController.navigate(Route.CreateRoom) },
                onOpenInvitation = { navController.navigate(Route.Invitation(it)) },
                onOpenHistory = { navController.navigate(Route.History) },
                onOpenProfile = { navController.navigate(Route.Profile) },
                onOpenSettings = { navController.navigate(Route.Settings) },
            )
        }
        composable<Route.CreateRoom> {
            CreateRoomScreen(env = env, onBack = navController::back, onCreated = { navController.openRoomFresh(it) })
        }
        composable<Route.Room> { entry ->
            val route = entry.toRoute<Route.Room>()
            RoomDetailScreen(
                env = env,
                roomId = route.roomId,
                onBack = navController::back,
                onLeft = { navController.popBackStack<Route.Home>(inclusive = false) },
                onOpenChallenge = { navController.navigate(Route.Challenge(it)) },
                onOpenResults = { navController.navigate(Route.Results(it)) },
                onCreateChallenge = { previous -> navController.navigate(Route.CreateChallenge(route.roomId, previous)) },
            )
        }
        composable<Route.CreateChallenge> { entry ->
            val route = entry.toRoute<Route.CreateChallenge>()
            CreateChallengeScreen(
                env = env,
                roomId = route.roomId,
                previousPromptId = route.previousPromptId,
                onBack = navController::back,
                onCreated = { challengeId ->
                    navController.navigate(Route.Challenge(challengeId)) {
                        popUpTo<Route.CreateChallenge> { inclusive = true }
                    }
                },
            )
        }
        composable<Route.Challenge> { entry ->
            val route = entry.toRoute<Route.Challenge>()
            ChallengeDetailScreen(
                env = env,
                challengeId = route.challengeId,
                onBack = navController::back,
                onOpenSubmission = { navController.navigate(Route.Submission(route.challengeId)) },
                onOpenVoting = { navController.navigate(Route.Voting(route.challengeId)) },
                onOpenResults = { navController.navigate(Route.Results(route.challengeId)) },
            )
        }
        composable<Route.Submission> { entry ->
            val route = entry.toRoute<Route.Submission>()
            SubmissionScreen(env = env, challengeId = route.challengeId, onBack = navController::back)
        }
        composable<Route.Voting> { entry ->
            val route = entry.toRoute<Route.Voting>()
            VotingScreen(
                env = env,
                challengeId = route.challengeId,
                onBack = navController::back,
                onOpenResults = {
                    navController.navigate(Route.Results(route.challengeId)) {
                        popUpTo<Route.Voting> { inclusive = true }
                    }
                },
            )
        }
        composable<Route.Results> { entry ->
            val route = entry.toRoute<Route.Results>()
            ResultsScreen(
                env = env,
                challengeId = route.challengeId,
                onBack = navController::back,
                onOpenRoom = { navController.openRoomFresh(it) },
                onRematch = { roomId, previous -> navController.navigate(Route.CreateChallenge(roomId, previous)) },
            )
        }
        composable<Route.Invitation>(
            deepLinks = listOf(navDeepLink<Route.Invitation>(basePath = DeepLinks.INVITE_BASE)),
        ) { entry ->
            val route = entry.toRoute<Route.Invitation>()
            InvitationScreen(
                env = env,
                token = route.token,
                onBack = navController::back,
                onJoined = { navController.openRoomFresh(it) },
                onGoHome = { navController.popBackStack<Route.Home>(inclusive = false) },
            )
        }
        composable<Route.History> {
            HistoryScreen(
                env = env,
                onBack = navController::back,
                onOpenChallenge = { navController.navigate(Route.Challenge(it)) },
                onOpenResults = { navController.navigate(Route.Results(it)) },
            )
        }
        composable<Route.Profile> {
            ProfileScreen(
                env = env,
                onBack = navController::back,
                onProfileChanged = session.onProfileChanged,
                onSignedOut = session.onSignedOut,
                onAccountDeleted = session.onAccountDeleted,
                onOpenSettings = { navController.navigate(Route.Settings) },
                onOpenBlocked = { navController.navigate(Route.BlockedUsers) },
            )
        }
        composable<Route.Settings> {
            SettingsScreen(
                env = env,
                onBack = navController::back,
                onOpenGuideline = { navController.navigate(Route.Guideline(it.name)) },
                onOpenBlocked = { navController.navigate(Route.BlockedUsers) },
            )
        }
        composable<Route.Guideline> { entry ->
            val route = entry.toRoute<Route.Guideline>()
            val topic = GuidelineTopic.entries.firstOrNull { it.name == route.topic } ?: GuidelineTopic.COMMUNITY
            GuidelineScreen(topic = topic, onBack = navController::back)
        }
        composable<Route.BlockedUsers> {
            BlockedUsersScreen(env = env, onBack = navController::back)
        }
    }
}
