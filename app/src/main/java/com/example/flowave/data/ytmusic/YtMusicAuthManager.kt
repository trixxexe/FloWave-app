package com.example.flowave.data.ytmusic

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * Manages the authenticated YouTube Music session, cookies, visitorData, dataSyncId,
 * and computes the per-request SAPISIDHASH Authorization header (including SAPISID1PHASH
 * and SAPISID3PHASH as used in ArchiveTune / Metrolist) for authenticated InnerTube endpoints.
 */
class YtMusicAuthManager private constructor(
    private val preferences: YtMusicPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connection = MutableStateFlow(YtConnection.DISCONNECTED)
    val connection: StateFlow<YtConnection> = _connection.asStateFlow()

    init {
        scope.launch {
            runCatching {
                preferences.connection.collect { _connection.value = it }
            }.onFailure {
                Log.w(TAG, "Failed to observe YT connection from preferences", it)
            }
        }
    }

    /** Extracts SAPISID token, falling back to __Secure-3PAPISID, __Secure-1PAPISID, and APISID. */
    fun sapisid(account: YtConnection = connection.value): String? =
        account.cookies["SAPISID"]
            ?: account.cookies[COOKIE_SAPISID_PRIMARY]
            ?: account.cookies["__Secure-1PAPISID"]
            ?: account.cookies["APISID"]

    /**
     * Builds `SAPISIDHASH <ts>_<sha1> SAPISID1PHASH <ts>_<sha1> SAPISID3PHASH <ts>_<sha1>`
     * matching ArchiveTune / Metrolist's InnerTube authentication header format.
     */
    fun authorizationHeaderValue(origin: String = AUTH_ORIGIN, account: YtConnection = connection.value): String? {
        val primaryToken = sapisid(account) ?: return null
        val timestamp = System.currentTimeMillis() / 1000
        fun sha1Hash(token: String): String {
            val payload = "$timestamp $token $origin"
            val digest = MessageDigest.getInstance("SHA-1").digest(payload.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
        val hashMain = sha1Hash(primaryToken)
        val token1p = account.cookies["__Secure-1PAPISID"] ?: primaryToken
        val token3p = account.cookies[COOKIE_SAPISID_PRIMARY] ?: primaryToken
        val hash1p = sha1Hash(token1p)
        val hash3p = sha1Hash(token3p)
        return "SAPISIDHASH ${timestamp}_$hashMain SAPISID1PHASH ${timestamp}_$hash1p SAPISID3PHASH ${timestamp}_$hash3p"
    }

    fun cookieHeaderValue(account: YtConnection = connection.value): String? {
        val cookies = account.cookies
        if (cookies.isEmpty()) return null
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    suspend fun awaitLoadedConnection(): YtConnection {
        val persisted = preferences.connection.first()
        return connection.first { it == persisted }
    }

    suspend fun connect(
        rawCookieHeader: String,
        accountName: String,
        channelHandle: String?,
        photoUrl: String?,
        visitorData: String? = null,
        dataSyncId: String? = null,
        authUserIndex: Int? = null
    ) {
        val cookies = parseCookieHeader(rawCookieHeader)
        if (cookies.isEmpty()) return
        val name = accountName.ifBlank { "Google account" }
        val normalizedSyncId = dataSyncId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { raw ->
                when {
                    raw.contains("||") -> raw.substringBefore("||").takeIf { it.isNotBlank() } ?: raw.substringAfter("||")
                    else -> raw
                }
            }
        val effectiveVisitorData = visitorData?.takeIf { it.isNotBlank() } ?: _connection.value.visitorData
        val effectiveSyncId = normalizedSyncId ?: _connection.value.onBehalfOfUser
        val effectiveAuthUser = authUserIndex ?: _connection.value.authUserIndex ?: 0

        _connection.value = YtConnection(
            cookies = cookies,
            accountName = name,
            channelHandle = channelHandle,
            photoUrl = photoUrl,
            connectedAtMillis = System.currentTimeMillis(),
            onBehalfOfUser = effectiveSyncId,
            authUserIndex = effectiveAuthUser,
            visitorData = effectiveVisitorData
        )
        preferences.saveConnection(
            cookies = cookies,
            accountName = name,
            channelHandle = channelHandle,
            photoUrl = photoUrl,
            onBehalfOfUser = effectiveSyncId,
            authUserIndex = effectiveAuthUser,
            visitorData = effectiveVisitorData
        )
    }

    suspend fun updateAccountIdentity(
        accountName: String,
        channelHandle: String?,
        photoUrl: String?,
        visitorData: String? = null,
        dataSyncId: String? = null
    ) {
        val current = connection.value
        if (!current.isConnected) return
        val normalizedSyncId = dataSyncId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { if (it.contains("||")) it.substringBefore("||").ifBlank { it.substringAfter("||") } else it }
            ?: current.onBehalfOfUser
        val effectiveVisitor = visitorData?.takeIf { it.isNotBlank() } ?: current.visitorData
        _connection.value = current.copy(
            accountName = accountName,
            channelHandle = channelHandle,
            photoUrl = photoUrl,
            onBehalfOfUser = normalizedSyncId,
            visitorData = effectiveVisitor
        )
        preferences.saveConnection(
            cookies = current.cookies,
            accountName = accountName,
            channelHandle = channelHandle,
            photoUrl = photoUrl,
            onBehalfOfUser = normalizedSyncId,
            authUserIndex = current.authUserIndex,
            pageId = current.pageId,
            visitorData = effectiveVisitor
        )
    }

    /** Clears session state, DataStore, and Android WebView cookies. */
    suspend fun signOut() {
        _connection.value = YtConnection.DISCONNECTED
        preferences.clearConnection()
        runCatching {
            withContext(Dispatchers.Main) {
                val cm = CookieManager.getInstance()
                suspendCancellableCoroutine<Boolean> { cont ->
                    cm.removeAllCookies { result ->
                        if (cont.isActive) cont.resume(result)
                    }
                }
                cm.flush()
                WebStorage.getInstance().deleteAllData()
            }
        }
    }

    /**
     * Reads updated session cookies from Android's CookieManager across Google and YouTube domains.
     * Returns true if fresh cookies were found and saved.
     */
    suspend fun refreshCookiesFromCookieManager(): Boolean {
        val current = connection.value
        if (!current.isConnected) return false

        return try {
            val cm = CookieManager.getInstance()
            cm.flush()
            val domains = listOf(
                "https://music.youtube.com",
                "https://www.youtube.com",
                "https://accounts.google.com",
                "https://youtube.com"
            )
            val combined = domains.mapNotNull { cm.getCookie(it)?.takeIf(String::isNotBlank) }
                .joinToString("; ")
            if (combined.isBlank()) return false
            val cookies = parseCookieHeader(combined)
            val hasSapisid = cookies.containsKey("SAPISID") ||
                cookies.containsKey(COOKIE_SAPISID_PRIMARY) ||
                cookies.containsKey("__Secure-1PAPISID") ||
                cookies.containsKey("APISID")
            val hasSid = cookies.containsKey("SID") ||
                cookies.containsKey("__Secure-3PSID") ||
                cookies.containsKey("__Secure-1PSID")
            if (!hasSapisid || !hasSid) return false

            if (current.cookies == cookies) return false

            _connection.value = current.copy(cookies = cookies)
            preferences.saveConnection(
                cookies = cookies,
                accountName = current.accountName.ifBlank { "Google account" },
                channelHandle = current.channelHandle,
                photoUrl = current.photoUrl,
                onBehalfOfUser = current.onBehalfOfUser,
                authUserIndex = current.authUserIndex,
                pageId = current.pageId,
                visitorData = current.visitorData
            )
            Log.i(TAG, "Auto-refreshed YouTube Music session cookies from CookieManager")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Could not auto-refresh cookies from CookieManager", e)
            false
        }
    }

    fun parseCookieHeader(raw: String): Map<String, String> =
        raw.split(';')
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) null else {
                    val name = pair.substring(0, idx).trim()
                    val value = pair.substring(idx + 1).trim()
                    if (name.isBlank() || value.isBlank()) null else name to value
                }
            }
            .toMap()

    companion object {
        private const val TAG = "YtMusicAuthManager"
        const val COOKIE_SAPISID_PRIMARY = "__Secure-3PAPISID"
        const val AUTH_ORIGIN = "https://music.youtube.com"

        @Volatile
        private var instance: YtMusicAuthManager? = null

        fun getInstance(context: Context): YtMusicAuthManager =
            instance ?: synchronized(this) {
                instance ?: YtMusicAuthManager(YtMusicPreferences.getInstance(context)).also { instance = it }
            }
    }
}
