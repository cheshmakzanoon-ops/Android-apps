package com.agency.timeflip.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.agency.timeflip.domain.model.OutputPreset
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private const val PREFERENCES_FILE_NAME = "timeflip_prefs"

private val Context.timeflipPreferences: DataStore<Preferences> by preferencesDataStore(name = PREFERENCES_FILE_NAME)

/**
 * Small, non-identifying settings: whether onboarding is done and the last output preset name.
 * The preference file holds no project data, no photo references and no identifiers.
 */
class UserPreferences(context: Context) {

    private val store: DataStore<Preferences> = context.applicationContext.timeflipPreferences

    val onboardingCompleted: Flow<Boolean> = readPreferences().map { prefs ->
        prefs[ONBOARDING_COMPLETED] ?: false
    }

    suspend fun markOnboardingCompleted() {
        store.edit { prefs -> prefs[ONBOARDING_COMPLETED] = true }
    }

    /**
     * Enum name of the default output preset (for example "PORTRAIT_1080"). Unknown names, such as one left
     * by an older build, read as null so the caller falls back to its own default.
     */
    val lastExportPreset: Flow<String?> = readPreferences().map { prefs ->
        prefs[LAST_EXPORT_PRESET]?.takeIf { name -> isKnownPresetName(name) }
    }

    /** Stores the enum name of [preset], or clears the value when [preset] is null. */
    suspend fun setLastExportPreset(preset: OutputPreset?) {
        store.edit { prefs ->
            if (preset == null) {
                prefs.remove(LAST_EXPORT_PRESET)
            } else {
                prefs[LAST_EXPORT_PRESET] = preset.name
            }
        }
    }

    /** Removes every key, including onboarding state and the last export preset. Used by "Delete all data". */
    suspend fun clearAll() {
        store.edit { prefs -> prefs.clear() }
    }

    /** A corrupt or unreadable preference file reads as defaults instead of crashing the library screen. */
    private fun readPreferences(): Flow<Preferences> = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    private companion object {
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val LAST_EXPORT_PRESET = stringPreferencesKey("last_export_preset")

        fun isKnownPresetName(name: String): Boolean = OutputPreset.entries.any { it.name == name }
    }
}
