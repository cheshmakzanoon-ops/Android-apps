package com.agency.photoduel.ui.state

import com.agency.photoduel.domain.model.AppResult
import com.agency.photoduel.domain.model.Challenge
import com.agency.photoduel.domain.model.ChallengeState
import com.agency.photoduel.domain.model.ChallengeView
import com.agency.photoduel.domain.model.MemberRole
import com.agency.photoduel.domain.model.PhotoDuelError
import com.agency.photoduel.domain.model.RoomDetail
import com.agency.photoduel.domain.model.SubmissionStatus
import com.agency.photoduel.domain.port.PhotoDuelService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the viewer should do next in a challenge. Derived only from what the server returned. */
enum class ChallengePhase {
    WAITING_TO_START,
    SUBMIT_PHOTO,
    PHOTO_SUBMITTED,
    VOTE,
    VOTE_CAST,
    RESULTS,
    CANCELLED,
    ARCHIVED,

    /** The viewer is not on the frozen roster (joined after the start) or cannot act in this window. */
    WATCHING,
}

data class ChallengeData(
    val view: ChallengeView,
    val room: RoomDetail?,
    val anchor: ServerTimeAnchor,
) {
    val challenge: Challenge get() = view.challenge
    val isOwner: Boolean get() = room?.viewerRole == MemberRole.OWNER
    val deadline: Long? get() = Countdown.deadlineOf(view.challenge)
    val phase: ChallengePhase get() = phaseOf(view)

    /** The viewer's own ACTIVE submission, if the server returned it. */
    val mySubmission get() = view.submissions.firstOrNull { it.isMine && it.status == SubmissionStatus.ACTIVE }

    companion object {
        fun phaseOf(view: ChallengeView): ChallengePhase {
            val my = view.myStatus
            return when (view.challenge.state) {
                ChallengeState.CREATED -> ChallengePhase.WAITING_TO_START
                ChallengeState.OPEN_FOR_SUBMISSIONS -> when {
                    my.mySubmissionId != null -> ChallengePhase.PHOTO_SUBMITTED
                    my.canSubmit -> ChallengePhase.SUBMIT_PHOTO
                    else -> ChallengePhase.WATCHING
                }
                ChallengeState.VOTING -> when {
                    my.myBallotSubmissionId != null -> ChallengePhase.VOTE_CAST
                    my.canVote -> ChallengePhase.VOTE
                    else -> ChallengePhase.WATCHING
                }
                ChallengeState.COMPLETED -> ChallengePhase.RESULTS
                ChallengeState.ARCHIVED -> ChallengePhase.ARCHIVED
                ChallengeState.CANCELLED -> ChallengePhase.CANCELLED
            }
        }
    }
}

enum class ChallengeConfirm { CANCEL_CHALLENGE }

data class ChallengeDetailUi(
    val busy: Boolean = false,
    val error: PhotoDuelError? = null,
    val confirm: ChallengeConfirm? = null,
)

/**
 * The challenge screen: loads the view and the room (for the owner check), and runs start and cancel.
 * [clockNow] is the display clock (ServerClock.nowEpochMillis). It anchors countdowns and nothing else.
 */
class ChallengeDetailState(
    private val scope: CoroutineScope,
    private val service: PhotoDuelService,
    private val network: NetworkStatus,
    private val clockNow: () -> Long,
    val challengeId: String,
    private val pollDelaysMillis: List<Long> = listOf(1_500, 3_000, 5_000, 8_000, 15_000),
) {
    private val loader = Loader(scope, network) { load() }
    val data: StateFlow<Loadable<ChallengeData>> = loader.state

    private val _ui = MutableStateFlow(ChallengeDetailUi())
    val ui: StateFlow<ChallengeDetailUi> = _ui.asStateFlow()
    private var pollJob: Job? = null

    init {
        loader.load()
    }

    private suspend fun load(): AppResult<ChallengeData> {
        val view = when (val r = service.challenge(challengeId)) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return r
        }
        val anchor = ServerTimeAnchor.of(view.challenge, clockNow())
        // The room tells us whether the viewer owns it. If that call fails the challenge is still shown.
        val room = (service.room(view.challenge.roomId) as? AppResult.Success)?.value
        return AppResult.Success(ChallengeData(view, room, anchor))
    }

    fun refresh() = loader.refresh()

    /**
     * Called when a countdown reaches zero. The server moves the challenge on at the deadline; this asks
     * again a few times until the state has changed. The client never decides the transition itself.
     */
    fun onDeadlineReached() {
        if (pollJob?.isActive == true) return
        val before = loader.value?.challenge?.state
        pollJob = scope.launch {
            for (wait in pollDelaysMillis) {
                delay(wait)
                loader.refresh()
                delay(500)
                val now = loader.value?.challenge?.state
                if (now != null && now != before) return@launch
            }
        }
    }

    fun ask(confirm: ChallengeConfirm) = _ui.update { it.copy(confirm = confirm) }

    fun dismissConfirm() = _ui.update { it.copy(confirm = null) }

    fun dismissError() = _ui.update { it.copy(error = null) }

    fun start() = act { service.startChallenge(challengeId) }

    fun cancel() = act { service.cancelChallenge(challengeId) }

    private fun act(call: suspend () -> AppResult<Challenge>) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, error = null, confirm = null) }
        scope.launch {
            when (val result = network.track(call())) {
                is AppResult.Success -> {
                    _ui.update { it.copy(busy = false) }
                    loader.refresh()
                }
                is AppResult.Failure -> _ui.update { it.copy(busy = false, error = result.error) }
            }
        }
    }
}
