package com.agency.photoduel.ui.state

import com.agency.photoduel.domain.model.AppResult
import com.agency.photoduel.domain.model.ChallengeOutcome
import com.agency.photoduel.domain.model.ChallengeView
import com.agency.photoduel.domain.model.PhotoDuelError
import com.agency.photoduel.domain.model.Submission
import com.agency.photoduel.domain.model.SubmissionStatus
import com.agency.photoduel.domain.port.PhotoDuelService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One line of the tally. [submission] is null when the photo is no longer available (removed or purged). */
data class ResultRow(
    val submissionId: String,
    val submission: Submission?,
    val votes: Int,
    /** Bar length as a share of the highest vote count, 0 to 1. Zero when nobody received a vote. */
    val fraction: Float,
    val isWinner: Boolean,
)

data class ResultsModel(
    val outcome: ChallengeOutcome,
    val rows: List<ResultRow>,
    val winners: List<ResultRow>,
    val ballotCount: Int,
    val eligibleVoterCount: Int,
    val maxVotes: Int,
) {
    val hasWinner: Boolean get() = outcome == ChallengeOutcome.WINNER || outcome == ChallengeOutcome.TIE
    val isTie: Boolean get() = outcome == ChallengeOutcome.TIE
}

object ResultsModelBuilder {
    /** Builds the model from the server's result only. Returns null when the server has not published one. */
    fun build(view: ChallengeView): ResultsModel? {
        val result = view.result ?: return null
        // Only ACTIVE photos can be shown. The author still receives their own REMOVED row from the server.
        val byId = view.submissions.filter { it.status == SubmissionStatus.ACTIVE }.associateBy { it.id }
        val maxVotes = result.tallies.maxOfOrNull { it.votes } ?: 0
        val winnerIds = if (result.outcome == ChallengeOutcome.WINNER || result.outcome == ChallengeOutcome.TIE) {
            result.coWinnerSubmissionIds.toSet()
        } else {
            emptySet()
        }
        val rows = result.tallies
            .map { tally ->
                ResultRow(
                    submissionId = tally.submissionId,
                    submission = byId[tally.submissionId],
                    votes = tally.votes,
                    fraction = if (maxVotes == 0) 0f else tally.votes.toFloat() / maxVotes.toFloat(),
                    isWinner = tally.submissionId in winnerIds,
                )
            }
            // Most votes first. The server already orders ties by id, and a stable sort keeps that order.
            .sortedByDescending { it.votes }
        return ResultsModel(
            outcome = result.outcome,
            rows = rows,
            winners = rows.filter { it.isWinner },
            ballotCount = result.ballotCount,
            eligibleVoterCount = result.eligibleVoterCount,
            maxVotes = maxVotes,
        )
    }
}

enum class RevealStage { TALLY, WINNER }

data class ResultsUi(
    val stage: RevealStage = RevealStage.TALLY,
    val deleting: Boolean = false,
    val confirmDelete: Boolean = false,
    val error: PhotoDuelError? = null,
    val photoDeleted: Boolean = false,
)

data class ResultsData(val view: ChallengeView, val model: ResultsModel?)

/**
 * Results of a completed challenge. Nothing is invented: when the server has no result the screen says
 * so. With reduced motion the tally is shown at once and the winner step is not animated.
 */
class ResultsState(
    private val scope: CoroutineScope,
    private val service: PhotoDuelService,
    private val network: NetworkStatus,
    val challengeId: String,
    val reduceMotion: Boolean,
) {
    private val loader = Loader(scope, network) {
        when (val r = service.challenge(challengeId)) {
            is AppResult.Success -> AppResult.Success(ResultsData(r.value, ResultsModelBuilder.build(r.value)))
            is AppResult.Failure -> r
        }
    }
    val data: StateFlow<Loadable<ResultsData>> = loader.state

    private val _ui = MutableStateFlow(ResultsUi())
    val ui: StateFlow<ResultsUi> = _ui.asStateFlow()

    init {
        loader.load()
    }

    fun reload() = loader.load()

    fun showWinner() = _ui.update { it.copy(stage = RevealStage.WINNER) }

    fun showTally() = _ui.update { it.copy(stage = RevealStage.TALLY) }

    fun askDeleteMyPhoto() = _ui.update { it.copy(confirmDelete = true) }

    fun dismissDelete() = _ui.update { it.copy(confirmDelete = false) }

    fun dismissError() = _ui.update { it.copy(error = null) }

    /** Deletes the viewer's own photo after the challenge. The tally is kept. */
    fun deleteMyPhoto(submissionId: String) {
        if (_ui.value.deleting) return
        _ui.update { it.copy(deleting = true, confirmDelete = false, error = null) }
        scope.launch {
            when (val result = network.track(service.deleteSubmission(submissionId))) {
                is AppResult.Success -> {
                    _ui.update { it.copy(deleting = false, photoDeleted = true) }
                    loader.refresh()
                }
                is AppResult.Failure -> _ui.update { it.copy(deleting = false, error = result.error) }
            }
        }
    }
}
