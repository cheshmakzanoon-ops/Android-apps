package com.agency.photoduel.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
class OnboardingToHomeTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun firstLaunchGoesThroughOnboardingAndGuestSignInToAnEmptyHome() {
        val clock = FakeServerClock()
        val service = InMemoryPhotoDuelService(clock)
        var finishedCalls = 0
        rule.setContent {
            PhotoDuelApp(
                deps = uiDependencies(service, clock),
                onboardingCompleted = false,
                onOnboardingFinished = { finishedCalls++ },
                reduceMotion = true,
                liveClock = false,
            )
        }

        // Three pages, then "Get started".
        rule.onNodeWithText(text(R.string.onboarding_1_title)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.onboarding_next)).performClick()
        rule.onNodeWithText(text(R.string.onboarding_2_title)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.onboarding_next)).performClick()
        rule.onNodeWithText(text(R.string.onboarding_3_title)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.onboarding_start)).performClick()
        rule.waitForIdle()
        assertEquals("completion is reported once through the callback", 1, finishedCalls)

        // No stored session: guest sign-in. An empty name is refused before any call.
        rule.waitForText(text(R.string.signin_guest_button))
        rule.onNodeWithText(text(R.string.signin_guest_button)).performClick()
        rule.onNodeWithText(text(R.string.name_problem_empty)).assertIsDisplayed()
        assertEquals(null, service.sessionToken)

        rule.onNode(hasSetTextAction() and hasText(text(R.string.signin_name_label))).performTextInput("Ada")
        rule.onNodeWithText(text(R.string.signin_guest_button)).performClick()

        // Home with no rooms, from the service.
        rule.waitForText(text(R.string.home_empty_title))
        rule.onNodeWithText(text(R.string.home_title)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.home_empty_body)).assertIsDisplayed()
        assertTrue(service.sessionToken != null)
        assertEquals("Ada", runBlocking { service.currentUser().value().displayName })
    }
}
