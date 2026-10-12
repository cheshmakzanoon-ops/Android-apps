package com.agency.photoduel.ui.state

import com.agency.photoduel.domain.model.AppResult
import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.PhotoDuelError
import com.agency.photoduel.domain.model.UserProfile
import com.agency.photoduel.domain.port.PhotoDuelService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppPhase { ONBOARDING, SERVER_NOT_CONFIGURED, CHECKING_SESSION, SIGNED_OUT, SIGNED_IN, CONNECTION_PROBLEM, SESSION_ERROR }

data class AppUi(
    val phase: AppPhase,
    val user: UserProfile? = null,
    val error: PhotoDuelError? = null,
    /** Invitation token received before the user was signed in. Consumed after sign-in. */
    val pendingInviteToken: String? = null,
)

/**
 * Decides what the app shows first: onboarding (once), the "server not configured" state, sign-in or
 * the signed-in app. The session token itself lives in the data layer; this class only asks the service
 * who the current user is.
 */
class AppSessionState(
    private val scope: CoroutineScope,
    private val service: PhotoDuelService,
    private val network: NetworkStatus,
    private val serverConfigured: Boolean,
    onboardingCompleted: Boolean,
) {
    private val _state = MutableStateFlow(
        AppUi(phase = if (onboardingCompleted) afterOnboarding() else AppPhase.ONBOARDING),
    )
    val state: StateFlow<AppUi> = _state.asStateFlow()

    private fun afterOnboarding(): AppPhase =
        if (serverConfigured) AppPhase.CHECKING_SESSION else AppPhase.SERVER_NOT_CONFIGURED

    /** Call once after creation. Runs the session check when the first phase needs it. */
    fun start() {
        if (_state.value.phase == AppPhase.CHECKING_SESSION) checkSession()
    }

    fun onboardingFinished() {
        if (_state.value.phase != AppPhase.ONBOARDING) return
        _state.update { it.copy(phase = afterOnboarding()) }
        start()
    }

    fun retry() {
        if (serverConfigured) {
            _state.update { it.copy(phase = AppPhase.CHECKING_SESSION, error = null) }
            checkSession()
        }
    }

    fun onSignedIn(user: UserProfile) {
        _state.update { it.copy(phase = AppPhase.SIGNED_IN, user = user, error = null) }
    }

    fun onProfileChanged(user: UserProfile) {
        _state.update { if (it.phase == AppPhase.SIGNED_IN) it.copy(user = user) else it }
    }

    /** Signs out locally after the service call. A failed logout call still ends the local session. */
    fun signOut() {
        scope.launch {
            network.track(service.logout())
            _state.update { it.copy(phase = AppPhase.SIGNED_OUT, user = null) }
        }
    }

    /**
     * The server refused the session on some call (401). The data layer has already dropped the token,
     * so a signed-in user goes back to sign-in. In any other phase this changes nothing: a wrong recovery
     * code on the sign-in screen also answers 401.
     */
    fun onSessionRejected() {
        _state.update {
            if (it.phase == AppPhase.SIGNED_IN) it.copy(phase = AppPhase.SIGNED_OUT, user = null, error = null) else it
        }
    }

    /** Called when another screen (the profile) already ran the logout call. */
    fun onSignedOut() {
        _state.update { it.copy(phase = AppPhase.SIGNED_OUT, user = null, error = null) }
    }

    /** Called after account deletion succeeded; the session is already revoked on the server. */
    fun onAccountDeleted() {
        _state.update { it.copy(phase = AppPhase.SIGNED_OUT, user = null) }
    }

    fun inviteReceived(token: String) {
        _state.update { it.copy(pendingInviteToken = token) }
    }

    fun inviteConsumed() {
        _state.update { it.copy(pendingInviteToken = null) }
    }

    private var checkJob: Job? = null

    private fun checkSession() {
        checkJob?.cancel()
        checkJob = scope.launch {
            var attempt = 0
            while (true) {
                when (val result = network.track(service.currentUser())) {
                    is AppResult.Success -> {
                        _state.update { it.copy(phase = AppPhase.SIGNED_IN, user = result.value, error = null) }
                        return@launch
                    }
                    is AppResult.Failure -> {
                        val error = result.error
                        when {
                            error.code == ErrorCode.UNAUTHENTICATED -> {
                                _state.update { it.copy(phase = AppPhase.SIGNED_OUT, user = null, error = null) }
                                return@launch
                            }
                            error.isConnectivity() -> {
                                // Keep trying while the app is open; the screen says it is reconnecting.
                                _state.update { it.copy(phase = AppPhase.CONNECTION_PROBLEM, error = error) }
                                val delays = Loader.DEFAULT_RETRY_DELAYS_MILLIS
                                delay(delays[minOf(attempt, delays.lastIndex)])
                                attempt++
                            }
                            else -> {
                                _state.update { it.copy(phase = AppPhase.SESSION_ERROR, error = error) }
                                return@launch
                            }
                        }
                    }
                }
            }
        }
    }
}
