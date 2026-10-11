package com.agency.photoduel.ui.state

import com.agency.photoduel.domain.model.AppResult
import com.agency.photoduel.domain.model.PhotoDuelError
import com.agency.photoduel.domain.model.UserProfile
import com.agency.photoduel.domain.port.PhotoDuelService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SignInMode { GUEST, RESTORE }

/** What to tell the user after restoring an account. */
sealed interface RestoreNotice {
    /** The recovery code was rotated by the restore; this is the replacement, shown once. */
    data class NewCode(val code: String) : RestoreNotice

    /** The old code no longer works and a new one could not be issued right now. */
    data object CodeNotIssued : RestoreNotice
}

data class SignInUi(
    val mode: SignInMode = SignInMode.GUEST,
    val displayName: String = "",
    val nameProblem: NameProblem? = null,
    val recoveryCode: String = "",
    val codeInvalid: Boolean = false,
    val submitting: Boolean = false,
    val error: PhotoDuelError? = null,
    val notice: RestoreNotice? = null,
    /** Set when sign-in is finished and the app can move on. Consume with [SignInState.consumeCompleted]. */
    val completedUser: UserProfile? = null,
)

/**
 * Guest sign-in (display name) and restore (recovery code). After a restore the server rotates the
 * recovery code but the frozen contract does not return the new one, so this class immediately issues a
 * fresh code and hands it to the screen to show once.
 */
class SignInState(
    private val scope: CoroutineScope,
    private val service: PhotoDuelService,
    private val network: NetworkStatus,
) {
    private val _state = MutableStateFlow(SignInUi())
    val state: StateFlow<SignInUi> = _state.asStateFlow()
    private var pendingUser: UserProfile? = null

    fun setMode(mode: SignInMode) = _state.update { it.copy(mode = mode, error = null) }

    fun onNameChanged(value: String) = _state.update { it.copy(displayName = value, nameProblem = null, error = null) }

    fun onCodeChanged(value: String) = _state.update { it.copy(recoveryCode = value, codeInvalid = false, error = null) }

    fun submitGuest() {
        val current = _state.value
        if (current.submitting) return
        val check = Inputs.displayName(current.displayName)
        val clean = check.clean
        if (clean == null) {
            _state.update { it.copy(nameProblem = check.problem) }
            return
        }
        _state.update { it.copy(submitting = true, error = null) }
        scope.launch {
            when (val result = network.track(service.createGuest(clean))) {
                is AppResult.Success -> _state.update { it.copy(submitting = false, completedUser = result.value.user) }
                is AppResult.Failure -> _state.update { it.copy(submitting = false, error = result.error) }
            }
        }
    }

    fun submitRestore() {
        val current = _state.value
        if (current.submitting) return
        val code = Inputs.recoveryCode(current.recoveryCode)
        if (code == null) {
            _state.update { it.copy(codeInvalid = true) }
            return
        }
        _state.update { it.copy(submitting = true, error = null) }
        scope.launch {
            when (val restored = network.track(service.restoreWithRecoveryCode(code))) {
                is AppResult.Failure -> _state.update { it.copy(submitting = false, error = restored.error) }
                is AppResult.Success -> {
                    pendingUser = restored.value.user
                    val notice = when (val issued = network.track(service.issueRecoveryCode())) {
                        is AppResult.Success -> RestoreNotice.NewCode(issued.value.code)
                        is AppResult.Failure -> RestoreNotice.CodeNotIssued
                    }
                    _state.update { it.copy(submitting = false, notice = notice) }
                }
            }
        }
    }

    /** The user saw the notice (and, for a new code, confirmed they saved it). Sign-in can finish. */
    fun acknowledgeNotice() {
        val user = pendingUser ?: return
        pendingUser = null
        _state.update { it.copy(notice = null, recoveryCode = "", completedUser = user) }
    }

    fun consumeCompleted() = _state.update { it.copy(completedUser = null) }
}
