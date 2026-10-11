package com.agency.photoduel.ui.state

import com.agency.photoduel.domain.model.AppResult
import com.agency.photoduel.domain.model.Challenge
import com.agency.photoduel.domain.model.ChallengeState
import com.agency.photoduel.domain.model.CreatedInvitation
import com.agency.photoduel.domain.model.InvitationTtl
import com.agency.photoduel.domain.model.MemberRole
import com.agency.photoduel.domain.model.PhotoDuelError
import com.agency.photoduel.domain.model.RoomDetail
import com.agency.photoduel.domain.port.PhotoDuelService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---- Create room ----------------------------------------------------------------------------------

data class CreateRoomUi(
    val name: String = "",
    val problem: NameProblem? = null,
    val submitting: Boolean = false,
    val error: PhotoDuelError? = null,
    /** Set when the room exists. Consume with [CreateRoomState.consumeCreated]. */
    val createdRoomId: String? = null,
)

class CreateRoomState(
    private val scope: CoroutineScope,
    private val service: PhotoDuelService,
    private val network: NetworkStatus,
) {
    private val _state = MutableStateFlow(CreateRoomUi())
    val state: StateFlow<CreateRoomUi> = _state.asStateFlow()

    fun onNameChanged(value: String) = _state.update { it.copy(name = value, problem = null, error = null) }

    fun submit() {
        val current = _state.value
        if (current.submitting) return
        val check = Inputs.roomName(current.name)
        val clean = check.clean
        if (clean == null) {
            _state.update { it.copy(problem = check.problem) }
            return
        }
        _state.update { it.copy(submitting = true, error = null) }
        scope.launch {
            when (val result = network.track(service.createRoom(clean))) {
                is AppResult.Success -> _state.update { it.copy(submitting = false, createdRoomId = result.value.id) }
                is AppResult.Failure -> _state.update { it.copy(submitting = false, error = result.error) }
            }
        }
    }

    fun consumeCreated() = _state.update { it.copy(createdRoomId = null) }
}

// ---- Room detail ----------------------------------------------------------------------------------

/** Everything the room screen shows. [activeChallenge] is the one in CREATED, OPEN_FOR_SUBMISSIONS or VOTING. */
data class RoomDetailData(
    val room: RoomDetail,
    val activeChallenge: Challenge?,
    val latestChallenge: Challenge?,
) {
    val isOwner: Boolean get() = room.viewerRole == MemberRole.OWNER
}

/** A confirmation the screen must ask for before a destructive action. */
sealed interface RoomConfirm {
    data class RemoveMember(val userId: String, val displayName: String) : RoomConfirm
    data object Leave : RoomConfirm
    data object DeleteRoom : RoomConfirm
    data class CancelChallenge(val challengeId: String) : RoomConfirm
}

data class RoomDetailUi(
    val busy: Boolean = false,
    val ttl: InvitationTtl = InvitationTtl.THREE_DAYS,
    /** The invitation just created. The token is shown once; it is not kept after the screen closes. */
    val invitation: CreatedInvitation? = null,
    val confirm: RoomConfirm? = null,
    val error: PhotoDuelError? = null,
    /** True after leaving or deleting the room; the screen navigates away. */
    val finished: Boolean = false,
)

class RoomDetailState(
    private val scope: CoroutineScope,
    private val service: PhotoDuelService,
    private val network: NetworkStatus,
    val roomId: String,
) {
    private val loader = Loader(scope, network) { load() }
    val data: StateFlow<Loadable<RoomDetailData>> = loader.state

    private val _ui = MutableStateFlow(RoomDetailUi())
    val ui: StateFlow<RoomDetailUi> = _ui.asStateFlow()

    init {
        loader.load()
    }

    private suspend fun load(): AppResult<RoomDetailData> {
        val room = when (val r = service.room(roomId)) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return r
        }
        val history = when (val h = service.roomHistory(roomId, null)) {
            is AppResult.Success -> h.value.items
            is AppResult.Failure -> return h
        }
        val active = history.firstOrNull { it.state in ACTIVE_STATES }
        return AppResult.Success(RoomDetailData(room, active, history.firstOrNull()))
    }

    fun refresh() = loader.refresh()

    fun setTtl(ttl: InvitationTtl) = _ui.update { it.copy(ttl = ttl) }

    fun dismissError() = _ui.update { it.copy(error = null) }

    fun ask(confirm: RoomConfirm) = _ui.update { it.copy(confirm = confirm) }

    fun dismissConfirm() = _ui.update { it.copy(confirm = null) }

    fun createInvitation() {
        val ttl = _ui.value.ttl
        act({ service.createInvitation(roomId, ttl) }) { invitation -> _ui.update { it.copy(invitation = invitation) } }
    }

    fun revokeInvitation() {
        val invitation = _ui.value.invitation ?: return
        act({ service.revokeInvitation(roomId, invitation.id) }) { _ui.update { it.copy(invitation = null) } }
    }

    fun dismissInvitation() = _ui.update { it.copy(invitation = null) }

    fun leave() = act({ service.leaveRoom(roomId) }) { _ui.update { it.copy(finished = true) } }

    fun deleteRoom() = act({ service.deleteRoom(roomId) }) { _ui.update { it.copy(finished = true) } }

    fun removeMember(userId: String) = act({ service.removeMember(roomId, userId) }) { loader.refresh() }

    fun startChallenge(challengeId: String) = act({ service.startChallenge(challengeId) }) { loader.refresh() }

    fun cancelChallenge(challengeId: String) = act({ service.cancelChallenge(challengeId) }) { loader.refresh() }

    /** Runs one service call with the busy flag and error handling shared by every action on this screen. */
    private fun <T> act(call: suspend () -> AppResult<T>, onSuccess: (T) -> Unit) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, error = null, confirm = null) }
        scope.launch {
            when (val result = network.track(call())) {
                is AppResult.Success -> {
                    _ui.update { it.copy(busy = false) }
                    onSuccess(result.value)
                }
                is AppResult.Failure -> _ui.update { it.copy(busy = false, error = result.error) }
            }
        }
    }

    companion object {
        val ACTIVE_STATES = setOf(ChallengeState.CREATED, ChallengeState.OPEN_FOR_SUBMISSIONS, ChallengeState.VOTING)
    }
}
