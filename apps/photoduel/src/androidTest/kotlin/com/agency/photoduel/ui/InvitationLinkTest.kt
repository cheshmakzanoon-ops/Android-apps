package com.agency.photoduel.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agency.photoduel.R
import com.agency.photoduel.domain.model.InvitationTtl
import com.agency.photoduel.domain.testing.FakeServerClock
import com.agency.photoduel.domain.testing.InMemoryPhotoDuelService
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InvitationLinkTest {
    @get:Rule
    val rule = createComposeRule()

    /** Well formed (43 characters of base64url) but never issued by the server. */
    private val unknownToken = "A".repeat(43)

    @Test
    fun anInvalidInvitationLinkShowsOneClearMessage() {
        val clock = FakeServerClock()
        val service = InMemoryPhotoDuelService(clock)
        runBlocking { service.createGuest("Zed").value() }
        rule.setContent {
            PhotoDuelApp(
                // The token arrives the way the platform delivers photoduel://invite/{token}.
                deps = uiDependencies(service, clock, inviteTokens = flowOf(unknownToken)),
                onboardingCompleted = true,
                onOnboardingFinished = {},
                reduceMotion = true,
                liveClock = false,
            )
        }

        rule.waitForText(text(R.string.invite_invalid_title))
        rule.onNodeWithText(text(R.string.invite_invalid_body)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.invite_go_home)).assertIsDisplayed().performClick()
        rule.waitForText(text(R.string.home_empty_title))
        assertEquals(emptyList<Any>(), runBlocking { service.myRooms().value() })
    }

    @Test
    fun aValidInvitationJoinsTheRoom() {
        val clock = FakeServerClock()
        val owner = InMemoryPhotoDuelService(clock)
        val token = runBlocking {
            owner.createGuest("Ada").value()
            val room = owner.createRoom("Sunday walkers").value()
            owner.createInvitation(room.id, InvitationTtl.ONE_DAY).value().token
        }
        val guest = owner.newClient()
        runBlocking { guest.createGuest("Zed").value() }
        rule.setContent {
            PhotoDuelApp(
                deps = uiDependencies(guest, clock, inviteTokens = flowOf(token)),
                onboardingCompleted = true,
                onOnboardingFinished = {},
                reduceMotion = true,
                liveClock = false,
            )
        }

        rule.waitForText(text(R.string.invite_join_heading, "Sunday walkers"))
        rule.onNodeWithText(text(R.string.invite_owner, "Ada")).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.invite_join_button)).performClick()
        rule.waitForText(text(R.string.room_invite_create))
        assertEquals(listOf("Sunday walkers"), runBlocking { guest.myRooms().value() }.map { it.name })
    }
}
