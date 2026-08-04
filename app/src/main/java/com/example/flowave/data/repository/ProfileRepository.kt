package com.example.flowave.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.flowave.data.model.UserProfile
import com.example.flowave.utils.SettingsSchema
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_profile_prefs")

class ProfileRepository(private val context: Context) {

    private val statDao = com.example.flowave.data.local.AppDatabase.getDatabase(context).statDao()

    private object PreferencesKeys {
        val USERNAME = stringPreferencesKey("username")
        val BIO = stringPreferencesKey("bio")
        val PREFERRED_QUALITY = stringPreferencesKey("preferred_quality")
        val PREFERRED_THEME = stringPreferencesKey("preferred_theme")
        val BACKGROUND_PRESET = stringPreferencesKey("background_preset")
        val CUSTOM_BG_URL = stringPreferencesKey("custom_bg_url")
        val AVATAR_URL = stringPreferencesKey("avatar_url")
        val STREAK_DAYS = intPreferencesKey("streak_days")
        val AUDIO_NORMALIZATION = booleanPreferencesKey("audio_normalization")
        val GAPLESS_PLAYBACK = booleanPreferencesKey("gapless_playback")
        val LAST_LISTEN_DATE = stringPreferencesKey("last_listen_date")
        val SETTINGS_JSON = stringPreferencesKey("dynamic_settings_json")
    }

