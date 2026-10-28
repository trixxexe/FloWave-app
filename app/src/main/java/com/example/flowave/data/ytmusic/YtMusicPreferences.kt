package com.example.flowave.data.ytmusic

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

private val Context.ytDataStore: DataStore<Preferences> by preferencesDataStore(name = "yt_music_preferences")

/**
 * DataStore-backed persistence for connected YouTube Music account credentials,
 * identity, and playlist cache.
 */
class YtMusicPreferences private constructor(private val context: Context) {

    private val dataStore: DataStore<Preferences> = context.ytDataStore

    val connection: Flow<YtConnection> = dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences())
            else throw exception
        }
        .map { prefs ->
            val cookiesRaw = prefs[COOKIES_KEY].orEmpty()
            val cookies = decodeCookieMap(cookiesRaw)
            val name = prefs[ACCOUNT_NAME_KEY].orEmpty()
            if (cookies.isEmpty() || name.isBlank()) {
                YtConnection.DISCONNECTED
            } else {
                YtConnection(
                    cookies = cookies,
                    accountName = name,
                    channelHandle = prefs[CHANNEL_HANDLE_KEY],
                    photoUrl = prefs[PHOTO_URL_KEY],
                    connectedAtMillis = prefs[CONNECTED_AT_KEY] ?: 0L,
                    onBehalfOfUser = prefs[ON_BEHALF_OF_USER_KEY]?.takeIf { it.isNotBlank() },
                    authUserIndex = prefs[AUTH_USER_INDEX_KEY],
                    pageId = prefs[PAGE_ID_KEY].orEmpty()
                )
            }
        }

    val syncEnabled: Flow<Boolean> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[SYNC_ENABLED_KEY] ?: false }

    suspend fun saveConnection(
        cookies: Map<String, String>,
        accountName: String,
        channelHandle: String?,
        photoUrl: String?,
        onBehalfOfUser: String? = null,
        authUserIndex: Int? = null,
        pageId: String = ""
    ) {
        withContext(Dispatchers.IO) {
            dataStore.edit { prefs ->
                prefs[COOKIES_KEY] = encodeCookieMap(cookies)
                prefs[ACCOUNT_NAME_KEY] = accountName
                if (channelHandle != null) prefs[CHANNEL_HANDLE_KEY] = channelHandle else prefs.remove(CHANNEL_HANDLE_KEY)
                if (photoUrl != null) prefs[PHOTO_URL_KEY] = photoUrl else prefs.remove(PHOTO_URL_KEY)
                prefs[CONNECTED_AT_KEY] = System.currentTimeMillis()
                prefs[SYNC_ENABLED_KEY] = true
                if (!onBehalfOfUser.isNullOrBlank()) prefs[ON_BEHALF_OF_USER_KEY] = onBehalfOfUser else prefs.remove(ON_BEHALF_OF_USER_KEY)
                if (authUserIndex != null) prefs[AUTH_USER_INDEX_KEY] = authUserIndex else prefs.remove(AUTH_USER_INDEX_KEY)
                if (pageId.isNotBlank()) prefs[PAGE_ID_KEY] = pageId else prefs.remove(PAGE_ID_KEY)
            }
        }
    }

    suspend fun clearConnection() {
        withContext(Dispatchers.IO) {
            dataStore.edit { prefs ->
                prefs.remove(COOKIES_KEY)
                prefs.remove(ACCOUNT_NAME_KEY)
                prefs.remove(CHANNEL_HANDLE_KEY)
                prefs.remove(PHOTO_URL_KEY)
                prefs.remove(CONNECTED_AT_KEY)
                prefs.remove(ON_BEHALF_OF_USER_KEY)
                prefs.remove(AUTH_USER_INDEX_KEY)
                prefs.remove(PAGE_ID_KEY)
                prefs[SYNC_ENABLED_KEY] = false
            }
        }
    }

    companion object {
        private val COOKIES_KEY = stringPreferencesKey("ytm_cookies")
        private val ACCOUNT_NAME_KEY = stringPreferencesKey("ytm_account_name")
        private val CHANNEL_HANDLE_KEY = stringPreferencesKey("ytm_channel_handle")
        private val PHOTO_URL_KEY = stringPreferencesKey("ytm_photo_url")
        private val CONNECTED_AT_KEY = longPreferencesKey("ytm_connected_at")
        private val SYNC_ENABLED_KEY = booleanPreferencesKey("ytm_sync_enabled")
        private val ON_BEHALF_OF_USER_KEY = stringPreferencesKey("ytm_on_behalf_of_user")
        private val AUTH_USER_INDEX_KEY = intPreferencesKey("ytm_auth_user_index")
        private val PAGE_ID_KEY = stringPreferencesKey("ytm_page_id")

        @Volatile
        private var instance: YtMusicPreferences? = null

        fun getInstance(context: Context): YtMusicPreferences =
            instance ?: synchronized(this) {
                instance ?: YtMusicPreferences(context.applicationContext).also { instance = it }
            }

        private fun escapeJson(str: String): String =
            str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

        private fun unescapeJson(str: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < str.length) {
                val c = str[i]
                if (c == '\\' && i + 1 < str.length) {
                    when (val next = str[i + 1]) {
                        '"' -> { sb.append('"'); i += 2 }
                        '\\' -> { sb.append('\\'); i += 2 }
                        'n' -> { sb.append('\n'); i += 2 }
                        'r' -> { sb.append('\r'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        else -> { sb.append(next); i += 2 }
                    }
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }

        fun encodeCookieMap(map: Map<String, String>): String {
            val sb = StringBuilder("{")
            var first = true
            for ((k, v) in map) {
                if (!first) sb.append(",")
                first = false
                sb.append("\"").append(escapeJson(k)).append("\":\"").append(escapeJson(v)).append("\"")
            }
            sb.append("}")
            return sb.toString()
        }

        fun decodeCookieMap(json: String): Map<String, String> {
            if (json.isBlank() || json == "{}") return emptyMap()
            val map = mutableMapOf<String, String>()
            val pattern = Regex(""""((?:[^"\\]|\\.)*)"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            for (match in pattern.findAll(json)) {
                val k = unescapeJson(match.groupValues[1])
                val v = unescapeJson(match.groupValues[2])
                map[k] = v
            }
            return map
        }
    }
}
