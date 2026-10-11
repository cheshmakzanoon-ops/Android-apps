package com.agency.photoduel.ui.state

import com.agency.photoduel.domain.model.AppResult
import com.agency.photoduel.domain.model.ChallengeView
import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.PhotoDuelError
import com.agency.photoduel.domain.model.Submission
import com.agency.photoduel.domain.model.SubmissionStatus
import com.agency.photoduel.domain.port.PhotoDuelService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class VotingStage {
    /** Short explanation of the rules, shown before the photos. */
    INTRO,

    /** The grid of photos: tap one to select it, then confirm. */
    BALLOT,

    /** The viewer's ballot is recorded. */
    COMPLETED,

    /** The viewer cannot vote in this challenge (not on the roster, or the window is not open). */
    UNAVAILABLE,
}

/** One-shot signals the screen turns into haptic feedback. */
enum class VotingEvent { SELECTED, CAST, REJECTED }

data class VotingUi(
    val stage: VotingStage = VotingStage.INTRO,
    val selectedId: String? = null,
    val casting: Boolean = false,
    val error: PhotoDuelError? = null,
    /** The submission the viewer voted for, once known. */
    val castSubmissionId: String? = null,
)

/**
 * Voting flow. A tap selects a photo (and can be changed freely); a separate confirm action casts the
 * ballot. Ballots are final, so the confirm step names the chosen photo's author. The viewer's own photo
 * is never selectable. Replaying the same ballot is safe because the server treats it as idempotent.
 */
class VotingState(
    private val scope: CoroutineScope,
    private val service: PhotoDuelService,
    private val network: NetworkStatus,
    private val clockNow: () -> Long,
    val challengeId: String,
) {
    private val _ui = MutableStateFlow(VotingUi())
    val ui: StateFlow<VotingUi> = _ui.asStateFlow()

    private val _events = MutableSharedFlow<VotingEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<VotingEvent> = _events.asSharedFlow()

    private val loader = Loader(scope, network, onLoaded = { applyView(it) }) { fetch() }
    val data: StateFlow<Loadable<VotingData>> = loader.state

    init {
        loader.load()
    }

    private suspend fun fetch(): AppResult<VotingData> = when (val r = service.challenge(challengeId)) {
        is AppResult.Success -> AppResult.Success(VotingData(r.value, ServerTimeAnchor.of(r.value.challenge, clockNow())))
        is AppResult.Failure -> r
    }

    private fun applyView(data: VotingData) {
        val view = data.view
        _ui.update { current ->
            val cast = view.myStatus.myBallotSubmissionId
            when {
                cast != null -> current.copy(stage = VotingStage.COMPLETED, castSubmissionId = cast, selectedId = cast, casting = false)
                current.stage == VotingStage.COMPLETED -> current
                !view.myStatus.canVote -> current.copy(stage = VotingStage.UNAVAILABLE)
                current.stage == VotingStage.UNAVAILABLE -> current.copy(stage = VotingStage.INTRO)
                else -> current.copy(selectedId = current.selectedId?.takeIf { id -> data.candidates.any { it.id == id && !it.isMine } })
            }
        }
    }

    fun reload() = loader.load()

    fun startVoting() {
        _ui.update { if (it.stage == VotingStage.INTRO) it.copy(stage = VotingStage.BALLOT) else it }
    }

    /** Selects [submissionId] when it is a photo the viewer may vote for. Returns whether it was accepted. */
    fun select(submissionId: String): Boolean {
        val current = _ui.value
        val data = loader.value ?: return false
        val target = data.candidates.firstOrNull { it.id == submissionId }
        if (current.stage != VotingStage.BALLOT || current.casting || target == null || target.isMine) {
            _events.tryEmit(VotingEvent.REJECTED)
            return false
        }
        _ui.update { it.copy(selectedId = submissionId, error = null) }
        _events.tryEmit(VotingEvent.SELECTED)
        return true
    }

    fun clearError() = _ui.update { it.copy(error = null) }

    /** Casts the ballot for the selected photo. */
    fun confirm() {
        val current = _ui.value
        val selected = current.selectedId ?: return
        if (current.stage != VotingStage.BALLOT || current.casting) return
        _ui.update { it.copy(casting = true, error = null) }
        scope.launch {
            when (val result = network.track(service.castBallot(challengeId, selected))) {
                is AppResult.Success -> {
                    _ui.update { it.copy(casting = false, stage = VotingStage.COMPLETED, castSubmissionId = selected) }
                    _events.tryEmit(VotingEvent.CAST)
                    loader.refresh()
                }
                is AppResult.Failure -> {
                    _ui.update { it.copy(casting = false, error = result.error) }
                    _events.tryEmit(VotingEvent.REJECTED)
                    // A ballot that already exists, or a window that closed, changes what the screen should show.
                    if (result.error.code == ErrorCode.ALREADY_VOTED || result.error.code == ErrorCode.DEADLINE_PASSED ||
                        result.error.code == ErrorCode.INVALID_TRANSITION
                    ) {
                        loader.refresh()
                    }
                }
            }
        }
    }
}

data class VotingData(val view: ChallengeView, val anchor: ServerTimeAnchor) {
    /** Photos the viewer can see: only ACTIVE submissions. Own photo is included but not votable. */
    val candidates: List<Submission> get() = view.submissions.filter { it.status == SubmissionStatus.ACTIVE }
}
