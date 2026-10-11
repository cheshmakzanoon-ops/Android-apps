package com.agency.timeflip

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agency.timeflip.app.MainActivity
import com.agency.timeflip.platform.buildVideoShareIntent
import com.agency.timeflip.testsupport.JOURNEY_OUTPUT_DIR
import com.agency.timeflip.testsupport.SyntheticPhotoStore
import com.agency.timeflip.testsupport.TimeFlipTestEnvironment
import com.agency.timeflip.testsupport.UiLabels
import com.agency.timeflip.testsupport.UiTimeouts
import com.agency.timeflip.testsupport.waitForText
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * Journey: first launch to a shareable MP4, through the real UI, Room, the photo copy path (replaced by
 * a fake that writes synthetic JPEGs), alignment and export. The system picker is stubbed.
 *
 * Asserts only what the screen shows, plus the files the app writes. The exported MP4 is copied to
 * filesDir/journey-output/ (with journey.json holding the recorded width, height, fps and duration) so
 * the emulator job can pass it to benchmarks/tools/verify_mp4.py. If the file is missing, the test fails.
 */
@RunWith(AndroidJUnit4::class)
class TimeFlipJourneyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val photos = SyntheticPhotoStore(File(context.cacheDir, "journey-fixtures"))
    private val environment = TimeFlipTestEnvironment(
        photoStore = photos,
        pickerResults = listOf(BEFORE_PICK, AFTER_PICK),
    )

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(environment).around(composeRule)

    @Test
    fun firstLaunchProducesAndSharesAnMp4() {
        onboardingAndLibrary()
        choosePhotos()
        alignAndOpenEditor()
        exportAndShare()
    }

    private fun onboardingAndLibrary() {
        composeRule.waitForText(UiLabels.WELCOME)
        composeRule.onNodeWithText(UiLabels.WELCOME).assertIsDisplayed()
        composeRule.onNodeWithText(UiLabels.START).performClick()

        composeRule.waitForText(UiLabels.LIBRARY_EMPTY)
        composeRule.onNodeWithText(UiLabels.NEW_TRANSFORMATION).performClick()
    }

    private fun choosePhotos() {
        composeRule.waitForText(UiLabels.CHOOSE_BEFORE)
        composeRule.onNodeWithText(UiLabels.CHOOSE_BEFORE).performClick()
        composeRule.waitUntilImported(expected = 1)

        composeRule.waitForText(UiLabels.CHOOSE_AFTER)
        composeRule.onNodeWithText(UiLabels.CHOOSE_AFTER).performClick()
        composeRule.waitUntilImported(expected = 2)
    }

    private fun alignAndOpenEditor() {
        // Automatic alignment has finished when the editor entry point appears, whether the estimate
        // was accepted or flagged for manual adjustment.
        composeRule.waitForText(UiLabels.OPEN_EDITOR, timeoutMs = UiTimeouts.ALIGNMENT_MS)
        composeRule.onNodeWithText(UiLabels.OPEN_EDITOR).performClick()

        composeRule.waitForText(UiLabels.CROSSFADE)
        composeRule.onNodeWithText(UiLabels.CROSSFADE).performClick()
    }

    private fun exportAndShare() {
        composeRule.onNodeWithText(UiLabels.EXPORT).performClick()
        composeRule.waitForText(UiLabels.SHARE, timeoutMs = UiTimeouts.EXPORT_MS)

        val video = assertExportedMp4Exists()
        publishForCi(video)
        assertShareIntentBuilds(video)
    }

    private fun assertExportedMp4Exists(): File {
        val video = environment.container.exportsDir
            .listFiles { file -> file.extension == "mp4" }
            ?.maxByOrNull { it.lastModified() }
        assertTrue("Export finished on screen, but no MP4 exists in the app exports folder", video != null)
        val file = requireNotNull(video)
        assertTrue("The exported MP4 is empty (${file.length()} bytes)", file.length() > 0L)
        return file
    }

    /** Copies the MP4 and the recorded metadata to a fixed folder for the CI verification step. */
    private fun publishForCi(video: File) {
        val container = environment.container
        val summary = runBlocking { container.projects.observeProjects().first().single() }
        val recorded = runBlocking { container.projects.listExports(summary.id).first() }

        val outputDir = File(context.filesDir, JOURNEY_OUTPUT_DIR).apply { mkdirs() }
        video.copyTo(File(outputDir, "journey.mp4"), overwrite = true)
        File(outputDir, "journey.json").writeText(
            """{"width":${recorded.width},"height":${recorded.height},""" +
                """"fps":${recorded.fps},"durationMs":${recorded.durationMs}}""",
        )
    }

    /** Builds the share chooser without starting it, so the test never leaves the app. */
    private fun assertShareIntentBuilds(video: File) {
        val chooser = buildVideoShareIntent(context, video)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertTrue("The share chooser does not wrap an ACTION_SEND intent", send?.action == Intent.ACTION_SEND)
        assertEquals("video/mp4", send?.type)
        @Suppress("DEPRECATION")
        val stream = send?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertTrue("The share intent has no EXTRA_STREAM content URI", stream != null)
    }

    private fun ComposeTestRule.waitUntilImported(expected: Int) {
        val reached = runCatching {
            waitUntil(UiTimeouts.PICK_MS) { photos.importCount >= expected }
        }.isSuccess
        assertTrue(
            "Expected $expected photo import(s) after the picker result, saw ${photos.importCount}. " +
                "The picker result did not reach the import path.",
            reached,
        )
    }

    private companion object {
        /** Picker results. Their content is ignored; the test PhotoStore produces the pixels. */
        const val BEFORE_PICK = "content://com.agency.timeflip.test/before"
        const val AFTER_PICK = "content://com.agency.timeflip.test/after"
    }
}
