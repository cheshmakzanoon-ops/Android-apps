package com.agency.photoduel.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.agency.photoduel.R
import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.PhotoDuelError
import com.agency.photoduel.ui.common.AppEnv
import com.agency.photoduel.ui.common.EmptyState
import com.agency.photoduel.ui.common.ErrorState
import com.agency.photoduel.ui.common.LoadingState
import com.agency.photoduel.ui.common.OfflineBanner
import com.agency.photoduel.ui.common.rememberScreenState
import com.agency.photoduel.ui.navigation.PhotoDuelNavHost
import com.agency.photoduel.ui.navigation.Route
import com.agency.photoduel.ui.navigation.SessionCallbacks
import com.agency.photoduel.ui.screens.onboarding.OnboardingScreen
import com.agency.photoduel.ui.screens.signin.SignInScreen
import com.agency.photoduel.ui.state.AppPhase
import com.agency.photoduel.ui.state.AppSessionState
import com.agency.photoduel.ui.state.NetworkStatus
import com.agency.photoduel.ui.theme.PhotoDuelTheme
import com.agency.photoduel.ui.theme.rememberSystemReduceMotion
import kotlinx.coroutines.CoroutineScope

/**
 * Entry point of the UI, called by the composition root (MainActivity) with the frozen dependencies.
 *
 * Onboarding completion: the frozen [UiDependencies] has no place to persist it, so here it is kept in
 * saved instance state only (it survives rotation and process death, not a fresh start). Callers that
 * can persist the flag use [PhotoDuelApp] directly and store it in `onOnboardingFinished`.
 */
@Composable
fun PhotoDuelRoot(deps: UiDependencies) {
    var onboardingCompleted by rememberSaveable { mutableStateOf(false) }
    PhotoDuelApp(
        deps = deps,
        onboardingCompleted = onboardingCompleted,
        onOnboardingFinished = { onboardingCompleted = true },
    )
}

/** Session-wide state, created once per activity and kept across configuration changes. */
class RootModel(scope: CoroutineScope, deps: UiDependencies, onboardingCompleted: Boolean) {
    val network = NetworkStatus()
    val session = AppSessionState(scope, deps.service, network, deps.serverConfigured, onboardingCompleted).also { it.start() }
}

/**
 * The whole app below the activity. [onboardingCompleted] is read once, when the session state is
 * created; [onOnboardingFinished] is the callback in which the caller stores the flag.
 *
 * [reduceMotion] overrides the system setting (null follows it). [liveClock] false stops the countdown
 * ticker, for UI tests. [darkTheme] defaults to the system setting.
 */
@Composable
fun PhotoDuelApp(
    deps: UiDependencies,
    onboardingCompleted: Boolean,
    onOnboardingFinished: () -> Unit,
    reduceMotion: Boolean? = null,
    liveClock: Boolean = true,
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
    val root = rememberScreenState("root") { scope -> RootModel(scope, deps, onboardingCompleted) }
    val systemReduce = rememberSystemReduceMotion()
    PhotoDuelTheme(darkTheme = darkTheme, reduceMotion = reduceMotion ?: systemReduce) {
        val app by root.session.state.collectAsStateWithLifecycle()
        val offline by root.network.offline.collectAsStateWithLifecycle()

        // Invitation links delivered by the platform. Kept until the user is signed in.
        LaunchedEffect(deps.pendingInviteTokens) {
            deps.pendingInviteTokens.collect { token -> root.session.inviteReceived(token) }
        }
        // Any 401 from the server sends a signed-in user back to sign-in.
        LaunchedEffect(root) {
            root.network.sessionRejected.collect { root.session.onSessionRejected() }
        }

        val env = remember(app.user?.id, liveClock) { AppEnv(deps, root.network, app.user?.id, liveClock) }
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                val showBanner = offline && app.phase == AppPhase.SIGNED_IN
                if (showBanner) OfflineBanner(modifier = Modifier.statusBarsPadding())
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .then(if (showBanner) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier),
                ) {
                    when (app.phase) {
                        AppPhase.ONBOARDING -> OnboardingScreen(
                            onFinished = {
                                onOnboardingFinished()
                                root.session.onboardingFinished()
                            },
                        )
                        AppPhase.SERVER_NOT_CONFIGURED -> EmptyState(
                            title = stringResource(R.string.setup_title),
                            message = stringResource(R.string.setup_body),
                            icon = Icons.Filled.Info,
                            modifier = Modifier.safeDrawingPadding(),
                        )
                        AppPhase.CHECKING_SESSION -> LoadingState(
                            label = stringResource(R.string.checking_session),
                            modifier = Modifier.safeDrawingPadding(),
                        )
                        AppPhase.CONNECTION_PROBLEM -> ErrorState(
                            error = app.error ?: NO_CONNECTION,
                            reconnecting = true,
                            onRetry = root.session::retry,
                            modifier = Modifier.safeDrawingPadding(),
                        )
                        AppPhase.SESSION_ERROR -> ErrorState(
                            error = app.error ?: NO_CONNECTION,
                            title = stringResource(R.string.session_error_title),
                            onRetry = root.session::retry,
                            modifier = Modifier.safeDrawingPadding(),
                        )
                        AppPhase.SIGNED_OUT -> SignInScreen(env = env, onSignedIn = root.session::onSignedIn)
                        AppPhase.SIGNED_IN -> SignedInApp(env = env, root = root, pendingInvite = app.pendingInviteToken)
                    }
                }
            }
        }
    }
}

/** Placeholder used only if a failure phase arrives without its error. The message is never shown. */
private val NO_CONNECTION = PhotoDuelError(ErrorCode.NETWORK, "", retryable = true)

@Composable
private fun SignedInApp(env: AppEnv, root: RootModel, pendingInvite: String?) {
    val navController = rememberNavController()
    PhotoDuelNavHost(
        navController = navController,
        env = env,
        session = remember(root) {
            SessionCallbacks(
                onProfileChanged = root.session::onProfileChanged,
                onSignedOut = root.session::onSignedOut,
                onAccountDeleted = root.session::onAccountDeleted,
            )
        },
    )
    LaunchedEffect(pendingInvite) {
        val token = pendingInvite ?: return@LaunchedEffect
        root.session.inviteConsumed()
        navController.navigate(Route.Invitation(token)) { launchSingleTop = true }
    }
}
