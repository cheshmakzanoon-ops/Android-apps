package com.agency.photoduel.ui.state

import com.agency.photoduel.domain.model.AppResult
import com.agency.photoduel.domain.model.BlockedUser
import com.agency.photoduel.domain.model.PhotoDuelError
import com.agency.photoduel.domain.model.ReportReason
import com.agency.photoduel.domain.model.ReportTarget
import com.agency.photoduel.domain.port.PhotoDuelService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReportUi(
    val reason: ReportReason? = null,
    val details: String = "",
    val submitting: Boolean = false,
    val error: PhotoDuelError? = null,
    val sent: Boolean = false,
) {
    val canSend: Boolean get() = reason != null && !submitting && !sent
}

/** Report sheet. The reporter is never shown to the reported person; the server keeps that rule. */
class ReportState(
    private val scope: CoroutineScope,
    private val service: PhotoDuelService,
    private val network: NetworkStatus,
    val target: ReportTarget,
) {
    private val _ui = MutableStateFlow(ReportUi())
    val ui: StateFlow<ReportUi> = _ui.asStateFlow()

    fun selectReason(reason: ReportReason) = _ui.update { it.copy(reason = reason, error = null) }

    fun onDetailsChanged(value: String) = _ui.update { it.copy(details = value.take(Inputs.REPORT_DETAILS_MAX), error = null) }

    fun send() {
        val current = _ui.value
        val reason = current.reason ?: return
        if (!current.canSend) return
        _ui.update { it.copy(submitting = true, error = null) }
        scope.launch {
            when (val result = network.track(service.report(target, reason, Inputs.reportDetails(current.details)))) {
                is AppResult.Success -> _ui.update { it.copy(submitting = false, sent = true) }
                is AppResult.Failure -> _ui.update { it.copy(submitting = false, error = result.error) }
            }
        }
    }
}

data class BlockUi(
    val submitting: Boolean = false,
    val error: PhotoDuelError? = null,
    val done: Boolean = false,
)

/** Block sheet for one person. */
class BlockState(
    private val scope: CoroutineScope,
    private val service: PhotoDuelService,
    private val network: NetworkStatus,
    val userId: String,
) {
    private val _ui = MutableStateFlow(BlockUi())
    val ui: StateFlow<BlockUi> = _ui.asStateFlow()

    fun confirm() {
        if (_ui.value.submitting || _ui.value.done) return
        _ui.update { it.copy(submitting = true, error = null) }
        scope.launch {
            when (val result = network.track(service.setBlocked(userId, true))) {
                is AppResult.Success -> _ui.update { it.copy(submitting = false, done = true) }
                is AppResult.Failure -> _ui.update { it.copy(submitting = false, error = result.error) }
            }
        }
    }
}

data class BlockedUsersUi(val unblocking: Set<String> = emptySet(), val error: PhotoDuelError? = null)

/** The list of people the viewer blocked, with unblock. */
class BlockedUsersState(
    private val scope: CoroutineScope,
    private val service: PhotoDuelService,
    private val network: NetworkStatus,
) {
    private val loader = Loader(scope, network) { service.blockedUsers() }
    val users: StateFlow<Loadable<List<BlockedUser>>> = loader.state

    private val _ui = MutableStateFlow(BlockedUsersUi())
    val ui: StateFlow<BlockedUsersUi> = _ui.asStateFlow()

    init {
        loader.load()
    }

    fun reload() = loader.load()

    fun dismissError() = _ui.update { it.copy(error = null) }

    fun unblock(userId: String) {
        if (userId in _ui.value.unblocking) return
        _ui.update { it.copy(unblocking = it.unblocking + userId, error = null) }
        scope.launch {
            when (val result = network.track(service.setBlocked(userId, false))) {
                is AppResult.Success -> {
                    loader.update { list -> list.filterNot { it.userId == userId } }
                    _ui.update { it.copy(unblocking = it.unblocking - userId) }
                }
                is AppResult.Failure -> _ui.update { it.copy(unblocking = it.unblocking - userId, error = result.error) }
            }
        }
    }
}