    val settingsJson: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.SETTINGS_JSON] ?: SettingsSchema.getDefaultJson()
        }

    val userProfile: Flow<UserProfile> = combine(
        context.dataStore.data
            .catch { exception ->
                if (exception is java.io.IOException) emit(emptyPreferences())
                else throw exception
            },
        statDao.getTotalListeningTimeMs(),
        statDao.getTotalPlayCount()
    ) { preferences, totalMs, totalCount ->
        val json = preferences[PreferencesKeys.SETTINGS_JSON] ?: SettingsSchema.getDefaultJson()
        UserProfile(
            username = preferences[PreferencesKeys.USERNAME] ?: "Audio Enthusiast",
            bio = preferences[PreferencesKeys.BIO] ?: "Loving ultra-fidelity sound with FloWave",
            avatarUrl = preferences[PreferencesKeys.AVATAR_URL],
            preferredTheme = SettingsSchema.getValue(json, "preferred_theme")
                .takeIf { it.isNotEmpty() } ?: preferences[PreferencesKeys.PREFERRED_THEME] ?: "GLASS",
            preferredQuality = SettingsSchema.getValue(json, "preferred_quality")
                .takeIf { it.isNotEmpty() } ?: preferences[PreferencesKeys.PREFERRED_QUALITY] ?: "Ultra FLAC (24-bit)",
            customBgUrl = preferences[PreferencesKeys.CUSTOM_BG_URL],
            backgroundPreset = preferences[PreferencesKeys.BACKGROUND_PRESET] ?: "LIQUID_GLASS",
            totalTimeMs = totalMs ?: 0L,
            playCountTotal = totalCount,
            streakDays = preferences[PreferencesKeys.STREAK_DAYS] ?: 0
        )
    }

    val audioNormalization: Flow<Boolean> = settingsJson
        .map { SettingsSchema.getBoolean(it, "audio_normalization") }

    val gaplessPlayback: Flow<Boolean> = settingsJson
        .map { SettingsSchema.getBoolean(it, "gapless_playback") }

    suspend fun updateSetting(key: String, value: Any) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        context.dataStore.edit { preferences ->
            val currentJson = preferences[PreferencesKeys.SETTINGS_JSON] ?: SettingsSchema.getDefaultJson()
            val updatedJson = SettingsSchema.updateValue(currentJson, key, value)
            preferences[PreferencesKeys.SETTINGS_JSON] = updatedJson

            // Backwards compatibility sync
            when (key) {
                "audio_normalization" -> preferences[PreferencesKeys.AUDIO_NORMALIZATION] = value as Boolean
                "gapless_playback" -> preferences[PreferencesKeys.GAPLESS_PLAYBACK] = value as Boolean
                "preferred_theme" -> preferences[PreferencesKeys.PREFERRED_THEME] = value as String
                "preferred_quality" -> preferences[PreferencesKeys.PREFERRED_QUALITY] = value as String
            }
        }
    }

    suspend fun exportSettings(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val currentJson = settingsJson.first()
        val backupFile = File(context.cacheDir, "flowave_settings_backup.json")
        backupFile.writeText(currentJson)
        backupFile.absolutePath
    }

    suspend fun importSettings(jsonContent: String): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            org.json.JSONObject(jsonContent) // validate JSON structure
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.SETTINGS_JSON] = jsonContent

                // Restoring legacy keys for compatibility
                val optNorm = org.json.JSONObject(jsonContent).optBoolean("audio_normalization", true)
                val optGapless = org.json.JSONObject(jsonContent).optBoolean("gapless_playback", true)
                val optQuality = org.json.JSONObject(jsonContent).optString("preferred_quality", "Ultra FLAC (24-bit)")
                val optTheme = org.json.JSONObject(jsonContent).optString("preferred_theme", "GLASS")

                preferences[PreferencesKeys.AUDIO_NORMALIZATION] = optNorm
                preferences[PreferencesKeys.GAPLESS_PLAYBACK] = optGapless
                preferences[PreferencesKeys.PREFERRED_QUALITY] = optQuality
                preferences[PreferencesKeys.PREFERRED_THEME] = optTheme
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("ProfileRepository", "Failed to import settings: ${e.message}")
            false
        }
    }

    suspend fun updateUsername(username: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.USERNAME] = username
        }
    }

    suspend fun updateBio(bio: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.BIO] = bio
        }
    }

    suspend fun updateStreamingQuality(quality: String) {
        updateSetting("preferred_quality", quality)
    }

    suspend fun updateTheme(theme: String) {
        updateSetting("preferred_theme", theme)
    }

    suspend fun updateBackgroundPreset(preset: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.BACKGROUND_PRESET] = preset
        }
    }

    suspend fun updateCustomBgUrl(url: String?) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        context.dataStore.edit { preferences ->
            if (url != null) {
                preferences[PreferencesKeys.CUSTOM_BG_URL] = url
            } else {
                preferences.remove(PreferencesKeys.CUSTOM_BG_URL)
            }
        }
    }

    suspend fun updateAvatarUrl(url: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AVATAR_URL] = url
        }
    }

    suspend fun updateAudioSettings(normalization: Boolean, gapless: Boolean) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        context.dataStore.edit { preferences ->
            val currentJson = preferences[PreferencesKeys.SETTINGS_JSON] ?: SettingsSchema.getDefaultJson()
            var updatedJson = SettingsSchema.updateValue(currentJson, "audio_normalization", normalization)
            updatedJson = SettingsSchema.updateValue(updatedJson, "gapless_playback", gapless)
            preferences[PreferencesKeys.SETTINGS_JSON] = updatedJson

            preferences[PreferencesKeys.AUDIO_NORMALIZATION] = normalization
            preferences[PreferencesKeys.GAPLESS_PLAYBACK] = gapless
        }
    }

    suspend fun recordDailyListeningStreak() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        context.dataStore.edit { preferences ->
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val lastDate = preferences[PreferencesKeys.LAST_LISTEN_DATE]
            val currentStreak = preferences[PreferencesKeys.STREAK_DAYS] ?: 1

            if (lastDate == null) {
                preferences[PreferencesKeys.LAST_LISTEN_DATE] = today
                preferences[PreferencesKeys.STREAK_DAYS] = 1
            } else if (lastDate == today) {
                // Already listened today, keep streak
            } else {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                try {
                    val lastDateObj = sdf.parse(lastDate)
                    val todayObj = sdf.parse(today)
                    if (lastDateObj != null && todayObj != null) {
                        val diffDays = (todayObj.time - lastDateObj.time) / (1000 * 60 * 60 * 24)
                        if (diffDays == 1L) {
                            preferences[PreferencesKeys.STREAK_DAYS] = currentStreak + 1
                        } else if (diffDays > 1L) {
                            preferences[PreferencesKeys.STREAK_DAYS] = 1
                        }
                    }
                } catch (e: Exception) {
                    preferences[PreferencesKeys.STREAK_DAYS] = 1
                }
                preferences[PreferencesKeys.LAST_LISTEN_DATE] = today
            }
        }
    }
}
