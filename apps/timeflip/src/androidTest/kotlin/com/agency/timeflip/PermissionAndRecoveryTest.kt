package com.agency.timeflip

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agency.timeflip.app.MainActivity
import com.agency.timeflip.domain.model.AlignmentSource
import com.agency.timeflip.domain.model.AlignmentTransform
import com.agency.timeflip.domain.model.ExportProfile
import com.agency.timeflip.domain.model.ImagePair
import com.agency.timeflip.domain.model.PhotoSlot
import com.agency.timeflip.domain.model.PhotoSourceRef
import com.agency.timeflip.domain.model.ProjectId
import com.agency.timeflip.domain.model.TimeFlipProject
import com.agency.timeflip.domain.model.TransitionDefinition
import com.agency.timeflip.domain.model.TransitionKind
import com.agency.timeflip.testsupport.SyntheticJpeg
import com.agency.timeflip.testsupport.TimeFlipTestEnvironment
import com.agency.timeflip.testsupport.UiLabels
import com.agency.timeflip.testsupport.UiTimeouts
import com.agency.timeflip.testsupport.waitForText
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * Recovery and denial paths on the real storage (no photo fake).
 *
 * - A private photo copy that disappears after import is reported by StorageRecovery, and the library
 *   still opens with the project in it.
 * - A cancelled picker (no grant) changes nothing and the wizard keeps offering the choice.
 */
@RunWith(AndroidJUnit4::class)
class PermissionAndRecoveryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val environment = TimeFlipTestEnvironment(
        photoStore = null,
        pickerResults = listOf(null),
    )

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(environment).around(composeRule)

    @Test
    fun missingPrivatePhotoCopyIsReportedByRecoveryAndLibraryStillOpens() {
        composeRule.waitForText(UiLabels.WELCOME)
        composeRule.onNodeWithText(UiLabels.START).performClick()
        composeRule.waitForText(UiLabels.LIBRARY_EMPTY)

        val container = environment.container
        val fixtures = File(context.cacheDir, "recovery-fixtures")
        val beforeSource = File(fixtures, "before.jpg").also { SyntheticJpeg.write(it, PhotoSlot.BEFORE) }
        val afterSource = File(fixtures, "after.jpg").also { SyntheticJpeg.write(it, PhotoSlot.AFTER) }

        val copyId = runBlocking {
            val now = System.currentTimeMillis()
            // The real PhotoStore copies the file into private storage, exactly as the picker path does.
            val before = container.photos.importPhoto(PhotoSourceRef(Uri.fromFile(beforeSource).toString()), PhotoSlot.BEFORE, now)
            val after = container.photos.importPhoto(PhotoSourceRef(Uri.fromFile(afterSource).toString()), PhotoSlot.AFTER, now)
            container.projects.saveProject(
                TimeFlipProject(
                    id = ProjectId("recovery-check"),
                    name = PROJECT_NAME,
                    pair = ImagePair(before, after),
                    alignment = AlignmentTransform.IDENTITY,
                    alignmentSource = AlignmentSource.UNALIGNED,
                    alignmentConfidence = null,
                    transition = TransitionDefinition(TransitionKind.CROSSFADE),
                    exportProfile = ExportProfile(),
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                ),
            )
            before.id
        }

        // Simulate damaged storage: the private copy of the BEFORE photo disappears after import.
        val copyPath = runBlocking { container.photos.originalPath(copyId) }
        assertNotNull("The import did not create a private photo copy", copyPath)
        assertTrue("Could not delete the private copy for the test", File(requireNotNull(copyPath)).delete())

        val report = runBlocking { container.storageRecovery.recover() }
        assertTrue(
            "StorageRecovery did not report the missing private copy. Reported ${report.missingPhotos.size} missing photo(s).",
            report.missingPhotos.any { it.photoId == copyId },
        )

        // The library still opens and lists the project, even though one photo copy is gone.
        composeRule.waitForText(PROJECT_NAME)
        composeRule.onNodeWithText(PROJECT_NAME).assertIsDisplayed()
        composeRule.onNodeWithText(UiLabels.NEW_TRANSFORMATION).assertIsDisplayed()
    }

    @Test
    fun cancelledPickerLeavesTheWizardUsable() {
        composeRule.waitForText(UiLabels.WELCOME)
        composeRule.onNodeWithText(UiLabels.START).performClick()
        composeRule.waitForText(UiLabels.LIBRARY_EMPTY)
        composeRule.onNodeWithText(UiLabels.NEW_TRANSFORMATION).performClick()

        composeRule.waitForText(UiLabels.CHOOSE_BEFORE)
        composeRule.onNodeWithText(UiLabels.CHOOSE_BEFORE).performClick()

        // The picker returned no item. The wizard must keep offering the choice, with no crash.
        composeRule.waitForText(UiLabels.CHOOSE_BEFORE, timeoutMs = UiTimeouts.PICK_MS)
        composeRule.onNodeWithText(UiLabels.CHOOSE_BEFORE).assertIsDisplayed()
    }

    private companion object {
        const val PROJECT_NAME = "Recovery check"
    }
}
