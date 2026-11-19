package com.example.flowave.data.remote

import android.content.Context
import com.example.flowave.FloWaveApplication
import com.example.flowave.audio.OnlinePlaybackPolicy
import com.example.flowave.data.model.InnerTubeTrack
import com.example.flowave.data.model.LrcLine
import com.example.flowave.data.model.Track
import com.example.flowave.data.ytmusic.YtAccountInfo
import com.example.flowave.data.ytmusic.YtMusicAuthManager
import com.example.flowave.data.ytmusic.YtPlaylistSummary
import com.example.flowave.diagnostics.FloWaveLogger
import com.example.flowave.downloader.SealStyleDownloadEngine
import com.example.flowave.utils.FloWaveConstants
import com.example.flowave.utils.YouTubeDecipherer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class InnerTubeClientConfig(
    val clientName: String,
    val clientVersion: String,
    val userAgent: String,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val osName: String? = null,
    val osVersion: String? = null,
    val androidSdkVersion: Int? = null,
    val clientNumber: String? = null,
    val hl: String = "en",
    val gl: String = "US"
)

data class ResolvedStreamSource(
    val url: String,
    val resolver: String,
    val candidateKey: String? = null,
    val userAgent: String? = null,
    val requestHeaders: Map<String, String> = emptyMap()
)

object InnerTubeClients {
    val IOS = InnerTubeClientConfig(
        clientName = "IOS",
        clientVersion = "20.10.4",
        deviceMake = "Apple",
        deviceModel = "iPhone16,2",
        osName = "iPhone",
        osVersion = "18.3.2.22D82",
        hl = "en",
        gl = "US",
        userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
        clientNumber = "5"
    )

    val VISIONOS = InnerTubeClientConfig(
        clientName = "VISIONOS",
        clientVersion = "1.02",
        deviceMake = "Apple",
        deviceModel = "RealityDevice14,1",
        osName = "visionOS",
        osVersion = "1.3.21O771",
        hl = "en",
        gl = "US",
        userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
        clientNumber = "101"
    )

    val ANDROID_VR = InnerTubeClientConfig(
        clientName = "ANDROID_VR",
        clientVersion = "1.56.21",
        deviceMake = "Oculus",
        deviceModel = "Quest 2",
        osName = "Android",
        osVersion = "12",
        androidSdkVersion = 32,
        hl = "en",
        gl = "US",
        userAgent = "com.google.android.apps.youtube.vr.oculus/1.56.21 (Linux; U; Android 12; en_US; Quest 2; Build/SQ3A.220605.009.A1) gzip",
        clientNumber = "28"
    )

    val TVHTML5_SIMPLY_EMBEDDED_PLAYER = InnerTubeClientConfig(
        clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
        clientVersion = "2.0",
        hl = "en",
        gl = "US",
        userAgent = "Mozilla/5.0 (PlayStation; PlayStation 4/11.50) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
        clientNumber = "85"
    )

    val WEB_REMIX = InnerTubeClientConfig(
        clientName = "WEB_REMIX",
        clientVersion = "1.20250122.01.00",
        userAgent = FloWaveConstants.USER_AGENT_DESKTOP,
        clientNumber = "67"
    )

    val STREAM_FALLBACK_CHAIN = listOf(IOS, VISIONOS, ANDROID_VR, TVHTML5_SIMPLY_EMBEDDED_PLAYER)

    val FALLBACK_CHAIN = STREAM_FALLBACK_CHAIN
}

