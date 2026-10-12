package com.agency.photoduel.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agency.photoduel.R
import com.agency.photoduel.domain.testing.FakeServerClock
import com.agency.photoduel.domain.testing.InMemoryPhotoDuelService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CreateRoomTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun creatingARoomOpensItWithTheInviteAction() {
        val clock = FakeServerClock()
        val service = InMemoryPhotoDuelService(clock)
        runBlocking { service.createGuest("Ada").value() }
        val sharing = RecordingSharing()
        rule.setContent {
            PhotoDuelApp(
                deps = uiDependencies(service, clock, sharing = sharing),
                onboardingCompleted = true,
                onOnboardingFinished = {},
                reduceMotion = true,
                liveClock = false,
            )
        }

        rule.waitForText(text(R.string.home_empty_title))
        rule.onNodeWithText(text(R.string.home_create_room)).performClick()
        rule.waitForText(text(R.string.create_room_label))
        rule.onNode(hasSetTextAction() and hasText(text(R.string.create_room_label))).performTextInput("Sunday walkers")
        rule.onNodeWithText(text(R.string.create_room_button)).performClick()

        // The room screen opens with the invite section and its action.
        rule.waitForText(text(R.string.room_invite_create))
        rule.onAllNodesWithText("Sunday walkers").onFirst().assertIsDisplayed()
        rule.onNodeWithText(text(R.string.room_invite_header)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(text(R.string.room_invite_create)).performScrollTo().assertIsDisplayed()

        // Creating the link offers the platform share sheet with the custom-scheme link.
        rule.onNodeWithText(text(R.string.room_invite_create)).performClick()
        rule.waitForText(text(R.string.room_invite_share))
        rule.onNodeWithText(text(R.string.room_invite_share)).performScrollTo().performClick()
        assertEquals(1, sharing.shared.size)
        assertEquals("Sunday walkers", sharing.shared.single().first)
        assertTrue(sharing.shared.single().second.startsWith("photoduel://invite/"))

        // The room exists on the service, not only on screen.
        val rooms = runBlocking { service.myRooms().value() }
        assertEquals(listOf("Sunday walkers"), rooms.map { it.name })
    }
}
