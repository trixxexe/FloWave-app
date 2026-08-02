package com.example.flowave.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.flowave.data.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_profile_prefs")

class ProfileRepository(private val context: Context) {

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
    }

    val userProfile: Flow<UserProfile> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            UserProfile(
                username = preferences[PreferencesKeys.USERNAME] ?: "Audio Enthusiast",
                bio = preferences[PreferencesKeys.BIO] ?: "Loving ultra-fidelity sound with FloWave",
                avatarUrl = preferences[PreferencesKeys.AVATAR_URL],
                preferredTheme = preferences[PreferencesKeys.PREFERRED_THEME] ?: "GLASS",
                preferredQuality = preferences[PreferencesKeys.PREFERRED_QUALITY] ?: "Ultra FLAC (24-bit)",
                customBgUrl = preferences[PreferencesKeys.CUSTOM_BG_URL],
                backgroundPreset = preferences[PreferencesKeys.BACKGROUND_PRESET] ?: "LIQUID_GLASS",
                streakDays = preferences[PreferencesKeys.STREAK_DAYS] ?: 5
            )
        }

    val audioNormalization: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { preferences -> preferences[PreferencesKeys.AUDIO_NORMALIZATION] ?: true }

    val gaplessPlayback: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { preferences -> preferences[PreferencesKeys.GAPLESS_PLAYBACK] ?: true }

    suspend fun updateUsername(username: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.USERNAME] = username
        }
    }

    suspend fun updateBio(bio: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.BIO] = bio
        }
    }

    suspend fun updateStreamingQuality(quality: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PREFERRED_QUALITY] = quality
        }
    }

    suspend fun updateTheme(theme: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PREFERRED_THEME] = theme
        }
    }

    suspend fun updateBackgroundPreset(preset: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.BACKGROUND_PRESET] = preset
        }
    }

    suspend fun updateCustomBgUrl(url: String?) {
        context.dataStore.edit { preferences ->
            if (url != null) {
                preferences[PreferencesKeys.CUSTOM_BG_URL] = url
            } else {
                preferences.remove(PreferencesKeys.CUSTOM_BG_URL)
            }
        }
    }

    suspend fun updateAvatarUrl(url: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AVATAR_URL] = url
        }
    }

    suspend fun updateAudioSettings(normalization: Boolean, gapless: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUDIO_NORMALIZATION] = normalization
            preferences[PreferencesKeys.GAPLESS_PLAYBACK] = gapless
        }
    }

    suspend fun recordDailyListeningStreak() {
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
