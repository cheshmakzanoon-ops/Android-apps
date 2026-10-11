package com.agency.photoduel.ui.state

import com.agency.photoduel.domain.model.AppResult
import com.agency.photoduel.domain.model.Challenge
import com.agency.photoduel.domain.model.ChallengeSpec
import com.agency.photoduel.domain.model.PhotoDuelError
import com.agency.photoduel.domain.model.PromptCatalog
import com.agency.photoduel.domain.port.PhotoDuelService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CreateChallengeUi(
    val promptId: String? = null,
    val submissionHours: Int = ChallengeDurations.DEFAULT_SUBMISSION_HOURS,
    val votingHours: Int = ChallengeDurations.DEFAULT_VOTING_HOURS,
    val submitting: Boolean = false,
    val error: PhotoDuelError? = null,
    /** Set when the challenge exists. Consume with [CreateChallengeState.consumeCreated]. */
    val created: Challenge? = null,
) {
    val canSubmit: Boolean get() = promptId != null && !submitting
}

/**
 * Prompt picker and window choices for a new challenge. Used for the first challenge of a room and for
 * a rematch. For a rematch, [previousPromptId] is not pre-selected, so the default is a fresh prompt.
 */
class CreateChallengeState(
    private val scope: CoroutineScope,
    private val service: PhotoDuelService,
    private val network: NetworkStatus,
    val roomId: String,
    private val previousPromptId: String? = null,
) {
    private val _ui = MutableStateFlow(CreateChallengeUi())
    val ui: StateFlow<CreateChallengeUi> = _ui.asStateFlow()

    private val catalog = Loader(scope, network, onLoaded = { applyDefaultPrompt(it) }) { service.prompts() }
    val prompts: StateFlow<Loadable<PromptCatalog>> = catalog.state

    init {
        catalog.load()
    }

    private fun applyDefaultPrompt(loaded: PromptCatalog) {
        _ui.update { current ->
            val stillValid = current.promptId != null && loaded.prompts.any { it.id == current.promptId }
            if (stillValid) {
                current
            } else {
                val pick = loaded.prompts.firstOrNull { it.id != previousPromptId } ?: loaded.prompts.firstOrNull()
                current.copy(promptId = pick?.id)
            }
        }
    }

    fun reloadPrompts() = catalog.load()

    fun selectPrompt(id: String) = _ui.update { it.copy(promptId = id, error = null) }

    fun setSubmissionHours(hours: Int) {
        if (hours in ChallengeDurations.SUBMISSION_HOURS) _ui.update { it.copy(submissionHours = hours) }
    }

    fun setVotingHours(hours: Int) {
        if (hours in ChallengeDurations.VOTING_HOURS) _ui.update { it.copy(votingHours = hours) }
    }

    fun submit() {
        val current = _ui.value
        val promptId = current.promptId ?: return
        if (current.submitting) return
        _ui.update { it.copy(submitting = true, error = null) }
        scope.launch {
            val spec = ChallengeSpec(promptId, current.submissionHours, current.votingHours)
            when (val result = network.track(service.createChallenge(roomId, spec))) {
                is AppResult.Success -> _ui.update { it.copy(submitting = false, created = result.value) }
                is AppResult.Failure -> _ui.update { it.copy(submitting = false, error = result.error) }
            }
        }
    }

    fun consumeCreated() = _ui.update { it.copy(created = null) }
}
