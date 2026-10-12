package com.agency.photoduel.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agency.photoduel.R
import com.agency.photoduel.ui.common.AppEnv
import com.agency.photoduel.ui.screens.results.ResultsScreen
import com.agency.photoduel.ui.state.NetworkStatus
import com.agency.photoduel.ui.theme.PhotoDuelTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResultsRevealTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(reduceMotion: Boolean): ThreeFriends {
        val friends = ThreeFriends()
        val challengeId = friends.threeWayTie()
        val env = AppEnv(uiDependencies(friends.clients[0], friends.clock), NetworkStatus(), friends.users[0].id, liveClock = false)
        rule.setContent {
            PhotoDuelTheme(reduceMotion = reduceMotion) {
                ResultsScreen(env = env, challengeId = challengeId, onBack = {}, onOpenRoom = {}, onRematch = { _, _ -> })
            }
        }
        return friends
    }

    private fun assertTieRevealed() {
        rule.waitForText(text(R.string.results_tie_heading))
        rule.onNodeWithText(text(R.string.results_tie_body, 3)).assertIsDisplayed()
        val oneVote = plural(R.plurals.results_votes, 1)
        // Every co-winner gets a card, and every tally row says it won. No hidden tie-break.
        for (name in listOf("Ada", "Ben", "Cy")) {
            rule.onNodeWithContentDescription(text(R.string.results_winner_cd, name, oneVote)).performScrollTo().assertIsDisplayed()
            rule.onNodeWithContentDescription(text(R.string.results_row_winner_a11y, name, oneVote)).performScrollTo().assertIsDisplayed()
        }
        rule.onNodeWithText(text(R.string.results_ballots, 3, 3)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aTieRevealsEveryCoWinnerAfterTheBarsGrow() {
        show(reduceMotion = false)
        assertTieRevealed()
    }

    @Test
    fun withReducedMotionTheTieIsShownAtOnce() {
        show(reduceMotion = true)
        assertTieRevealed()
        // The "counting" step is skipped entirely.
        rule.waitForIdle()
        assertTrue("counting text should not remain", rule.hasNoText(text(R.string.results_counting)))
    }
}
