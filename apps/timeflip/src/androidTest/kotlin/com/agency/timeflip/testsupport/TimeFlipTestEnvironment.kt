package com.agency.timeflip.testsupport

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import com.agency.timeflip.app.AppContainer
import com.agency.timeflip.app.TimeFlipApplication
import com.agency.timeflip.domain.port.PhotoStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.anyOf
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Folder under the app's files directory where the journey leaves its MP4 for CI to read. */
const val JOURNEY_OUTPUT_DIR: String = "journey-output"

/**
 * Per-test state, set up before the Activity under test is created:
 * - waits for the process container's startup recovery to finish, then replaces it with a fresh
 *   [AppContainer] that uses [photoStore] when one is given (the test seam),
 * - removes every project, photo copy, export and preference left by an earlier test,
 * - stubs the system Photo Picker. Each pick returns the next entry of [pickerResults]; null means the
 *   user cancelled. Espresso-intents intercepts the request, so no system picker UI is shown.
 *
 * Use it as the OUTER rule (RuleChain.outerRule), so it runs before createAndroidComposeRule starts
 * the Activity.
 */
class TimeFlipTestEnvironment(
    private val photoStore: PhotoStore? = null,
    private val pickerResults: List<String?> = emptyList(),
) : TestRule {

    private val app: TimeFlipApplication = ApplicationProvider.getApplicationContext<TimeFlipApplication>()

    /** The container the Activity under test uses. Valid inside the test body. */
    lateinit var container: AppContainer
        private set

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            setUp()
            try {
                base.evaluate()
            } finally {
                tearDown()
            }
        }
    }

    private fun setUp() {
        runBlocking { app.container.startupRecovery.join() }

        val fresh = AppContainer(app, photoStore)
        runBlocking {
            fresh.startupRecovery.join()
            fresh.projects.deleteAllData()
            fresh.preferences.clearAll()
        }
        File(app.filesDir, JOURNEY_OUTPUT_DIR).deleteRecursively()
        app.replaceContainerForTests(fresh)
        container = fresh

        Intents.init()
        stubPicker()
    }

    private fun tearDown() {
        Intents.release()
        runBlocking { container.projects.deleteAllData() }
    }

    private fun stubPicker() {
        val remaining = ArrayDeque(pickerResults)
        Intents.intending(
            anyOf(
                hasAction(MediaStore.ACTION_PICK_IMAGES),
                hasAction(Intent.ACTION_GET_CONTENT),
                hasAction(Intent.ACTION_OPEN_DOCUMENT),
            ),
        ).respondWithFunction {
            val next = remaining.removeFirstOrNull()
            if (next == null) {
                Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            } else {
                Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.parse(next)))
            }
        }
    }
}
