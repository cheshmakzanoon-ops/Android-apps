package com.agency.stickerjet.ui.robot

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.platform.app.InstrumentationRegistry
import com.agency.stickerjet.R
import com.agency.stickerjet.ui.importing.ClipPicker
import com.agency.stickerjet.ui.importing.LocalClipPicker
import com.agency.stickerjet.ui.navigation.StickerJetHost
import com.agency.stickerjet.ui.theme.StickerJetTheme
import java.io.File

/**
 * Drives the full path a user takes: import a clip, edit it, render the preview, export and see that
 * the share action is offered. The system picker is replaced with a fixed clip, and every other port
 * is a fake from core testFixtures or a small double in this package.
 */
class ImportToShareRobot(
    private val rule: ComposeContentTestRule,
    private val root: File,
) {
    private fun string(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    /** Starts the host with a completed onboarding, so the library is the first screen. */
    fun launch(): ImportToShareRobot {
        val ports = robotPorts(root, onboardingCompleted = true)
        rule.setContent {
            StickerJetTheme {
                CompositionLocalProvider(
                    LocalClipPicker provides ClipPicker { onPicked -> onPicked(FIXED_CLIP) },
                ) {
                    StickerJetHost(ports = ports)
                }
            }
        }
        return this
    }

    fun tapImportFromEmptyLibrary(): ImportToShareRobot {
        rule.waitUntil(WAIT_MS) { nodeExists(string(R.string.library_empty_title)) }
        rule.onNodeWithText(string(R.string.action_import)).performClick()
        return this
    }

    fun chooseFixedClip(): ImportToShareRobot {
        rule.waitUntil(WAIT_MS) { nodeExists(string(R.string.import_choose)) }
        rule.onNodeWithText(string(R.string.import_choose)).performClick()
        return this
    }

    fun waitForEditor(): ImportToShareRobot {
        rule.waitUntil(WAIT_MS) { nodeExists(string(R.string.editor_tab_preview)) }
        return this
    }

    fun openPreviewTab(): ImportToShareRobot {
        rule.onNodeWithText(string(R.string.editor_tab_preview)).performClick()
        return this
    }

    fun exportSticker(): ImportToShareRobot {
        rule.waitUntil(WAIT_MS) { nodeExists(string(R.string.preview_export)) }
        rule.onNodeWithText(string(R.string.preview_export)).performClick()
        return this
    }

    fun assertExportedAndSaved(): ImportToShareRobot {
        rule.waitUntil(WAIT_MS) { nodeExists(string(R.string.preview_export_done_title)) }
        rule.onNodeWithText(string(R.string.preview_saved)).assertIsDisplayed()
        return this
    }

    fun assertShareOffered(): ImportToShareRobot {
        rule.onNodeWithText(string(R.string.preview_share)).assertIsEnabled()
        return this
    }

    private fun nodeExists(text: String): Boolean =
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        const val FIXED_CLIP = "content://robot/clip.mp4"
        const val WAIT_MS = 10_000L
    }
}
