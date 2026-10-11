package com.agency.stickerjet.ui.accessibility

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agency.stickerjet.R
import com.agency.stickerjet.ui.navigation.StickerJetHost
import com.agency.stickerjet.ui.robot.robotPorts
import com.agency.stickerjet.ui.theme.StickerJetTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Spoken labels and touch targets. Icon-only controls must have a description, and every control must
 * be at least 48 dp square. Runs under connectedAndroidTest only.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AccessibilityLabelsTest {

    @get:Rule
    val rule = createComposeRule()

    private fun string(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun root(): File = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "a11y")

    @Test
    fun library_settings_icon_is_labelled_and_meets_the_48dp_target() {
        rule.setContent {
            StickerJetTheme { StickerJetHost(ports = robotPorts(root(), onboardingCompleted = true)) }
        }
        rule.onNodeWithContentDescription(string(R.string.action_settings))
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun first_run_welcome_states_the_privacy_promise_and_offers_one_action() {
        rule.setContent {
            StickerJetTheme { StickerJetHost(ports = robotPorts(root(), onboardingCompleted = false)) }
        }
        rule.waitUntilExactlyOneExists(androidx.compose.ui.test.hasText(string(R.string.welcome_privacy_title)), 10_000L)
        rule.onNodeWithText(string(R.string.welcome_cta))
            .assertHasClickAction()
            .assertIsEnabled()
            .assertHeightIsAtLeast(48.dp)
    }
}
