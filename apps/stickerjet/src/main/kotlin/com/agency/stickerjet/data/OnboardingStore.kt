package com.agency.stickerjet.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.onboardingStore by preferencesDataStore(name = "onboarding")

/**
 * First-run flag in Jetpack DataStore (Preferences). It lives only on this device, is never synced, and
 * is not part of any project.
 */
class OnboardingStore(context: Context) {

    private val store = context.applicationContext.onboardingStore

    /** False until [markCompleted] has succeeded. A damaged preferences file reads as false. */
    val completed: Flow<Boolean> = store.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs -> prefs[COMPLETED_KEY] ?: false }
        .distinctUntilChanged()

    /**
     * Stores the flag. A failed write is not fatal: onboarding is shown again on the next launch, so
     * the IOException is swallowed on purpose.
     */
    suspend fun markCompleted() {
        try {
            store.edit { prefs -> prefs[COMPLETED_KEY] = true }
        } catch (e: IOException) {
            // Deliberately ignored, see the KDoc above.
        }
    }

    private companion object {
        val COMPLETED_KEY = booleanPreferencesKey("onboarding_completed")
    }
}
