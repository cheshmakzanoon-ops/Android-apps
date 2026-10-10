package com.agency.timeflip.app

import com.agency.timeflip.data.prefs.UserPreferences
import com.agency.timeflip.ui.ports.OnboardingStore
import kotlinx.coroutines.flow.Flow

/** Adapts [UserPreferences] to the UI's first-run port. Holds no state of its own. */
internal class PreferencesOnboardingStore(private val preferences: UserPreferences) : OnboardingStore {
    override fun observeCompleted(): Flow<Boolean> = preferences.onboardingCompleted

    override suspend fun markCompleted() {
        preferences.markOnboardingCompleted()
    }
}
