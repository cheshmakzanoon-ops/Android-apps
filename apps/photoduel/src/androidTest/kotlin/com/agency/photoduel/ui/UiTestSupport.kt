package com.agency.photoduel.ui

import android.net.Uri
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import com.agency.photoduel.domain.model.AppResult
import com.agency.photoduel.domain.model.ChallengeSpec
import com.agency.photoduel.domain.model.InvitationTtl
import com.agency.photoduel.domain.model.PreparedPhoto
import com.agency.photoduel.domain.model.UserProfile
import com.agency.photoduel.domain.port.PhotoDuelService
import com.agency.photoduel.domain.testing.FakeServerClock
import com.agency.photoduel.domain.testing.InMemoryBackend
import com.agency.photoduel.domain.testing.InMemoryPhotoDuelService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

// Shared helpers for the Compose UI tests. Every test runs on the frozen fakes from domain/testing
// (InMemoryPhotoDuelService and FakeServerClock): the same rules as the server, no network, no
// invented data. Platform pieces (picker, processing, share sheet) are small recording fakes.

const val UI_TIMEOUT_MS = 5_000L

fun text(@StringRes id: Int, vararg args: Any): String =
    InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

fun plural(@PluralsRes id: Int, count: Int): String =
    InstrumentationRegistry.getInstrumentation().targetContext.resources.getQuantityString(id, count, count)

fun ComposeContentTestRule.waitForText(value: String, timeoutMillis: Long = UI_TIMEOUT_MS) {
    waitUntil(timeoutMillis) { onAllNodes(hasText(value)).fetchSemanticsNodes().isNotEmpty() }
}

fun ComposeContentTestRule.waitForDescription(value: String, timeoutMillis: Long = UI_TIMEOUT_MS) {
    waitUntil(timeoutMillis) { onAllNodes(hasContentDescription(value)).fetchSemanticsNodes().isNotEmpty() }
}

/** Scrolls the lazy list on screen until a node with [description] is visible. */
fun ComposeContentTestRule.scrollToDescription(description: String) {
    onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription(description))
}

fun SemanticsNodeInteractionsProvider.hasNoText(value: String): Boolean =
    onAllNodes(hasText(value)).fetchSemanticsNodes().isEmpty()

/** Records what the UI asked the platform to share. */
class RecordingSharing : InvitationSharing {
    val shared = mutableListOf<Pair<String, String>>()
    override fun share(roomName: String, inviteUrl: String) {
        shared += roomName to inviteUrl
    }
}

/** A picker that reports "the user backed out". The submission flow is not exercised by these tests. */
val NoPhotoPicking = PhotoPicking { onResult -> onResult(null) }

object NoPhotoProcessing : PhotoProcessing {
    override suspend fun prepare(source: Uri, onStage: (ProcessingStage) -> Unit): PreparedPhoto =
        throw IllegalStateException("photo processing is not used in this test")
}

fun uiDependencies(
    service: PhotoDuelService,
    clock: FakeServerClock,
    sharing: InvitationSharing = RecordingSharing(),
    inviteTokens: Flow<String> = emptyFlow(),
    serverConfigured: Boolean = true,
) = UiDependencies(
    service = service,
    clock = clock,
    photoPicking = NoPhotoPicking,
    photoProcessing = NoPhotoProcessing,
    invitationSharing = sharing,
    pendingInviteTokens = inviteTokens,
    serverConfigured = serverConfigured,
)

fun <T> AppResult<T>.value(): T = when (this) {
    is AppResult.Success -> value
    is AppResult.Failure -> throw AssertionError("expected success, got $error")
}

/** Small but valid photo for the in-memory service (short edge at least 200 px). */
fun testPhoto() = PreparedPhoto(ByteArray(64) { 1 }, ByteArray(16) { 2 }, 400, 300)

/**
 * Three friends in "Sunday walkers": Ada (owner, index 0), Ben and Cy. Each has their own client on one
 * shared in-memory backend, like three phones talking to one server.
 */
class ThreeFriends {
    val clock = FakeServerClock()
    val backend = InMemoryBackend(clock)
    val clients: List<InMemoryPhotoDuelService>
    val users: List<UserProfile>
    val roomId: String

    init {
        val ada = InMemoryPhotoDuelService(backend)
        val ben = ada.newClient()
        val cy = ada.newClient()
        clients = listOf(ada, ben, cy)
        val setup = runBlocking {
            val adaUser = ada.createGuest("Ada").value().user
            val room = ada.createRoom("Sunday walkers").value()
            val token = ada.createInvitation(room.id, InvitationTtl.THREE_DAYS).value().token
            val benUser = ben.createGuest("Ben").value().user
            ben.joinWithInvitation(token).value()
            val cyUser = cy.createGuest("Cy").value().user
            cy.joinWithInvitation(token).value()
            listOf(adaUser, benUser, cyUser) to room.id
        }
        users = setup.first
        roomId = setup.second
    }

    /** Creates and starts a one-hour challenge, everyone submits, and the clock moves into voting. */
    fun challengeInVoting(): Pair<String, List<String>> = runBlocking {
        val challenge = clients[0].createChallenge(roomId, ChallengeSpec("p-001", 1, 1)).value()
        clients[0].startChallenge(challenge.id).value()
        val submissions = clients.map { it.submitPhoto(challenge.id, testPhoto()) {}.value().id }
        clock.advanceByHours(1)
        clock.advanceBy(1)
        challenge.id to submissions
    }

    /** Each friend votes for the next one's photo: one vote each, a three-way tie. Then voting closes. */
    fun threeWayTie(): String {
        val (challengeId, submissions) = challengeInVoting()
        runBlocking {
            clients[0].castBallot(challengeId, submissions[1]).value()
            clients[1].castBallot(challengeId, submissions[2]).value()
            clients[2].castBallot(challengeId, submissions[0]).value()
        }
        clock.advanceByHours(1)
        clock.advanceBy(1)
        return challengeId
    }
}