open class InnerTubeRepository(context: Context? = null) {
    private val appContext = context?.applicationContext ?: FloWaveApplication.appContext
    private val logger = appContext?.let { FloWaveLogger.getInstance(it) }
    private val authManager = appContext?.let { YtMusicAuthManager.getInstance(it) }
    private val client = OkHttpClient.Builder()
        .connectTimeout(FloWaveConstants.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(FloWaveConstants.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            response.headers.getDate("Date")?.time?.let { serverDateMs ->
                YtMusicAuthManager.updateServerTime(serverDateMs)
            }
            response
        }
        .build()

    private val probeClient = client.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val androidVrQuest3 = InnerTubeClientConfig(
        clientName = "ANDROID_VR",
        clientVersion = "1.62.27",
        deviceMake = "Oculus",
        deviceModel = "Quest 3",
        osName = "Android",
        osVersion = "14",
        androidSdkVersion = 34,
        hl = "en",
        gl = "US",
        userAgent = "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 14; en_US; Quest 3; Build/UP1A.231005.007.A1) gzip",
        clientNumber = "28"
    )

    private val androidTestSuite = InnerTubeClientConfig(
        clientName = "ANDROID_TESTSUITE",
        clientVersion = "1.9",
        deviceMake = "Google",
        deviceModel = "Pixel 8",
        osName = "Android",
        osVersion = "14",
        androidSdkVersion = 34,
        hl = "en",
        gl = "US",
        userAgent = "com.google.android.youtube/1.9 (Linux; U; Android 14; en_US) gzip",
        clientNumber = "30"
    )

    // Prioritize fast direct clients (ANDROID_VR, WEB_REMIX, TVHTML5_SIMPLY_EMBEDDED_PLAYER, IOS, VISIONOS),
    // verifying every candidate URL with a lightweight Range probe against googlevideo.com before playback.
    private val extractionPriorityChain = listOf(
        InnerTubeClients.ANDROID_VR,
        androidVrQuest3,
        InnerTubeClients.WEB_REMIX,
        InnerTubeClients.TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        InnerTubeClients.IOS,
        InnerTubeClients.VISIONOS,
        androidTestSuite
    )

    // Cache stream URLs and resolutions for 30 minutes to avoid re-querying YouTube endpoints
    private val streamUrlCache = ConcurrentHashMap<String, Pair<Long, String>>()
    private val streamResolutionCache = ConcurrentHashMap<String, Pair<Long, ResolvedStreamSource>>()
    private val streamResolutionLocks = ConcurrentHashMap<String, Mutex>()
    val streamDurationCache = ConcurrentHashMap<String, Long>()

    fun getCachedDuration(videoId: String): Long {
        return streamDurationCache[videoId] ?: 0L
    }

    fun invalidateStreamUrl(videoId: String) {
        streamUrlCache.remove(videoId)
        streamResolutionCache.remove(videoId)
        logger?.debug("online", "stream_cache_invalidated", context = mapOf("videoId" to videoId))
    }

    fun getCachedResolution(videoId: String): ResolvedStreamSource? {
        return streamResolutionCache[videoId]?.second
    }

    @Volatile
    private var cachedVisitorData: String? = null
    private val visitorDataLock = Mutex()

    private fun applyAuthHeaders(
        reqBuilder: Request.Builder,
        contextJson: JSONObject? = null,
        origin: String = "https://music.youtube.com",
        visitorDataOverride: String? = null
    ) {
        val auth = authManager ?: return
        if (!auth.connection.value.isConnected) {
            auth.tryConnectFromCookieManager()
        }
        val conn = auth.connection.value
        val activeVisitorData = visitorDataOverride?.takeIf { it.isNotBlank() }
            ?: conn.visitorData?.takeIf { it.isNotBlank() }
            ?: cachedVisitorData?.takeIf { it.isNotBlank() }

        if (!activeVisitorData.isNullOrBlank()) {
            reqBuilder.header("X-Goog-Visitor-Id", activeVisitorData)
            contextJson?.optJSONObject("client")?.put("visitorData", activeVisitorData)
        }

        if (conn.isConnected) {
            reqBuilder.header("Origin", origin)
            reqBuilder.header("Referer", "$origin/")
            reqBuilder.header("X-Origin", origin)
            auth.cookieHeaderValue(conn)?.let { cookie ->
                reqBuilder.header(
                    "Cookie",
                    "CONSENT=YES+cb.20210328-17-p0.en+FX+100; SOCS=CAISEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg; $cookie"
                )
            }
            auth.authorizationHeaderValue(origin = origin, account = conn)?.let { authHeader ->
                reqBuilder.header("Authorization", authHeader)
            }
            reqBuilder.header("X-Goog-AuthUser", (conn.authUserIndex ?: 0).toString())

            // Only attach onBehalfOfUser if it's an explicit Brand Account pageId
            conn.pageId?.takeIf { it.isNotBlank() }?.let { brandPageId ->
                contextJson?.put("user", JSONObject().apply {
                    put("onBehalfOfUser", brandPageId)
                    put("lockedSafetyMode", false)
                })
            }
        }
    }

    suspend fun getVisitorData(forceRefresh: Boolean = false): String? = withContext(Dispatchers.IO) {
        val authVisitor = authManager?.connection?.value?.visitorData?.takeIf { it.isNotBlank() }
        if (!forceRefresh && !authVisitor.isNullOrBlank()) {
            cachedVisitorData = authVisitor
            return@withContext authVisitor
        }
        if (!forceRefresh && !cachedVisitorData.isNullOrBlank()) {
            return@withContext cachedVisitorData
        }

        visitorDataLock.withLock {
            if (!forceRefresh && !cachedVisitorData.isNullOrBlank()) {
                return@withLock cachedVisitorData
            }

            // Method 1: Lightweight POST to https://music.youtube.com/youtubei/v1/visitor_id
            try {
                val visitorUrl = "https://music.youtube.com/youtubei/v1/visitor_id?prettyPrint=false"
                val reqBody = JSONObject().apply {
                    put("context", JSONObject().apply {
                        put("client", JSONObject().apply {
                            put("clientName", "WEB_REMIX")
                            put("clientVersion", InnerTubeClients.WEB_REMIX.clientVersion)
                            put("hl", "en")
                            put("gl", "US")
                        })
                    })
                }
                val req = Request.Builder()
                    .url(visitorUrl)
                    .post(reqBody.toString().toRequestBody(jsonMediaType))
                    .header("User-Agent", FloWaveConstants.USER_AGENT_DESKTOP)
                    .header("Content-Type", "application/json")
                    .header("Origin", "https://music.youtube.com")
                    .build()

                client.newCall(req).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        if (body.isNotBlank()) {
                            val json = JSONObject(body)
                            val vd = json.optJSONObject("responseContext")?.optString("visitorData")
                            if (!vd.isNullOrBlank()) {
                                cachedVisitorData = vd
                                logger?.debug("online", "visitor_data_acquired", context = mapOf("source" to "visitor_id_endpoint"))
                                return@withLock vd
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logger?.warn("online", "visitor_data_fetch_failed", context = mapOf("endpoint" to "visitor_id"), throwable = e)
            }

            // Method 2: ArchiveTune / Metrolist sw.js_data extraction (matches Cg... visitorData token in JSON array)
            try {
                val swUrl = "https://www.youtube.com/sw.js_data"
                val swReq = Request.Builder()
                    .url(swUrl)
                    .header("User-Agent", FloWaveConstants.USER_AGENT_DESKTOP)
                    .build()
                client.newCall(swReq).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        val vd = Regex("\"visitorData\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1)
                            ?: Regex("\"(Cg[a-zA-Z0-9_%-]{10,})\"").find(body)?.groupValues?.getOrNull(1)
                        if (!vd.isNullOrBlank()) {
                            cachedVisitorData = vd
                            logger?.debug("online", "visitor_data_acquired", context = mapOf("source" to "sw_js_data"))
                            return@withLock vd
                        }
                    }
                }
            } catch (e: Exception) {
                logger?.warn("online", "visitor_data_fetch_failed", context = mapOf("endpoint" to "sw_js_data"), throwable = e)
            }

            cachedVisitorData
        }
    }

    open suspend fun getStreamResolution(videoId: String, forceRefresh: Boolean = false): ResolvedStreamSource {
        logger?.info("online", "stream_resolution_started", context = mapOf("videoId" to videoId, "forceRefresh" to forceRefresh))
        val lock = streamResolutionLocks.computeIfAbsent(videoId) { Mutex() }
        return try {
            lock.withLock { getStreamResolutionInternal(videoId, forceRefresh) }
                .also {
                    logger?.info("online", "stream_resolved", context = mapOf(
                        "videoId" to videoId,
                        "resolverSuccess" to true,
                        "resolver" to it.resolver,
                        "candidateKey" to it.candidateKey.orEmpty(),
                        "urlHost" to (runCatching { java.net.URI(it.url).host }.getOrNull().orEmpty())
                    ))
                }
        } catch (error: CancellationException) {
            logger?.debug("online", "stream_resolution_cancelled", context = mapOf("videoId" to videoId, "reason" to "resolver_cancelled"))
            throw error
        } catch (error: InterruptedException) {
            logger?.debug("online", "stream_resolution_cancelled", context = mapOf("videoId" to videoId, "reason" to "resolver_interrupted"))
            throw error
        } catch (error: Exception) {
            logger?.error("online", "stream_resolution_failed", error.message.orEmpty(), mapOf(
                "videoId" to videoId,
                "failureClass" to OnlinePlaybackPolicy.classifyResolverFailure(error)
            ), error)
            throw error
        }
    }

    fun markPlayableStream(videoId: String, latencyMs: Long) {
        logger?.info("online", "resolver_promoted_after_media_open", context = mapOf(
            "videoId" to videoId,
            "latencyMs" to latencyMs
        ))
    }

    fun markUnplayableStream(videoId: String, failureClass: String = "media_open_failure") {
        invalidateStreamUrl(videoId)
        logger?.warn("online", "stream_marked_unplayable", context = mapOf(
            "videoId" to videoId,
            "failureClass" to failureClass
        ))
    }

    /** Parses a duration string (e.g., "3:45", "1:02:30", or "45") into milliseconds. */
    fun parseDurationText(text: String): Long {
        val parts = text.split(":")
        var seconds = 0L
        try {
            if (parts.size == 1) {
                seconds = parts[0].toLongOrNull() ?: 0L
            } else if (parts.size == 2) {
                val minutes = parts[0].toLongOrNull() ?: 0L
                val secs = parts[1].toLongOrNull() ?: 0L
                seconds = minutes * 60 + secs
            } else if (parts.size == 3) {
                val hours = parts[0].toLongOrNull() ?: 0L
                val minutes = parts[1].toLongOrNull() ?: 0L
                val secs = parts[2].toLongOrNull() ?: 0L
                seconds = hours * 3600 + minutes * 60 + secs
            }
        } catch (_: Exception) {
        }
        return if (seconds > 0L) seconds * 1000L else 210000L
    }

    /** Build a stable online media model without resolving an expiring URL. */
    fun createOnlineTrack(track: InnerTubeTrack): Track {
        val durationMs = getCachedDuration(track.id).takeIf { it > 0L }
            ?: parseDurationText(track.durationText)
        return Track(
            id = "yt_${track.id}",
            title = track.title,
            artist = track.artist,
            album = track.album.ifBlank { "Online Stream" },
            durationMs = durationMs,
            mediaUri = "flowave://youtube/${track.id}",
            artworkUri = track.thumbnailUrl,
            isOnline = true,
            source = "YOUTUBE",
            sourceId = track.id
        )
    }

    private suspend fun executeWithRetry(request: Request, maxRetries: Int = 3): Response = withContext(Dispatchers.IO) {
        var lastException: IOException? = null
        var delayMs = 800L
        for (attempt in 1..maxRetries) {
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    return@withContext response
                }

                val code = response.code
                if (code == 400 || code == 401 || code == 403 || code == 404) {
                    return@withContext response
                }

                response.close()
                if (attempt < maxRetries) {
                    delay(delayMs)
                    delayMs *= 2
                }
            } catch (e: SocketTimeoutException) {
                lastException = e
                if (attempt < maxRetries) {
                    delay(delayMs)
                    delayMs *= 2
                }
            } catch (e: IOException) {
                lastException = e
                if (attempt < maxRetries) {
                    delay(delayMs)
                    delayMs *= 2
                }
            }
        }
        throw lastException ?: IOException("Request execution failed after $maxRetries attempts")
    }

