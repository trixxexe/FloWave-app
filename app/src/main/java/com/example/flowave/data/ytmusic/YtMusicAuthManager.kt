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
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connection = MutableStateFlow(YtConnection.DISCONNECTED)
    val connection: StateFlow<YtConnection> = _connection.asStateFlow()

    init {
        scope.launch {
            runCatching {
                val initial = preferences.connection.first()
                if (initial.isConnected) {
                    _connection.value = initial
                } else {
                    tryConnectFromCookieManager()
                }
                preferences.connection.collect { persisted ->
                    if (persisted.isConnected || !_connection.value.isConnected) {
                        _connection.value = persisted
                    }
                }
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
     * Uses server-adjusted time when available so emulator clock skew never invalidates SAPISIDHASH.
     */
    fun authorizationHeaderValue(origin: String = AUTH_ORIGIN, account: YtConnection = connection.value): String? {
        val primaryToken = sapisid(account) ?: return null
        val timestamp = currentServerMillis() / 1000L
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
        if (_connection.value.isConnected) return _connection.value
        val persisted = preferences.connection.first()
        if (persisted.isConnected) {
            _connection.value = persisted
            return persisted
        }
        return _connection.value
    }

    /**
     * Normalizes YouTube's DATASYNC_ID (`onBehalfOfUser`).
     * For standard Google accounts, DATASYNC_ID is `<gaiaId>||` (second part blank) — sending
     * `onBehalfOfUser` for a personal account causes HTTP 400 INVALID_ARGUMENT on InnerTube.
     * Only Brand Accounts (`<brandPageId>||<ownerGaiaId>`, where both parts are non-blank and distinct)
     * require `onBehalfOfUser`.
     */
    private fun normalizeBrandSyncId(dataSyncId: String?): String? {
        val raw = dataSyncId?.trim()?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        if (!raw.contains("||")) return null
        val firstPart = raw.substringBefore("||").trim()
        val secondPart = raw.substringAfter("||").trim()
        return if (firstPart.isNotBlank() && secondPart.isNotBlank() && firstPart != secondPart) {
            firstPart
        } else {
            null
        }
    }

    /**
     * Immediately updates the in-memory connection stateFlow and persists to DataStore on
     * [scope] so closing the login screen never cancels the save operation.
     */
    fun connectImmediate(
        rawCookieHeader: String,
        accountName: String = "YouTube Music Account",
        channelHandle: String? = null,
        photoUrl: String? = null,
        visitorData: String? = null,
        dataSyncId: String? = null,
        authUserIndex: Int? = null
    ): Boolean {
        val cookies = parseCookieHeader(rawCookieHeader)
        if (cookies.isEmpty() || !hasRequiredSessionCookies(cookies)) return false
        val current = _connection.value
        val name = accountName.ifBlank {
            current.accountName.ifBlank { "YouTube Music Account" }
        }
        val effectiveVisitorData = visitorData?.takeIf { it.isNotBlank() && it != "null" } ?: current.visitorData
        val effectiveSyncId = normalizeBrandSyncId(dataSyncId) ?: current.onBehalfOfUser
        val effectiveAuthUser = authUserIndex ?: current.authUserIndex ?: 0

        val updated = YtConnection(
            cookies = cookies,
            accountName = name,
            channelHandle = channelHandle ?: current.channelHandle,
            photoUrl = photoUrl ?: current.photoUrl,
            connectedAtMillis = System.currentTimeMillis(),
            onBehalfOfUser = effectiveSyncId,
            authUserIndex = effectiveAuthUser,
            visitorData = effectiveVisitorData
        )
        _connection.value = updated

        scope.launch {
            runCatching {
                preferences.saveConnection(
                    cookies = cookies,
                    accountName = updated.accountName,
                    channelHandle = updated.channelHandle,
                    photoUrl = updated.photoUrl,
                    onBehalfOfUser = updated.onBehalfOfUser,
                    authUserIndex = updated.authUserIndex,
                    visitorData = updated.visitorData
                )
            }.onFailure {
                Log.w(TAG, "Failed to persist YT connection", it)
            }
        }
        return true
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
        val name = accountName.ifBlank { "YouTube Music Account" }
        val effectiveVisitorData = visitorData?.takeIf { it.isNotBlank() && it != "null" } ?: _connection.value.visitorData
        val effectiveSyncId = normalizeBrandSyncId(dataSyncId) ?: _connection.value.onBehalfOfUser
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
        val normalizedSyncId = normalizeBrandSyncId(dataSyncId) ?: current.onBehalfOfUser
        val effectiveVisitor = visitorData?.takeIf { it.isNotBlank() && it != "null" } ?: current.visitorData
        val cleanName = accountName.ifBlank { current.accountName.ifBlank { "YouTube Music Account" } }
        _connection.value = current.copy(
            accountName = cleanName,
            channelHandle = channelHandle ?: current.channelHandle,
            photoUrl = photoUrl ?: current.photoUrl,
            onBehalfOfUser = normalizedSyncId,
            visitorData = effectiveVisitor
        )
        preferences.saveConnection(
            cookies = current.cookies,
            accountName = cleanName,
            channelHandle = channelHandle ?: current.channelHandle,
            photoUrl = photoUrl ?: current.photoUrl,
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

    fun hasRequiredSessionCookies(cookies: Map<String, String>): Boolean {
        val hasSapisid = cookies.containsKey("SAPISID") ||
            cookies.containsKey(COOKIE_SAPISID_PRIMARY) ||
            cookies.containsKey("__Secure-1PAPISID") ||
            cookies.containsKey("APISID")
        val hasSid = cookies.containsKey("SID") ||
            cookies.containsKey("__Secure-3PSID") ||
            cookies.containsKey("__Secure-1PSID") ||
            cookies.containsKey("LOGIN_INFO")
        return hasSapisid && hasSid
    }

    /**
     * Harvests cookies directly from Android's CookieManager across YouTube domains
     * and connects immediately if valid session cookies are present.
     */
    fun tryConnectFromCookieManager(
        visitorData: String? = null,
        dataSyncId: String? = null,
        authUserIndex: Int? = null
    ): Boolean {
        return try {
            val cm = CookieManager.getInstance()
            cm.flush()
            val domains = listOf(
                "https://music.youtube.com",
                "https://www.youtube.com",
                "https://m.youtube.com",
                "https://youtube.com"
            )
            val merged = mutableMapOf<String, String>()
            for (domain in domains) {
                val raw = cm.getCookie(domain) ?: continue
                merged.putAll(parseCookieHeader(raw))
            }
            if (!hasRequiredSessionCookies(merged)) return false
            val rawHeader = merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
            connectImmediate(
                rawCookieHeader = rawHeader,
                accountName = _connection.value.accountName.ifBlank { "YouTube Music Account" },
                channelHandle = _connection.value.channelHandle,
                photoUrl = _connection.value.photoUrl,
                visitorData = visitorData,
                dataSyncId = dataSyncId,
                authUserIndex = authUserIndex
            )
        } catch (e: Throwable) {
            Log.w(TAG, "tryConnectFromCookieManager failed", e)
            false
        }
    }

    /**
     * Reads updated session cookies from Android's CookieManager across YouTube domains.
     * Returns true if fresh cookies were found and saved.
     */
    suspend fun refreshCookiesFromCookieManager(): Boolean {
        return try {
            val cm = CookieManager.getInstance()
            cm.flush()
            val domains = listOf(
                "https://music.youtube.com",
                "https://www.youtube.com",
                "https://m.youtube.com",
                "https://youtube.com"
            )
            val cookies = mutableMapOf<String, String>()
            for (domain in domains) {
                val raw = cm.getCookie(domain) ?: continue
                cookies.putAll(parseCookieHeader(raw))
            }
            if (!hasRequiredSessionCookies(cookies)) return false

            val current = connection.value
            if (current.isConnected && current.cookies == cookies) return false

            val accountName = current.accountName.ifBlank { "YouTube Music Account" }
            _connection.value = current.copy(
                cookies = cookies,
                accountName = accountName,
                connectedAtMillis = if (current.connectedAtMillis > 0L) current.connectedAtMillis else System.currentTimeMillis()
            )
            preferences.saveConnection(
                cookies = cookies,
                accountName = accountName,
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
        var serverTimeOffsetMs: Long = 0L

        fun updateServerTime(serverEpochMillis: Long) {
            if (serverEpochMillis > 1_600_000_000_000L) {
                serverTimeOffsetMs = serverEpochMillis - System.currentTimeMillis()
            }
        }

        fun currentServerMillis(): Long = System.currentTimeMillis() + serverTimeOffsetMs

        @Volatile
        private var instance: YtMusicAuthManager? = null

        fun getInstance(context: Context): YtMusicAuthManager =
            instance ?: synchronized(this) {
                instance ?: YtMusicAuthManager(YtMusicPreferences.getInstance(context)).also { instance = it }
            }
    }
}
