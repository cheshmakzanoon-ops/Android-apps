package com.agency.photoduel.ui.state

import com.agency.photoduel.domain.model.AppResult
import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.InvitationPreview
import com.agency.photoduel.domain.model.PhotoDuelError
import com.agency.photoduel.domain.port.PhotoDuelService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class InvitationUi(
    val joining: Boolean = false,
    val joinError: PhotoDuelError? = null,
    /** Set after joining (or when already a member). The screen opens this room. */
    val joinedRoomId: String? = null,
)

/** Invitation landing: preview the room, then join. */
class InvitationState(
    private val scope: CoroutineScope,
    private val service: PhotoDuelService,
    private val network: NetworkStatus,
    val token: String,
) {
    private val preview = Loader(scope, network) { service.previewInvitation(token) }
    val state: StateFlow<Loadable<InvitationPreview>> = preview.state

    private val _ui = MutableStateFlow(InvitationUi())
    val ui: StateFlow<InvitationUi> = _ui.asStateFlow()

    init {
        preview.load()
    }

    fun reload() = preview.load()

    fun join() {
        if (_ui.value.joining) return
        _ui.update { it.copy(joining = true, joinError = null) }
        scope.launch {
            when (val result = network.track(service.joinWithInvitation(token))) {
                is AppResult.Success -> _ui.update { it.copy(joining = false, joinedRoomId = result.value.id) }
                is AppResult.Failure -> _ui.update { it.copy(joining = false, joinError = result.error) }
            }
        }
    }

    fun consumeJoined() = _ui.update { it.copy(joinedRoomId = null) }

    companion object {
        /** The server answers every unusable link (unknown, expired, revoked, used up, room deleted) the same way. */
        fun isInvalidLink(error: PhotoDuelError): Boolean =
            error.code == ErrorCode.INVITE_UNAVAILABLE || error.code == ErrorCode.NOT_FOUND
    }
}