    /**
     * Checks whether a cached stream entry is expired.
     * Uses server-synchronized time (`YtMusicAuthManager.currentServerMillis()`) and never
     * falsely evicts a stream cached within the last 25 minutes when the device clock is skewed.
     */
    private fun isCachedEntryExpired(resolvedAtMs: Long, url: String): Boolean {
        val ageMs = System.currentTimeMillis() - resolvedAtMs
        if (ageMs >= FloWaveConstants.STREAM_CACHE_DURATION_MS) return true
        // YouTube googlevideo URLs are valid for 6 hours (360 minutes). If resolved < 25 minutes ago,
        // it is guaranteed fresh even if the device/emulator clock is set to a future date.
        if (ageMs in 0L..(25 * 60 * 1000L)) return false
        return OnlinePlaybackPolicy.isExpired(url, nowMs = YtMusicAuthManager.currentServerMillis())
    }

    /**
     * Lightweight Range probe (`bytes=0-0`) to verify that googlevideo.com accepts the stream URL
     * and returns HTTP 200/206 with playable audio content before handing it to ExoPlayer.
     */
    private fun probeStreamPlayable(
        url: String,
        userAgent: String,
        headers: Map<String, String>
    ): Boolean {
        if (url.endsWith(".m3u8") || url.contains(".m3u8?")) return true
        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
        if (host == "localhost" || host == "127.0.0.1" || host.endsWith(".example.com") || host == "example.com" || host.endsWith(".test")) {
            return true
        }
        return try {
            val reqBuilder = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", userAgent)
                .header("Range", "bytes=0-0")
            headers.forEach { (k, v) ->
                if (!k.equals("Range", ignoreCase = true)) {
                    reqBuilder.header(k, v)
                }
            }
            probeClient.newCall(reqBuilder.build()).execute().use { resp ->
                val code = resp.code
                val contentType = resp.header("Content-Type")
                val ok = (code in 200..299) && OnlinePlaybackPolicy.isPlayableResponseContentType(contentType)
                if (!ok) {
                    logger?.warn("online", "stream_probe_rejected", context = mapOf(
                        "httpCode" to code,
                        "contentType" to contentType.orEmpty()
                    ))
                }
                ok
            }
        } catch (e: Exception) {
            logger?.warn("online", "stream_probe_error", context = mapOf("error" to e.message.orEmpty()))
            false
        }
    }

    suspend fun searchTracks(query: String): List<InnerTubeTrack> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val tracks = mutableListOf<InnerTubeTrack>()

