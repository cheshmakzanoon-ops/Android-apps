package com.agency.photoduel.ui.state

import com.agency.photoduel.domain.model.AppResult
import com.agency.photoduel.domain.model.ChallengeView
import com.agency.photoduel.domain.model.PhotoDuelError
import com.agency.photoduel.domain.model.PreparedPhoto
import com.agency.photoduel.domain.model.Submission
import com.agency.photoduel.domain.model.SubmissionStatus
import com.agency.photoduel.domain.port.PhotoDuelService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SubmissionStage { CHOOSE, PREVIEW, PROCESSING, UPLOADING, SUBMITTED, FAILED }

/** Mirrors the engine's stages without depending on the Android-only UiDependencies file. */
enum class PrepStage { READING, DECODING, ENCODING, DONE }

sealed interface SubmissionFailure {
    /** The photo could not be read, decoded or re-encoded on the phone. */
    data object Processing : SubmissionFailure

    /** The service refused or could not be reached. */
    data class Service(val error: PhotoDuelError) : SubmissionFailure
}

/**
 * @param S the platform handle of the chosen photo (an android.net.Uri in the app, any object in tests).
 */
data class SubmissionUi<S>(
    val stage: SubmissionStage = SubmissionStage.CHOOSE,
    val source: S? = null,
    val prepStage: PrepStage? = null,
    val progress: Float = 0f,
    val failure: SubmissionFailure? = null,
    /** The viewer's photo on the server, if any. While replacing, it stays until the new one is accepted. */
    val submitted: Submission? = null,
    /** True when the user is choosing a replacement for [submitted]. */
    val replacing: Boolean = false,
    val withdrawing: Boolean = false,
)

/**
 * One photo submission: choose, preview, process, upload, then replace or withdraw. Cancel stops the
 * work and returns to the preview. Retry after a failure repeats only the step that failed: a photo
 * that was already processed is uploaded again without being re-encoded.
 */
class SubmissionState<S : Any>(
    private val scope: CoroutineScope,
    private val service: PhotoDuelService,
    private val network: NetworkStatus,
    val challengeId: String,
    private val prepare: suspend (source: S, onStage: (PrepStage) -> Unit) -> PreparedPhoto,
) {
    private val _ui = MutableStateFlow(SubmissionUi<S>())
    val ui: StateFlow<SubmissionUi<S>> = _ui.asStateFlow()

    private val context = Loader(scope, network, onLoaded = { applyContext(it) }) { service.challenge(challengeId) }
    val challenge: StateFlow<Loadable<ChallengeView>> = context.state

    private var prepared: PreparedPhoto? = null
    private var job: Job? = null

    init {
        context.load()
    }

    fun reload() = context.load()

    private fun applyContext(view: ChallengeView) {
        val mine = view.submissions.firstOrNull { it.isMine && it.status == SubmissionStatus.ACTIVE }
        _ui.update { current ->
            when {
                current.stage == SubmissionStage.CHOOSE && mine != null && !current.replacing ->
                    current.copy(stage = SubmissionStage.SUBMITTED, submitted = mine)
                else -> current.copy(submitted = mine ?: current.submitted.takeIf { current.stage == SubmissionStage.SUBMITTED })
            }
        }
    }

    /** The system picker returned. A null result means the user backed out, which changes nothing. */
    fun onPicked(source: S?) {
        if (source == null) return
        prepared = null
        _ui.update {
            it.copy(
                stage = SubmissionStage.PREVIEW, source = source, prepStage = null, progress = 0f,
                failure = null,
            )
        }
    }

    /** Starts processing and upload for the previewed photo. */
    fun confirm() {
        val current = _ui.value
        val source = current.source ?: return
        if (current.stage != SubmissionStage.PREVIEW && current.stage != SubmissionStage.FAILED) return
        run(source)
    }

    /** Repeats the failed step. */
    fun retry() = confirm()

    /** Stops processing or upload and goes back to the preview of the same photo. */
    fun cancel() {
        job?.cancel()
        job = null
        _ui.update {
            if (it.stage == SubmissionStage.PROCESSING || it.stage == SubmissionStage.UPLOADING) {
                it.copy(stage = SubmissionStage.PREVIEW, prepStage = null, progress = 0f, failure = null)
            } else {
                it
            }
        }
    }

    /** Back to the picker. The current photo on the server is untouched until a new one is accepted. */
    fun chooseDifferent() {
        job?.cancel()
        prepared = null
        _ui.update {
            it.copy(
                stage = SubmissionStage.CHOOSE, source = null, prepStage = null, progress = 0f, failure = null,
                replacing = it.submitted != null,
            )
        }
    }

    /** Leaves the replace flow without changing anything. */
    fun keepCurrent() {
        _ui.update {
            if (it.submitted != null) {
                it.copy(stage = SubmissionStage.SUBMITTED, source = null, replacing = false, failure = null)
            } else {
                it
            }
        }
    }

    fun withdraw() {
        if (_ui.value.withdrawing) return
        _ui.update { it.copy(withdrawing = true, failure = null) }
        scope.launch {
            when (val result = network.track(service.withdrawSubmission(challengeId))) {
                is AppResult.Success -> {
                    prepared = null
                    _ui.update {
                        it.copy(
                            withdrawing = false, stage = SubmissionStage.CHOOSE, submitted = null, source = null,
                            replacing = false, progress = 0f, prepStage = null,
                        )
                    }
                    context.load()
                }
                is AppResult.Failure -> _ui.update {
                    it.copy(withdrawing = false, failure = SubmissionFailure.Service(result.error))
                }
            }
        }
    }

    private fun run(source: S) {
        job?.cancel()
        job = scope.launch {
            var photo = prepared
            if (photo == null) {
                _ui.update { it.copy(stage = SubmissionStage.PROCESSING, prepStage = PrepStage.READING, progress = 0f, failure = null) }
                photo = try {
                    prepare(source) { stage -> _ui.update { it.copy(prepStage = stage) } }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    _ui.update { it.copy(stage = SubmissionStage.FAILED, failure = SubmissionFailure.Processing, prepStage = null) }
                    return@launch
                }
                prepared = photo
            }
            _ui.update { it.copy(stage = SubmissionStage.UPLOADING, prepStage = null, progress = 0f, failure = null) }
            val result = network.track(
                service.submitPhoto(challengeId, photo) { fraction ->
                    _ui.update { it.copy(progress = fraction.coerceIn(0f, 1f)) }
                },
            )
            when (result) {
                is AppResult.Success -> {
                    prepared = null
                    _ui.update {
                        it.copy(
                            stage = SubmissionStage.SUBMITTED, submitted = result.value, source = null,
                            replacing = false, progress = 1f, failure = null,
                        )
                    }
                    context.load()
                }
                is AppResult.Failure -> _ui.update {
                    it.copy(stage = SubmissionStage.FAILED, failure = SubmissionFailure.Service(result.error))
                }
            }
        }
    }
}
