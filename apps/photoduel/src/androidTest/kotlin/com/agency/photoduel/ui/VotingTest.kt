package com.agency.photoduel.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isNotSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agency.photoduel.R
import com.agency.photoduel.ui.common.AppEnv
import com.agency.photoduel.ui.screens.voting.VotingScreen
import com.agency.photoduel.ui.state.NetworkStatus
import com.agency.photoduel.ui.theme.PhotoDuelTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VotingTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun tapSelectsAPhotoAndConfirmCastsTheBallot() {
        val friends = ThreeFriends()
        val (challengeId, submissions) = friends.challengeInVoting()
        val ada = friends.clients[0]
        val env = AppEnv(uiDependencies(ada, friends.clock), NetworkStatus(), viewerId = friends.users[0].id, liveClock = false)
        rule.setContent {
            PhotoDuelTheme(reduceMotion = true) {
                VotingScreen(env = env, challengeId = challengeId, onBack = {}, onOpenResults = {})
            }
        }

        rule.waitForText(text(R.string.vote_start))
        rule.onNodeWithText(text(R.string.vote_intro_2)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.vote_start)).performScrollTo().performClick()

        // The viewer's own photo is shown but cannot be chosen.
        val ownLabel = text(R.string.vote_own_photo_cd)
        rule.scrollToDescription(ownLabel)
        rule.onNodeWithContentDescription(ownLabel).assertIsDisplayed().assert(isNotSelectable()).performClick()
        assertNull(runBlocking { ada.challenge(challengeId).value().myStatus.myBallotSubmissionId })

        // A tap selects; the selection can change; nothing is cast yet.
        val benLabel = text(R.string.photo_by_cd, "Ben")
        val cyLabel = text(R.string.photo_by_cd, "Cy")
        rule.scrollToDescription(benLabel)
        rule.onNodeWithContentDescription(benLabel).performClick().assertIsSelected()
        rule.scrollToDescription(cyLabel)
        rule.onNodeWithContentDescription(cyLabel).performClick().assertIsSelected()
        rule.scrollToDescription(benLabel)
        rule.onNodeWithContentDescription(benLabel).assertIsNotSelected().performClick().assertIsSelected()
        assertNull(runBlocking { ada.challenge(challengeId).value().myStatus.myBallotSubmissionId })

        // The confirm step names the author, because votes are final.
        rule.onNodeWithText(text(R.string.vote_confirm_prompt, "Ben")).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.vote_confirm_button)).performClick()

        rule.waitForText(text(R.string.vote_you_voted_for, "Ben"))
        assertEquals(submissions[1], runBlocking { ada.challenge(challengeId).value().myStatus.myBallotSubmissionId })
        // After casting there is nothing left to confirm.
        assertTrue(rule.hasNoText(text(R.string.vote_confirm_button)))
    }
}
