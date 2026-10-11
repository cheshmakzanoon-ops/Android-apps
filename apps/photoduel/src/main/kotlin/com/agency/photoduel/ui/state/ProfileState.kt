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

data class ProfileUi(
    val renaming: Boolean = false,
    val renameText: String = "",
    val renameProblem: NameProblem? = null,
    val busy: Boolean = false,
    /** The recovery code just issued. Shown once; cleared by [ProfileState.dismissRecoveryCode]. */
    val recoveryCode: String? = null,
    val confirmRecoveryReplace: Boolean = false,
    val deleting: Boolean = false,
    val deleteText: String = "",
    val error: PhotoDuelError? = null,
    val signedOut: Boolean = false,
    val accountDeleted: Boolean = false,
) {
    val deleteConfirmed: Boolean get() = Inputs.isDeleteConfirmed(deleteText)
}

/** Profile: rename, issue a recovery code (shown once), sign out and delete the account. */
class ProfileState(
    private val scope: CoroutineScope,
    private val service: PhotoDuelService,
    private val network: NetworkStatus,
    private val onProfileChanged: (UserProfile) -> Unit = {},
) {
    private val user = Loader(scope, network, onLoaded = { onProfileChanged(it) }) { service.currentUser() }
    val profile: StateFlow<Loadable<UserProfile>> = user.state

    private val _ui = MutableStateFlow(ProfileUi())
    val ui: StateFlow<ProfileUi> = _ui.asStateFlow()

    init {
        user.load()
    }

    fun reload() = user.load()

    fun dismissError() = _ui.update { it.copy(error = null) }

    // ---- Rename
    fun startRename() = _ui.update { it.copy(renaming = true, renameText = user.value?.displayName.orEmpty(), renameProblem = null) }

    fun cancelRename() = _ui.update { it.copy(renaming = false, renameProblem = null) }

    fun onRenameTextChanged(value: String) = _ui.update { it.copy(renameText = value, renameProblem = null) }

    fun saveRename() {
        val current = _ui.value
        if (current.busy) return
        val check = Inputs.displayName(current.renameText)
        val clean = check.clean
        if (clean == null) {
            _ui.update { it.copy(renameProblem = check.problem) }
            return
        }
        _ui.update { it.copy(busy = true, error = null) }
        scope.launch {
            when (val result = network.track(service.renameSelf(clean))) {
                is AppResult.Success -> {
                    user.update { result.value }
                    onProfileChanged(result.value)
                    _ui.update { it.copy(busy = false, renaming = false) }
                }
                is AppResult.Failure -> _ui.update { it.copy(busy = false, error = result.error) }
            }
        }
    }

    // ---- Recovery code
    /** Asks first when a code already exists, because issuing a new one replaces it. */
    fun requestRecoveryCode() {
        if (user.value?.hasRecoveryCode == true) _ui.update { it.copy(confirmRecoveryReplace = true) } else issueRecoveryCode()
    }

    fun dismissReplaceConfirm() = _ui.update { it.copy(confirmRecoveryReplace = false) }

    fun issueRecoveryCode() {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, confirmRecoveryReplace = false, error = null) }
        scope.launch {
            when (val result = network.track(service.issueRecoveryCode())) {
                is AppResult.Success -> {
                    _ui.update { it.copy(busy = false, recoveryCode = result.value.code) }
                    user.update { it.copy(hasRecoveryCode = true) }
                }
                is AppResult.Failure -> _ui.update { it.copy(busy = false, error = result.error) }
            }
        }
    }

    /** Called when the user confirms they saved the code. The code is no longer kept anywhere. */
    fun dismissRecoveryCode() = _ui.update { it.copy(recoveryCode = null) }

    // ---- Sign out
    fun signOut() {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true) }
        scope.launch {
            // A failed logout call still ends the local session; the token is dropped by the data layer.
            network.track(service.logout())
            _ui.update { it.copy(busy = false, signedOut = true) }
        }
    }

    // ---- Delete account
    fun startDelete() = _ui.update { it.copy(deleting = true, deleteText = "", error = null) }

    fun cancelDelete() = _ui.update { it.copy(deleting = false, deleteText = "") }

    fun onDeleteTextChanged(value: String) = _ui.update { it.copy(deleteText = value) }

    fun confirmDelete() {
        val current = _ui.value
        if (current.busy || !current.deleteConfirmed) return
        _ui.update { it.copy(busy = true, error = null) }
        scope.launch {
            when (val result = network.track(service.deleteAccount())) {
                is AppResult.Success -> _ui.update { it.copy(busy = false, deleting = false, accountDeleted = true) }
                is AppResult.Failure -> _ui.update { it.copy(busy = false, error = result.error) }
            }
        }
    }
}
