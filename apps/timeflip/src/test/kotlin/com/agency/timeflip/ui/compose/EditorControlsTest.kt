package com.agency.timeflip.ui.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.agency.timeflip.R
import com.agency.timeflip.domain.model.TransitionDefinition
import com.agency.timeflip.domain.model.TransitionKind
import com.agency.timeflip.ui.TimeFlipTheme
import com.agency.timeflip.ui.screens.editor.TransitionControls
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Transition chip selection under Robolectric. The controls are driven by a small host that holds the
 * state, the same way the editor's ViewModel does. It runs in CI; it is not run in the authoring sandbox.
 */
@RunWith(RobolectricTestRunner::class)
class EditorControlsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tappingATransitionChipSelectsIt() {
        val context = RuntimeEnvironment.getApplication()
        val horizontal = context.getString(R.string.transition_horizontal)
        val crossfade = context.getString(R.string.transition_crossfade)

        composeRule.setContent {
            TimeFlipTheme {
                var transition by remember { mutableStateOf(TransitionDefinition(TransitionKind.CROSSFADE)) }
                TransitionControls(
                    transition = transition,
                    onKindSelected = { kind -> transition = transition.copy(kind = kind) },
                    onEasingSelected = {},
                    onReversedChange = {},
                    onDurationPreview = {},
                    onDurationCommit = {},
                )
            }
        }

        composeRule.onNodeWithText(crossfade).assertIsSelected()
        composeRule.onNodeWithText(horizontal).assertIsNotSelected()

        composeRule.onNodeWithText(horizontal).performClick()

        composeRule.onNodeWithText(horizontal).assertIsSelected()
        composeRule.onNodeWithText(crossfade).assertIsNotSelected()
    }
}
