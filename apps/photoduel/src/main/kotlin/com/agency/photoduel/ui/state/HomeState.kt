package com.agency.photoduel.ui.state

import com.agency.photoduel.domain.model.RoomSummary
import com.agency.photoduel.domain.port.PhotoDuelService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class JoinLinkUi(
    val open: Boolean = false,
    val text: String = "",
    /** True after the user pressed Join and the text held no recognisable invitation. */
    val invalid: Boolean = false,
)

/** The list of rooms, plus the "join with a pasted link" dialog. */
class HomeState(
    scope: CoroutineScope,
    service: PhotoDuelService,
    network: NetworkStatus,
) {
    private val rooms = Loader(scope, network) { service.myRooms() }
    val state: StateFlow<Loadable<List<RoomSummary>>> = rooms.state

    private val _join = MutableStateFlow(JoinLinkUi())
    val join: StateFlow<JoinLinkUi> = _join.asStateFlow()

    init {
        rooms.load()
    }

    fun refresh() = rooms.refresh()

    fun openJoinDialog() = _join.update { JoinLinkUi(open = true) }

    fun closeJoinDialog() = _join.update { JoinLinkUi() }

    fun onJoinTextChanged(value: String) = _join.update { it.copy(text = value, invalid = false) }

    /** Returns the invitation token to open, or null (and marks the field invalid) when the text is not an invitation. */
    fun submitJoinLink(): String? {
        val token = InvitationInput.tokenFrom(_join.value.text)
        if (token == null) {
            _join.update { it.copy(invalid = true) }
            return null
        }
        _join.update { JoinLinkUi() }
        return token
    }
}
