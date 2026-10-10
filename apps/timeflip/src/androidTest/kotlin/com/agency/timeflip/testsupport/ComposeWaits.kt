package com.agency.timeflip.testsupport

import androidx.compose.ui.test.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString

/** Default wait for a label on screen. Generous enough for an emulator under load. */
const val UI_TIMEOUT_MS: Long = 15_000L

private const val MAX_SCREEN_DUMP_CHARS = 3_000

/**
 * Waits until [text] is on screen. On timeout it fails with the label that was expected and a dump of
 * the visible semantics tree, which shows what the screen actually displayed.
 */
fun ComposeTestRule.waitForText(text: String, timeoutMs: Long = UI_TIMEOUT_MS) {
    val appeared = runCatching {
        waitUntil(timeoutMs) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }.isSuccess
    if (!appeared) {
        val screen = onRoot().printToString().take(MAX_SCREEN_DUMP_CHARS)
        throw AssertionError("Expected the text \"$text\" within $timeoutMs ms. The screen showed:\n$screen")
    }
}
