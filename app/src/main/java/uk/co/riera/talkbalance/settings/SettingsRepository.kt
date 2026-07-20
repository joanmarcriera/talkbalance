package uk.co.riera.talkbalance.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val SETTINGS_DATASTORE_NAME = "talkbalance_settings"
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = SETTINGS_DATASTORE_NAME)

class SettingsRepository(context: Context) {
    private val dataStore = context.settingsDataStore

    // Preference keys
    private val nudgeEnabledKey = booleanPreferencesKey("nudge_enabled")
    private val nudgeSecondsKey = intPreferencesKey("nudge_seconds")
    private val matchThresholdKey = floatPreferencesKey("match_threshold")
    private val keepScreenOnKey = booleanPreferencesKey("keep_screen_on")

    // Flow observables
    val nudgeEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[nudgeEnabledKey] ?: false
    }

    val nudgeSeconds: Flow<Int> = dataStore.data.map { preferences ->
        preferences[nudgeSecondsKey] ?: 45
    }

    val matchThreshold: Flow<Float> = dataStore.data.map { preferences ->
        preferences[matchThresholdKey] ?: 0.50f
    }

    val keepScreenOn: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[keepScreenOnKey] ?: true
    }

    // Suspend setters
    suspend fun setNudgeEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[nudgeEnabledKey] = enabled
        }
    }

    suspend fun setNudgeSeconds(seconds: Int) {
        // Clamp value between 15 and 120
        val clamped = seconds.coerceIn(15, 120)
        dataStore.edit { preferences ->
            preferences[nudgeSecondsKey] = clamped
        }
    }

    suspend fun setMatchThreshold(threshold: Float) {
        // Clamp value between 0.30 and 0.80
        val clamped = threshold.coerceIn(0.30f, 0.80f)
        dataStore.edit { preferences ->
            preferences[matchThresholdKey] = clamped
        }
    }

    suspend fun setKeepScreenOn(keepOn: Boolean) {
        dataStore.edit { preferences ->
            preferences[keepScreenOnKey] = keepOn
        }
    }

    /** Wipes every stored setting back to defaults. Used by "Delete all data". */
    suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }

    companion object {
        @Volatile
        private var INSTANCE: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context).also { INSTANCE = it }
            }
        }
    }
}
