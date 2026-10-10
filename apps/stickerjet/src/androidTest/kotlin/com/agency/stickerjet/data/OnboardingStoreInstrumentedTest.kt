package com.agency.stickerjet.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OnboardingStore on a device or emulator. The flag must reach the app's DataStore file on disk
 * and be read back by a new store instance. A cross-process restart cannot be simulated here.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingStoreInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun markCompletedIsWrittenToTheDataStoreFile() = runBlocking {
        OnboardingStore(context).markCompleted()

        assertTrue(
            "preferences file missing after markCompleted",
            File(context.filesDir, "datastore/onboarding.preferences_pb").isFile,
        )
    }

    @Test
    fun completedFlagIsReadByANewStoreInstance() = runBlocking {
        OnboardingStore(context).markCompleted()

        assertTrue(OnboardingStore(context).completed.first())
    }
}
