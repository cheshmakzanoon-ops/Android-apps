package com.agency.photoduel.ui.state

import com.agency.photoduel.domain.model.AppResult
import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.PhotoDuelError
import com.agency.photoduel.domain.model.PreparedPhoto
import com.agency.photoduel.domain.model.Submission
import com.agency.photoduel.domain.model.SubmissionStatus
import com.agency.photoduel.domain.port.PhotoDuelService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubmissionStateTest {
    private val harness = TestHarness()
    private val network = NetworkStatus()
    private val friends = Friends.create(count = 3)
    private val challengeId = friends.startChallenge(submissionHours = 6)
    private val ben = friends.clients[1]

    @After
    fun tearDown() = harness.close()

    private var prepareCalls = 0
    private val prepared = mutableListOf<String>()

    private fun state(
        service: PhotoDuelService = ben,
        prepare: suspend (String, (PrepStage) -> Unit) -> PreparedPhoto = { source, onStage ->
            prepareCalls++
            prepared += source
            onStage(PrepStage.DECODING)
            onStage(PrepStage.ENCODING)
            onStage(PrepStage.DONE)
            photo()
        },
    ) = SubmissionState(harness.scope, service, network, challengeId, prepare).also { harness.settle() }

    @Test
    fun backingOutOfThePickerChangesNothing() {
        val state = state()
        state.onPicked(null)
        assertEquals(SubmissionStage.CHOOSE, state.ui.value.stage)
        assertNull(state.ui.value.source)
    }

    @Test
    fun aPickedPhotoIsPreviewedThenProcessedAndUploaded() {
        val state = state()
        state.onPicked("content://photo/1")
        assertEquals(SubmissionStage.PREVIEW, state.ui.value.stage)
        assertEquals("content://photo/1", state.ui.value.source)
        assertEquals(0, prepareCalls)

        state.confirm()
        harness.settle()
        val ui = state.ui.value
        assertEquals(SubmissionStage.SUBMITTED, ui.stage)
        assertEquals(listOf("content://photo/1"), prepared)
        assertTrue(ui.submitted!!.isMine)
        assertEquals(1f, ui.progress)
        assertNull(ui.source)
        val view = runBlocking { ben.challenge(challengeId).value() }
        assertEquals(ui.submitted!!.id, view.myStatus.mySubmissionId)
    }

    @Test
    fun processingStagesAreReportedAndCancelReturnsToThePreview() {
        val gate = CompletableDeferred<Unit>()
        val state = state(prepare = { _, onStage ->
            onStage(PrepStage.DECODING)
            gate.await()
            photo()
        })
        state.onPicked("content://photo/2")
        state.confirm()
        harness.settle()
        assertEquals(SubmissionStage.PROCESSING, state.ui.value.stage)
        assertEquals(PrepStage.DECODING, state.ui.value.prepStage)

        state.cancel()
        harness.settle()
        assertEquals(SubmissionStage.PREVIEW, state.ui.value.stage)
        assertEquals("content://photo/2", state.ui.value.source)
        assertNull(state.ui.value.prepStage)
        gate.complete(Unit)
        harness.settle()
        assertEquals(SubmissionStage.PREVIEW, state.ui.value.stage)
    }

    @Test
    fun aPhotoThatCannotBeProcessedFailsAndCanBeRetried() {
        var attempts = 0
        val state = state(prepare = { _, _ ->
            attempts++
            if (attempts == 1) throw IllegalStateException("decoder") else photo()
        })
        state.onPicked("content://photo/3")
        state.confirm()
        harness.settle()
        assertEquals(SubmissionStage.FAILED, state.ui.value.stage)
        assertEquals(SubmissionFailure.Processing, state.ui.value.failure)

        state.retry()
        harness.settle()
        assertEquals(SubmissionStage.SUBMITTED, state.ui.value.stage)
        assertEquals(2, attempts)
    }

    @Test
    fun aFailedUploadIsRetriedWithoutProcessingAgain() {
        var uploads = 0
        val flaky = object : PhotoDuelService by ben {
            override suspend fun submitPhoto(challengeId: String, photo: PreparedPhoto, onProgress: (Float) -> Unit): AppResult<Submission> {
                uploads++
                if (uploads == 1) {
                    onProgress(0.4f)
                    return AppResult.Failure(PhotoDuelError(ErrorCode.TIMEOUT, "slow", retryable = true))
                }
                return ben.submitPhoto(challengeId, photo, onProgress)
            }
        }
        val state = state(service = flaky)
        state.onPicked("content://photo/4")
        state.confirm()
        harness.settle()
        assertEquals(SubmissionStage.FAILED, state.ui.value.stage)
        assertEquals(ErrorCode.TIMEOUT, (state.ui.value.failure as SubmissionFailure.Service).error.code)
        assertEquals(0.4f, state.ui.value.progress)

        state.retry()
        harness.settle()
        assertEquals(SubmissionStage.SUBMITTED, state.ui.value.stage)
        assertEquals(1, prepareCalls)
        assertEquals(2, uploads)
    }

    @Test
    fun anExistingPhotoIsShownAndCanBeReplacedOrKept() {
        val (existing) = friends.submit(challengeId, 1)
        val state = state()
        assertEquals(SubmissionStage.SUBMITTED, state.ui.value.stage)
        assertEquals(existing, state.ui.value.submitted?.id)

        state.chooseDifferent()
        assertEquals(SubmissionStage.CHOOSE, state.ui.value.stage)
        assertTrue(state.ui.value.replacing)
        // The photo on the server stays until a new one is accepted.
        assertEquals(existing, state.ui.value.submitted?.id)
        state.keepCurrent()
        assertEquals(SubmissionStage.SUBMITTED, state.ui.value.stage)
        assertFalse(state.ui.value.replacing)

        state.chooseDifferent()
        state.onPicked("content://photo/5")
        state.confirm()
        harness.settle()
        assertEquals(SubmissionStage.SUBMITTED, state.ui.value.stage)
        assertFalse(state.ui.value.replacing)
        val view = runBlocking { ben.challenge(challengeId).value() }
        assertEquals(1, view.submissions.count { it.isMine && it.status == SubmissionStatus.ACTIVE })
    }

    @Test
    fun withdrawingRemovesThePhoto() {
        friends.submit(challengeId, 1)
        val state = state()
        state.withdraw()
        assertTrue(state.ui.value.withdrawing)
        harness.settle()
        assertEquals(SubmissionStage.CHOOSE, state.ui.value.stage)
        assertNull(state.ui.value.submitted)
        assertFalse(state.ui.value.withdrawing)
        val view = runBlocking { ben.challenge(challengeId).value() }
        assertNull(view.myStatus.mySubmissionId)
    }

    @Test
    fun aLateUploadShowsTheServerRefusal() {
        val state = state()
        state.onPicked("content://photo/6")
        friends.closeSubmissions(submissionHours = 6)
        state.confirm()
        harness.settle()
        assertEquals(SubmissionStage.FAILED, state.ui.value.stage)
        val code = (state.ui.value.failure as SubmissionFailure.Service).error.code
        assertTrue(code.name, code == ErrorCode.DEADLINE_PASSED || code == ErrorCode.INVALID_TRANSITION)
    }

    @Test
    fun confirmIsIgnoredWithoutAPhoto() {
        val state = state()
        state.confirm()
        harness.settle()
        assertEquals(SubmissionStage.CHOOSE, state.ui.value.stage)
        assertEquals(0, prepareCalls)
    }
}