        // ENGINE 1: YouTube Music InnerTube Search (WEB_REMIX client with Songs filter)
        try {
            val contextJson = JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", InnerTubeClients.WEB_REMIX.clientName)
                    put("clientVersion", InnerTubeClients.WEB_REMIX.clientVersion)
                    put("hl", "en")
                    put("gl", "US")
                })
            }

            val reqBuilder = Request.Builder()
                .url(FloWaveConstants.INNERTUBE_SEARCH_URL)
                .header("User-Agent", InnerTubeClients.WEB_REMIX.userAgent)
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .header("X-YouTube-Client-Name", "67")
                .header("X-YouTube-Client-Version", InnerTubeClients.WEB_REMIX.clientVersion)

            applyAuthHeaders(reqBuilder, contextJson, origin = "https://music.youtube.com")

            val requestBodyJson = JSONObject().apply {
                put("context", contextJson)
                put("query", query)
                put("params", "EgWKAQIIAWoKEAkQBRAKEAMQBBAK") // Songs filter
            }
            reqBuilder.post(requestBodyJson.toString().toRequestBody(jsonMediaType))

            executeWithRetry(reqBuilder.build(), maxRetries = 2).use { response ->
                val bodyString = response.body?.string() ?: ""
                if (response.isSuccessful && bodyString.isNotEmpty()) {
                    val json = JSONObject(bodyString)
                    json.optJSONObject("responseContext")?.optString("visitorData")?.takeIf { it.isNotBlank() }?.let {
                        cachedVisitorData = it
                    }
                    val parsed = InnerTubeParser.parseInnerTubeSearchJson(json)
                    tracks.addAll(parsed)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("InnerTubeRepository", "InnerTube music search notice: ${e.message}", e)
        }

        if (tracks.isNotEmpty()) return@withContext tracks

        // ENGINE 2: Unfiltered WEB_REMIX search (includes videos/remixes/live performances)
        try {
            val contextJson = JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", InnerTubeClients.WEB_REMIX.clientName)
                    put("clientVersion", InnerTubeClients.WEB_REMIX.clientVersion)
                    put("hl", "en")
                    put("gl", "US")
                })
            }
            val reqBuilder = Request.Builder()
                .url(FloWaveConstants.INNERTUBE_SEARCH_URL)
                .header("User-Agent", InnerTubeClients.WEB_REMIX.userAgent)
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .header("X-YouTube-Client-Name", "67")
                .header("X-YouTube-Client-Version", InnerTubeClients.WEB_REMIX.clientVersion)

            applyAuthHeaders(reqBuilder, contextJson, origin = "https://music.youtube.com")

            val requestBodyJson = JSONObject().apply {
                put("context", contextJson)
                put("query", query)
            }
            reqBuilder.post(requestBodyJson.toString().toRequestBody(jsonMediaType))

            executeWithRetry(reqBuilder.build(), maxRetries = 1).use { response ->
                val bodyString = response.body?.string() ?: ""
                if (response.isSuccessful && bodyString.isNotEmpty()) {
                    val json = JSONObject(bodyString)
                    val parsed = InnerTubeParser.parseInnerTubeSearchJson(json)
                    tracks.addAll(parsed)
                }
            }
        } catch (_: Exception) {
        }

        if (tracks.isNotEmpty()) return@withContext tracks

        // ENGINE 3: YouTube HTML Scraping Fallback (ytInitialData)
        try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val searchUrl = "https://www.youtube.com/results?search_query=$encodedQuery&sp=EgIQAQ%253D%253D"
            val request = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", FloWaveConstants.USER_AGENT_DESKTOP)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            executeWithRetry(request, maxRetries = 2).use { response ->
                val html = response.body?.string() ?: ""
                if (html.isNotEmpty()) {
                    extractAndCacheKeys(html)
                }
                if (response.isSuccessful && html.isNotEmpty()) {
                    val jsonString = extractJsonFromHtml(html)
                    if (jsonString != null) {
                        val json = JSONObject(jsonString)
                        val scrapedTracks = InnerTubeParser.parseYtInitialData(json)
                        if (scrapedTracks.isNotEmpty()) {
                            tracks.addAll(scrapedTracks)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("InnerTubeRepository", "YouTube Scraper failed: ${e.message}", e)
        }

        tracks
    }

    /**
     * Fetches related radio / Automix tracks for continuous playback when a user plays an online song.
     */
    suspend fun fetchRadioQueue(videoId: String, playlistId: String? = null): List<InnerTubeTrack> = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext emptyList()
        try {
            val contextJson = JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", InnerTubeClients.WEB_REMIX.clientName)
                    put("clientVersion", InnerTubeClients.WEB_REMIX.clientVersion)
                    put("hl", "en")
                    put("gl", "US")
                })
            }
            val reqBuilder = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/next?prettyPrint=false")
                .header("Content-Type", "application/json")
                .header("User-Agent", InnerTubeClients.WEB_REMIX.userAgent)
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .header("X-YouTube-Client-Name", "67")
                .header("X-YouTube-Client-Version", InnerTubeClients.WEB_REMIX.clientVersion)

            applyAuthHeaders(reqBuilder, contextJson, origin = "https://music.youtube.com")

            val bodyJson = JSONObject().apply {
                put("context", contextJson)
                put("videoId", videoId)
                put("playlistId", playlistId ?: "RDAMVM$videoId")
                put("isAudioOnly", true)
                put("enablePersistentPlaylistPanel", true)
            }
            reqBuilder.post(bodyJson.toString().toRequestBody(jsonMediaType))

            executeWithRetry(reqBuilder.build(), maxRetries = 1).use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && body.isNotEmpty()) {
                    val json = JSONObject(body)
                    val parsed = InnerTubeParser.parseRadioNextResponse(json)
                        .filter { it.id != videoId }
                    if (parsed.isNotEmpty()) {
                        return@withContext parsed
                    }
                }
            }
        } catch (e: Exception) {
            logger?.warn("online", "fetch_radio_queue_failed", context = mapOf("videoId" to videoId), throwable = e)
        }
        emptyList()
    }

    suspend fun getTrackMetadata(videoId: String): InnerTubeTrack? = withContext(Dispatchers.IO) {
        for (clientConfig in extractionPriorityChain) {
            try {
                val clientJson = JSONObject().apply {
                    put("clientName", clientConfig.clientName)
                    put("clientVersion", clientConfig.clientVersion)
                    clientConfig.deviceMake?.let { put("deviceMake", it) }
                    clientConfig.deviceModel?.let { put("deviceModel", it) }
                    clientConfig.osName?.let { put("osName", it) }
                    clientConfig.osVersion?.let { put("osVersion", it) }
                    clientConfig.androidSdkVersion?.let { put("androidSdkVersion", it) }
                    put("hl", "en")
                    put("gl", "US")
                }
                val contextJson = JSONObject().apply {
                    put("client", clientJson)
                }
                val requestBodyJson = JSONObject().apply {
                    put("context", contextJson)
                    put("videoId", videoId)
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                }

                val isRemix = clientConfig.clientName == "WEB_REMIX"
                val playerUrl = if (isRemix) {
                    "https://music.youtube.com/youtubei/v1/player?prettyPrint=false"
                } else {
                    "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
                }

                val reqBuilder = Request.Builder()
                    .url(playerUrl)
                    .post(requestBodyJson.toString().toRequestBody(jsonMediaType))
                    .header("User-Agent", clientConfig.userAgent)
                    .header("Content-Type", "application/json")

                clientConfig.clientNumber?.let { reqBuilder.header("X-YouTube-Client-Name", it) }
                reqBuilder.header("X-YouTube-Client-Version", clientConfig.clientVersion)
                if (clientConfig.clientName == "ANDROID_VR") {
                    reqBuilder.header("Origin", "https://www.youtube.com")
                }

                executeWithRetry(reqBuilder.build(), maxRetries = 1).use { response ->
                    val bodyString = response.body?.string() ?: ""
                    if (response.isSuccessful && bodyString.isNotEmpty()) {
                        val json = JSONObject(bodyString)
                        val videoDetails = json.optJSONObject("videoDetails")
                        if (videoDetails != null) {
                            val title = videoDetails.optString("title", "Unknown Title")
                            val artist = videoDetails.optString("author", "Unknown Artist")
                            val lengthSeconds = videoDetails.optString("lengthSeconds", "0").toLongOrNull() ?: 0L

                            val durationMinutes = lengthSeconds / 60
                            val durationRemainingSeconds = lengthSeconds % 60
                            val durationText = String.format("%d:%02d", durationMinutes, durationRemainingSeconds)

                            var thumbUrl = ""
                            val thumbnailObj = videoDetails.optJSONObject("thumbnail")
                            if (thumbnailObj != null) {
                                val thumbnails = thumbnailObj.optJSONArray("thumbnails")
                                if (thumbnails != null && thumbnails.length() > 0) {
                                    thumbUrl = thumbnails.optJSONObject(thumbnails.length() - 1).optString("url", "")
                                }
                            }
                            if (thumbUrl.isEmpty()) {
                                thumbUrl = "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"
                            }

                            return@withContext InnerTubeTrack(
                                id = videoId,
                                title = title,
                                artist = artist,
                                durationText = durationText,
                                thumbnailUrl = thumbUrl,
                                album = "YouTube Music"
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("InnerTubeRepository", "Failed to fetch metadata with client ${clientConfig.clientName}: ${e.message}")
            }
        }
        null
    }

    private fun cleanExpiredStreamCache() {
        val now = System.currentTimeMillis()
        val urlIter = streamUrlCache.entries.iterator()
        while (urlIter.hasNext()) {
            val entry = urlIter.next()
            if (now - entry.value.first > FloWaveConstants.STREAM_CACHE_DURATION_MS) {
                urlIter.remove()
            }
        }
        val resIter = streamResolutionCache.entries.iterator()
        while (resIter.hasNext()) {
            val entry = resIter.next()
            if (now - entry.value.first > FloWaveConstants.STREAM_CACHE_DURATION_MS) {
                resIter.remove()
            }
        }
    }

    suspend fun getStreamUrl(videoId: String, forceRefresh: Boolean = false): String {
        return getStreamResolution(videoId, forceRefresh).url
    }

    private suspend fun getStreamResolutionInternal(
        videoId: String,
        forceRefresh: Boolean = false
    ): ResolvedStreamSource = withContext(Dispatchers.IO) {
        cleanExpiredStreamCache()
        if (forceRefresh) {
            streamUrlCache.remove(videoId)
            streamResolutionCache.remove(videoId)
        }

        // Check cache first
        val cached = streamResolutionCache[videoId]
        if (cached != null) {
            if (!isCachedEntryExpired(cached.first, cached.second.url)) {
                return@withContext cached.second
            } else {
                streamResolutionCache.remove(videoId)
                streamUrlCache.remove(videoId)
            }
        }

        if (authManager?.connection?.value?.isConnected != true) {
            authManager?.tryConnectFromCookieManager()
        }

        var visitorData = getVisitorData(forceRefresh = forceRefresh)
        var needFreshVisitorData = false
        val isSignedIn = authManager?.connection?.value?.isConnected == true
        val hlsFallbacks = mutableListOf<ResolvedStreamSource>()

        for ((attempt, clientConfig) in extractionPriorityChain.withIndex()) {
            val isHtml5Client = clientConfig.clientName == "TVHTML5_SIMPLY_EMBEDDED_PLAYER" ||
                clientConfig.clientName == "WEB_REMIX"

            val authModes = if (isSignedIn) {
                if (isHtml5Client) {
                    listOf(true, false)
                } else {
                    listOf(false, true)
                }
            } else {
                listOf(false)
            }

            for (useAuth in authModes) {
                try {
                    if (needFreshVisitorData) {
                        visitorData = getVisitorData(forceRefresh = true)
                        needFreshVisitorData = false
                    }

                    val clientJson = JSONObject().apply {
                        put("clientName", clientConfig.clientName)
                        put("clientVersion", clientConfig.clientVersion)
                        clientConfig.deviceMake?.let { put("deviceMake", it) }
                        clientConfig.deviceModel?.let { put("deviceModel", it) }
                        clientConfig.osName?.let { put("osName", it) }
                        clientConfig.osVersion?.let { put("osVersion", it) }
                        clientConfig.androidSdkVersion?.let { put("androidSdkVersion", it) }
                        put("hl", clientConfig.hl)
                        put("gl", clientConfig.gl)
                        if (!visitorData.isNullOrBlank()) {
                            put("visitorData", visitorData)
                        }
                    }

                    val contextJson = JSONObject().apply {
                        put("client", clientJson)
                        if (clientConfig.clientName == "TVHTML5_SIMPLY_EMBEDDED_PLAYER") {
                            put("thirdParty", JSONObject().apply {
                                put("embedUrl", "https://www.youtube.com/")
                            })
                        }
                    }

                    val isRemix = clientConfig.clientName == "WEB_REMIX"
                    val originUrl = if (isRemix) "https://music.youtube.com" else "https://www.youtube.com"
                    val playerUrl = if (isRemix) {
                        "https://music.youtube.com/youtubei/v1/player?prettyPrint=false"
                    } else {
                        "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
                    }

                    val reqBuilder = Request.Builder()
                        .url(playerUrl)
                        .header("User-Agent", clientConfig.userAgent)
                        .header("Content-Type", "application/json")
                        .header("Origin", originUrl)
                        .header("Referer", "$originUrl/")

                    clientConfig.clientNumber?.let { reqBuilder.header("X-YouTube-Client-Name", it) }
                    reqBuilder.header("X-YouTube-Client-Version", clientConfig.clientVersion)
                    if (!visitorData.isNullOrBlank()) {
                        reqBuilder.header("X-Goog-Visitor-Id", visitorData)
                    }

                    if (useAuth) {
                        applyAuthHeaders(reqBuilder, contextJson, origin = originUrl, visitorDataOverride = visitorData)
                    }

                    val charset = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
                    val cpn = (1..16).map { charset.random() }.joinToString("")

                    val requestBodyJson = JSONObject().apply {
                        put("context", contextJson)
                        put("cpn", cpn)
                        put("videoId", videoId)
                        put("contentCheckOk", true)
                        put("racyCheckOk", true)
                        if (isHtml5Client) {
                            val sts = YouTubeDecipherer.ensureTimestampLoaded(client, videoId)
                            put("playbackContext", JSONObject().apply {
                                put("contentPlaybackContext", JSONObject().apply {
                                    put("html5Preference", "HTML5_PREF_WANTS")
                                    put("signatureTimestamp", sts)
                                })
                            })
                        }
                        if (clientConfig.clientName == "TVHTML5_SIMPLY_EMBEDDED_PLAYER") {
                            put("thirdParty", JSONObject().apply {
                                put("embedUrl", "https://www.youtube.com/")
                            })
                        }
                    }

                    reqBuilder.post(requestBodyJson.toString().toRequestBody(jsonMediaType))

                    executeWithRetry(reqBuilder.build(), maxRetries = 1).use { response ->
                        val bodyString = response.body?.string() ?: ""
                        if (response.isSuccessful && bodyString.isNotEmpty()) {
                            val json = JSONObject(bodyString)

                            json.optJSONObject("responseContext")?.optString("visitorData")?.takeIf { it.isNotBlank() }?.let {
                                cachedVisitorData = it
                            }

                            val videoDetails = json.optJSONObject("videoDetails")
                            val lengthSecondsStr = videoDetails?.optString("lengthSeconds")
                            val durationSec = lengthSecondsStr?.toLongOrNull()
                            if (durationSec != null && durationSec > 0L) {
                                streamDurationCache[videoId] = durationSec * 1000L
                            }

                            val playabilityStatus = json.optJSONObject("playabilityStatus")
                            val status = playabilityStatus?.optString("status")
                            if (status != null && status != "OK") {
                                logger?.warn("online", "inner_tube_not_playable", context = mapOf(
                                    "videoId" to videoId,
                                    "client" to clientConfig.clientName,
                                    "authenticated" to useAuth,
                                    "status" to status,
                                    "reason" to (playabilityStatus.optString("reason").take(200))
                                ))
                                if (status == "LOGIN_REQUIRED" && !useAuth) {
                                    needFreshVisitorData = true
                                    cachedVisitorData = null
                                }
                            } else {
                                val requestHeaders = mutableMapOf(
                                    "User-Agent" to clientConfig.userAgent,
                                    "Origin" to originUrl,
                                    "Referer" to "$originUrl/"
                                )

                                // Primary check: require direct progressive audio URL (allowHlsFallback = false)
                                val directUrl = InnerTubeParser.parsePlayerResponse(
                                    json = json,
                                    okHttpClient = client,
                                    allowHlsFallback = false,
                                    videoId = videoId
                                )
                                if (!directUrl.isNullOrBlank()) {
                                    // Probe every candidate URL (both native and HTML5 clients) with Range: bytes=0-0
                                    // so any 403/throttled URL is immediately skipped in favor of the next client.
                                    val isPlayable = probeStreamPlayable(directUrl, clientConfig.userAgent, requestHeaders)
                                    if (isPlayable) {
                                        logger?.info("online", "inner_tube_client_succeeded", context = mapOf(
                                            "videoId" to videoId,
                                            "client" to clientConfig.clientName,
                                            "authenticated" to useAuth
                                        ))
                                        val resolvedSource = ResolvedStreamSource(
                                            url = directUrl,
                                            resolver = if (isRemix) "inner_tube_remix" else "inner_tube",
                                            candidateKey = clientConfig.clientName,
                                            userAgent = clientConfig.userAgent,
                                            requestHeaders = requestHeaders
                                        )
                                        val now = System.currentTimeMillis()
                                        streamResolutionCache[videoId] = Pair(now, resolvedSource)
                                        streamUrlCache[videoId] = Pair(now, directUrl)
                                        return@withContext resolvedSource
                                    }
                                } else {
                                    // Record HLS manifest only as a last-resort fallback after all direct clients are tried
                                    val hlsUrl = InnerTubeParser.parsePlayerResponse(
                                        json = json,
                                        okHttpClient = client,
                                        allowHlsFallback = true,
                                        videoId = videoId
                                    )
                                    if (!hlsUrl.isNullOrBlank()) {
                                        hlsFallbacks.add(
                                            ResolvedStreamSource(
                                                url = hlsUrl,
                                                resolver = "inner_tube_hls",
                                                candidateKey = clientConfig.clientName,
                                                userAgent = clientConfig.userAgent,
                                                requestHeaders = requestHeaders
                                            )
                                        )
                                    } else {
                                        logger?.warn("online", "inner_tube_formats_url_less_or_sabr", context = mapOf(
                                            "videoId" to videoId,
                                            "client" to clientConfig.clientName
                                        ))
                                    }
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                } catch (e: Exception) {
                    logger?.warn("online", "resolver_attempt_failed", context = mapOf(
                        "videoId" to videoId,
                        "resolver" to "inner_tube",
                        "attempt" to attempt + 1,
                        "client" to clientConfig.clientName,
                        "failureClass" to OnlinePlaybackPolicy.classifyResolverFailure(e)
                    ), throwable = e)
                }
            }
        }

        // 2. Native yt-dlp binary fallback (if bundled binary is available on device)
        if (appContext != null) {
            try {
                val ytDlpUrl = withTimeoutOrNull(8_000L) {
                    SealStyleDownloadEngine(appContext).resolveAudioUrl(
                        url = "https://www.youtube.com/watch?v=$videoId",
                        allowFallbackToNative = false
                    ).getOrNull()
                }
                if (!ytDlpUrl.isNullOrBlank()) {
                    val resolvedSource = ResolvedStreamSource(
                        url = ytDlpUrl,
                        resolver = "yt_dlp",
                        candidateKey = "YT_DLP",
                        userAgent = InnerTubeClients.ANDROID_VR.userAgent,
                        requestHeaders = mapOf("User-Agent" to InnerTubeClients.ANDROID_VR.userAgent)
                    )
                    val now = System.currentTimeMillis()
                    streamResolutionCache[videoId] = Pair(now, resolvedSource)
                    streamUrlCache[videoId] = Pair(now, ytDlpUrl)
                    return@withContext resolvedSource
                }
            } catch (e: Exception) {
                logger?.warn("online", "yt_dlp_stream_fallback_failed", context = mapOf("videoId" to videoId), throwable = e)
            }
        }

        // 3. HLS manifest fallback if no direct progressive stream was available
        hlsFallbacks.firstOrNull()?.let { hlsSource ->
            val now = System.currentTimeMillis()
            streamResolutionCache[videoId] = Pair(now, hlsSource)
            streamUrlCache[videoId] = Pair(now, hlsSource.url)
            return@withContext hlsSource
        }

        throw IOException("All extraction attempts and fallback clients were exhausted for videoId: $videoId")
    }

    suspend fun parseAudioUrl(formats: JSONArray): String? {
        return InnerTubeParser.parseAudioUrl(formats, client)
    }

    suspend fun fetchLrcLyrics(trackTitle: String, artistName: String): List<LrcLine> = withContext(Dispatchers.IO) {
        val lrcLines = mutableListOf<LrcLine>()
        try {
            val cleanTitleRaw = trackTitle.replace(Regex("(?i)\\(.*\\)|\\[.*\\]|official video|lyric video|audio|remix|hd|4k"), "").trim()
            val cleanArtistRaw = artistName.replace(Regex("(?i)vevo|official|music|topic|records"), "").trim()

            val cleanTitle = java.net.URLEncoder.encode(cleanTitleRaw, "UTF-8")
            val cleanArtist = java.net.URLEncoder.encode(cleanArtistRaw, "UTF-8")

            // 1. Direct GET endpoint
            val getUrl = "https://lrclib.net/api/get?artist_name=$cleanArtist&track_name=$cleanTitle"
            val request = Request.Builder().url(getUrl).header("User-Agent", FloWaveConstants.USER_AGENT_FLOWAVE_APP).build()
            executeWithRetry(request, maxRetries = 2).use { response ->
                val bodyString = response.body?.string() ?: ""
                if (response.isSuccessful && bodyString.isNotEmpty()) {
                    val json = JSONObject(bodyString)
                    val syncedLyrics = json.optString("syncedLyrics")
                    if (syncedLyrics.isNotEmpty()) {
                        lrcLines.addAll(parseLrc(syncedLyrics))
                    }
                }
            }

            // 2. Search endpoint fallback if direct get returned empty
            if (lrcLines.isEmpty()) {
                val query = java.net.URLEncoder.encode("$cleanTitleRaw $cleanArtistRaw", "UTF-8")
                val searchUrl = "https://lrclib.net/api/search?q=$query"
                val searchRequest = Request.Builder().url(searchUrl).header("User-Agent", FloWaveConstants.USER_AGENT_FLOWAVE_APP).build()
                executeWithRetry(searchRequest, maxRetries = 2).use { searchResponse ->
                    val searchBody = searchResponse.body?.string() ?: ""
                    if (searchResponse.isSuccessful && searchBody.isNotEmpty()) {
                        val array = JSONArray(searchBody)
                        if (array.length() > 0) {
                            for (i in 0 until array.length()) {
                                val item = array.optJSONObject(i) ?: continue
                                val synced = item.optString("syncedLyrics")
                                if (synced.isNotEmpty()) {
                                    lrcLines.addAll(parseLrc(synced))
                                    break
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("InnerTubeRepository", "LRCLIB lyrics fetch notice: ${e.message}", e)
        }

        lrcLines
    }

    fun parseLrc(lrcContent: String): List<LrcLine> {
        val lines = mutableListOf<LrcLine>()
        val regex = Regex("\\[(\\d+):(\\d+\\.\\d+)\\](.*)")
        lrcContent.lines().forEach { line ->
            val match = regex.find(line)
            if (match != null) {
                val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                val seconds = match.groupValues[2].toDoubleOrNull() ?: 0.0
                val text = match.groupValues[3].trim()
                val totalMs = (minutes * 60 * 1000) + (seconds * 1000).toLong()
                lines.add(LrcLine(timestampMs = totalMs, text = text))
            }
        }
        return lines.sortedBy { it.timestampMs }
    }

    fun parseLocalLrc(file: java.io.File): List<LrcLine> {
        return if (file.exists() && file.isFile) {
            try {
                parseLrc(file.readText())
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    private fun extractJsonFromHtml(html: String): String? {
        val prefixes = listOf(
            "var ytInitialData = ",
            "window['ytInitialData'] = ",
            "window[\"ytInitialData\"] = ",
            "ytInitialData = "
        )
        var startIndex = -1
        var selectedPrefix = ""
        for (prefix in prefixes) {
            startIndex = html.indexOf(prefix)
            if (startIndex != -1) {
                selectedPrefix = prefix
                break
            }
        }

        if (startIndex == -1) return null

        val jsonStart = html.indexOf("{", startIndex + selectedPrefix.length)
        if (jsonStart == -1) return null

        var braceCount = 0
        var inString = false
        var escape = false
        for (i in jsonStart until html.length) {
            val c = html[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\') {
                escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '{') {
                    braceCount++
                } else if (c == '}') {
                    braceCount--
                    if (braceCount == 0) {
                        return html.substring(jsonStart, i + 1)
                    }
                }
            }
        }
        return null
    }

    suspend fun getTrendingTracks(): List<InnerTubeTrack> {
        return try {
            val tracks = searchTracks("Top Global Hits Music 2026")
            if (tracks.isNotEmpty()) {
                tracks.take(20)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.e("InnerTubeRepository", "Failed to fetch trending tracks", e)
            emptyList()
        }
    }

    private fun extractAndCacheKeys(html: String) {
        try {
            val apiKeyRegex = Regex("(?i)\"INNERTUBE_API_KEY\"\\s*:\\s*\"([^\"]+)\"")
            val apiKeyMatch = apiKeyRegex.find(html)
            val apiKey = apiKeyMatch?.groupValues?.get(1)

            val clientVersionRegex = Regex("(?i)\"(?:INNERTUBE_CONTEXT_CLIENT_VERSION|clientVersion)\"\\s*:\\s*\"([^\"]+)\"")
            val clientVersionMatch = clientVersionRegex.find(html)
            val clientVersion = clientVersionMatch?.groupValues?.get(1)

            if (!apiKey.isNullOrBlank()) {
                scrapedApiKey = apiKey
            }
            if (!clientVersion.isNullOrBlank()) {
                scrapedClientVersion = clientVersion
            }

            if (!apiKey.isNullOrBlank() || !clientVersion.isNullOrBlank()) {
                lastScrapedTimeMs = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            android.util.Log.e("InnerTubeRepository", "Error extracting keys from HTML: ${e.message}")
        }
    }

    suspend fun ensureKeysUpdated() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (scrapedApiKey != null && scrapedClientVersion != null && (now - lastScrapedTimeMs) < 24 * 60 * 60 * 1000L) {
            return@withContext
        }

        try {
            val request = Request.Builder()
                .url("https://www.youtube.com/")
                .header("User-Agent", FloWaveConstants.USER_AGENT_DESKTOP)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            client.newCall(request).execute().use { response ->
                val html = response.body?.string() ?: ""
                if (response.isSuccessful && html.isNotEmpty()) {
                    extractAndCacheKeys(html)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("InnerTubeRepository", "Proactive key scraping failed: ${e.message}")
        }
    }

    /** Identity of the signed-in account (account_menu endpoint). */
    suspend fun fetchAccountInfo(): YtAccountInfo? = withContext(Dispatchers.IO) {
        val auth = authManager ?: return@withContext null
        if (!auth.connection.value.isConnected) {
            auth.tryConnectFromCookieManager()
        }
        if (!auth.connection.value.isConnected) return@withContext null

        // Ensure server clock offset is calibrated before computing SAPISIDHASH if not yet synced
        if (YtMusicAuthManager.serverTimeOffsetMs == 0L) {
            runCatching { getVisitorData() }
        }

        val url = "https://music.youtube.com/youtubei/v1/account/account_menu?prettyPrint=false"
        val contextJson = JSONObject().apply {
            put("client", JSONObject().apply {
                put("clientName", "WEB_REMIX")
                put("clientVersion", InnerTubeClients.WEB_REMIX.clientVersion)
                put("hl", "en")
                put("gl", "US")
            })
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("User-Agent", FloWaveConstants.USER_AGENT_DESKTOP)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", InnerTubeClients.WEB_REMIX.clientVersion)

        applyAuthHeaders(requestBuilder, contextJson, origin = "https://music.youtube.com")

        val bodyJson = JSONObject().apply {
            put("context", contextJson)
        }
        requestBuilder.post(bodyJson.toString().toRequestBody(jsonMediaType))

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    if ((response.code == 401 || response.code == 403) && auth.refreshCookiesFromCookieManager()) {
                        return@withContext fetchAccountInfo()
                    }
                    return@withContext null
                }
                val responseBody = response.body?.string() ?: return@withContext null
                val root = JSONObject(responseBody)
                root.optJSONObject("responseContext")?.optString("visitorData")?.takeIf { it.isNotBlank() }?.let {
                    cachedVisitorData = it
                }
                val header = findObjectRecursively(root, "activeAccountHeaderRenderer")
                    ?: findObjectRecursively(root, "accountItemRenderer")
                    ?: return@withContext null
                val accountName = InnerTubeParser.extractText(header, "accountName")?.trim().orEmpty()
                val handle = InnerTubeParser.extractText(header, "channelHandle")?.trim()
                    ?: InnerTubeParser.extractText(header, "email")?.trim()
                val thumbs = header.optJSONObject("accountPhoto")?.optJSONArray("thumbnails")
                    ?: header.optJSONObject("accountPhoto")?.optJSONObject("thumbnails")?.optJSONArray("thumbnails")
                val photoUrl = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url")
                if (accountName.isNotBlank()) {
                    YtAccountInfo(accountName, handle, photoUrl)
                } else null
            }
        } catch (e: Exception) {
            logger?.warn("online", "fetch_account_info_failed", throwable = e)
            null
        }
    }

    /** The account's library playlists (FEmusic_liked_playlists) with synthesized Liked Music head entry. */
    suspend fun fetchLibraryPlaylists(): List<YtPlaylistSummary> = withContext(Dispatchers.IO) {
        val auth = authManager ?: return@withContext emptyList()
        val results = mutableListOf<YtPlaylistSummary>()

        // Always provide Liked Music as primary head entry
        results.add(
            YtPlaylistSummary(
                id = "VLLM",
                title = "Liked Music",
                author = "YouTube Music",
                trackCountText = null,
                artworkUrl = "https://music.youtube.com/img/on_platform_logo.png"
            )
        )

        if (!auth.connection.value.isConnected) {
            auth.tryConnectFromCookieManager()
        }
        if (!auth.connection.value.isConnected) return@withContext results

        if (YtMusicAuthManager.serverTimeOffsetMs == 0L) {
            runCatching { getVisitorData() }
        }

        val url = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"
        val contextJson = JSONObject().apply {
            put("client", JSONObject().apply {
                put("clientName", "WEB_REMIX")
                put("clientVersion", InnerTubeClients.WEB_REMIX.clientVersion)
                put("hl", "en")
                put("gl", "US")
            })
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("User-Agent", FloWaveConstants.USER_AGENT_DESKTOP)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", InnerTubeClients.WEB_REMIX.clientVersion)

        applyAuthHeaders(requestBuilder, contextJson, origin = "https://music.youtube.com")

        val bodyJson = JSONObject().apply {
            put("context", contextJson)
            put("browseId", "FEmusic_liked_playlists")
        }
        requestBuilder.post(bodyJson.toString().toRequestBody(jsonMediaType))

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string().orEmpty()
                    if (responseBody.isNotEmpty()) {
                        val root = JSONObject(responseBody)
                        parsePlaylistsRecursively(root, results)
                    }
                } else if ((response.code == 401 || response.code == 403) && auth.refreshCookiesFromCookieManager()) {
                    return@withContext fetchLibraryPlaylists()
                }
            }
        } catch (e: Exception) {
            logger?.warn("online", "fetch_library_playlists_failed", throwable = e)
        }
        results.distinctBy { it.id }
    }

    /** Fetches tracks belonging to a YouTube Music playlist or album (browse endpoint). */
    suspend fun fetchPlaylistTracks(browseId: String): List<InnerTubeTrack> = withContext(Dispatchers.IO) {
        val auth = authManager
        if (auth != null && !auth.connection.value.isConnected) {
            auth.tryConnectFromCookieManager()
        }
        if (YtMusicAuthManager.serverTimeOffsetMs == 0L) {
            runCatching { getVisitorData() }
        }

        val url = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"
        val contextJson = JSONObject().apply {
            put("client", JSONObject().apply {
                put("clientName", "WEB_REMIX")
                put("clientVersion", InnerTubeClients.WEB_REMIX.clientVersion)
                put("hl", "en")
                put("gl", "US")
            })
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("User-Agent", FloWaveConstants.USER_AGENT_DESKTOP)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", InnerTubeClients.WEB_REMIX.clientVersion)

        applyAuthHeaders(requestBuilder, contextJson, origin = "https://music.youtube.com")

        val targetBrowseId = when {
            browseId == "LM" || browseId == "FEmusic_liked_videos" -> "VLLM"
            else -> browseId
        }
        val bodyJson = JSONObject().apply {
            put("context", contextJson)
            put("browseId", targetBrowseId)
        }
        requestBuilder.post(bodyJson.toString().toRequestBody(jsonMediaType))

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string().orEmpty()
                    if (responseBody.isNotEmpty()) {
                        val root = JSONObject(responseBody)
                        val tracks = parseTracksFromBrowse(root)
                        if (tracks.isNotEmpty()) return@withContext tracks
                    }
                } else if (auth != null && (response.code == 401 || response.code == 403) && auth.refreshCookiesFromCookieManager()) {
                    return@withContext fetchPlaylistTracks(browseId)
                }
            }
        } catch (e: Exception) {
            logger?.warn("online", "fetch_playlist_tracks_failed", context = mapOf("browseId" to browseId), throwable = e)
        }

        emptyList()
    }

    /** Fetches user's Liked Music tracks (VLLM endpoint). */
    suspend fun fetchLikedMusic(): List<InnerTubeTrack> = withContext(Dispatchers.IO) {
        fetchPlaylistTracks("VLLM")
    }

    /** Fetches online recommendations and personalized home feed (FEmusic_home). */
    suspend fun fetchHomeFeed(): List<InnerTubeTrack> = withContext(Dispatchers.IO) {
        if (authManager != null && !authManager.connection.value.isConnected) {
            authManager.tryConnectFromCookieManager()
        }
        if (YtMusicAuthManager.serverTimeOffsetMs == 0L) {
            runCatching { getVisitorData() }
        }

        val url = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"
        val contextJson = JSONObject().apply {
            put("client", JSONObject().apply {
                put("clientName", "WEB_REMIX")
                put("clientVersion", InnerTubeClients.WEB_REMIX.clientVersion)
                put("hl", "en")
                put("gl", "US")
            })
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("User-Agent", FloWaveConstants.USER_AGENT_DESKTOP)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", InnerTubeClients.WEB_REMIX.clientVersion)

        applyAuthHeaders(requestBuilder, contextJson, origin = "https://music.youtube.com")

        val bodyJson = JSONObject().apply {
            put("context", contextJson)
            put("browseId", "FEmusic_home")
        }
        requestBuilder.post(bodyJson.toString().toRequestBody(jsonMediaType))

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string().orEmpty()
                    if (responseBody.isNotEmpty()) {
                        val root = JSONObject(responseBody)
                        val tracks = parseTracksFromBrowse(root)
                        if (tracks.isNotEmpty()) return@withContext tracks
                    }
                }
            }
        } catch (e: Exception) {
            logger?.warn("online", "fetch_home_feed_failed", throwable = e)
        }

        // Fallback to trending search
        getTrendingTracks()
    }

    private fun findObjectRecursively(json: Any, targetKey: String): JSONObject? {
        when (json) {
            is JSONObject -> {
                if (json.has(targetKey)) {
                    val found = json.optJSONObject(targetKey)
                    if (found != null) return found
                }
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val candidate = findObjectRecursively(json.get(key), targetKey)
                    if (candidate != null) return candidate
                }
            }
            is JSONArray -> {
                for (i in 0 until json.length()) {
                    val item = json.opt(i)
                    if (item != null) {
                        val candidate = findObjectRecursively(item, targetKey)
                        if (candidate != null) return candidate
                    }
                }
            }
        }
        return null
    }

    private fun collectObjectsRecursively(json: Any, targetKey: String, results: MutableList<JSONObject>) {
        when (json) {
            is JSONObject -> {
                if (json.has(targetKey)) {
                    json.optJSONObject(targetKey)?.let { results.add(it) }
                }
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    collectObjectsRecursively(json.get(key), targetKey, results)
                }
            }
            is JSONArray -> {
                for (i in 0 until json.length()) {
                    val item = json.opt(i)
                    if (item != null) {
                        collectObjectsRecursively(item, targetKey, results)
                    }
                }
            }
        }
    }

    private fun parsePlaylistsRecursively(json: Any, results: MutableList<YtPlaylistSummary>) {
        val items = mutableListOf<JSONObject>()
        collectObjectsRecursively(json, "musicTwoRowItemRenderer", items)
        collectObjectsRecursively(json, "musicResponsiveListItemRenderer", items)
        for (item in items) {
            val nav = item.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                ?: item.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
            val browseId = nav?.optString("browseId")?.takeIf { it.isNotBlank() } ?: continue
            val title = InnerTubeParser.extractText(item, "title") ?: continue
            val author = InnerTubeParser.extractText(item, "subtitle")
            val thumb = InnerTubeParser.extractThumbnail(item)
            results.add(
                YtPlaylistSummary(
                    id = browseId,
                    title = title,
                    author = author,
                    trackCountText = null,
                    artworkUrl = thumb
                )
            )
        }
    }

    private fun parseTracksFromBrowse(root: JSONObject): List<InnerTubeTrack> {
        val results = mutableListOf<JSONObject>()
        collectObjectsRecursively(root, "musicResponsiveListItemRenderer", results)
        collectObjectsRecursively(root, "musicTwoRowItemRenderer", results)
        return results.mapNotNull { renderer ->
            val videoId = InnerTubeParser.extractVideoId(renderer).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = InnerTubeParser.extractText(renderer, "title")
                ?: renderer.optJSONArray("flexColumns")?.optJSONObject(0)
                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.optJSONObject("text")?.let { InnerTubeParser.extractTextFromObj(it) }
                ?: return@mapNotNull null
            val artist = InnerTubeParser.extractText(renderer, "subtitle")
                ?: renderer.optJSONArray("flexColumns")?.optJSONObject(1)
                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.optJSONObject("text")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                ?: "YouTube Music"
            val durationText = renderer.optJSONArray("fixedColumns")?.optJSONObject(0)
                ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                ?.optJSONObject("text")?.let { InnerTubeParser.extractTextFromObj(it) }
                ?: "3:30"
            val thumb = InnerTubeParser.extractThumbnail(renderer) ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
            InnerTubeTrack(
                id = videoId,
                title = title,
                artist = artist,
                durationText = durationText,
                thumbnailUrl = InnerTubeParser.getHighResThumbnail(thumb, videoId)
            )
        }.distinctBy { it.id }
    }

    companion object {
        @Volatile
        private var instance: InnerTubeRepository? = null

        fun getInstance(context: Context): InnerTubeRepository =
            instance ?: synchronized(this) {
                instance ?: InnerTubeRepository(context.applicationContext).also { instance = it }
            }

        @Volatile
        var scrapedApiKey: String? = null
        @Volatile
        var scrapedClientVersion: String? = null
        @Volatile
        var lastScrapedTimeMs: Long = 0L
    }
}
