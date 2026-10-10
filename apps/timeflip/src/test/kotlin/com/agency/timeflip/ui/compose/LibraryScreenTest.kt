package com.agency.timeflip.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.agency.timeflip.R
import com.agency.timeflip.ui.BitmapLoader
import com.agency.timeflip.ui.TimeFlipTheme
import com.agency.timeflip.ui.screens.library.LibraryContent
import com.agency.timeflip.ui.screens.library.LibraryScreenContent
import com.agency.timeflip.ui.screens.library.LibraryUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Empty-state check for the library under Robolectric. It runs in CI; it is not run in the authoring sandbox.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyLibraryShowsTheEmptyTitleAndOneWorkingNewTransformationAction() {
        val context = RuntimeEnvironment.getApplication()
        var created = false

        composeRule.setContent {
            TimeFlipTheme {
                LibraryScreenContent(
                    state = LibraryUiState(content = LibraryContent.Empty),
                    loader = BitmapLoader { _, _ -> null },
                    onNewTransformation = { created = true },
                    onOpenProject = {},
                    onRename = {},
                    onDuplicate = {},
                    onDelete = {},
                    onOpenSettings = {},
                    onRetry = {},
                    onMessageShown = {},
                    onRenameDraftChange = {},
                    onConfirmRename = {},
                    onConfirmDelete = {},
                    onDismissDialog = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.library_empty_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.library_new)).performClick()
        assertTrue(created)
    }
}
