package com.agency.stickerjet.ui.robot

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end UI path with fakes for every platform port: library, import, editor, preview, export and
 * the share offer. Runs on a device or emulator through connectedAndroidTest; it is not run in this
 * container, which has no Android SDK.
 */
@RunWith(AndroidJUnit4::class)
class ImportToShareJourneyTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun importEditExportAndShare_withoutSignIn() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "robot-projects")
            .apply { deleteRecursively() }
        ImportToShareRobot(rule, root)
            .launch()
            .tapImportFromEmptyLibrary()
            .chooseFixedClip()
            .waitForEditor()
            .openPreviewTab()
            .exportSticker()
            .assertExportedAndSaved()
            .assertShareOffered()
    }
}
