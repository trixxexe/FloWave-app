package com.example.flowave.data.remote

import android.content.Context
import com.example.flowave.FloWaveRuntime
import com.example.flowave.FloWaveApplication
import com.example.flowave.downloader.SealStyleDownloadEngine
import com.example.flowave.downloader.YtDlpBinaryValidator
import com.example.flowave.data.model.InnerTubeTrack
import com.example.flowave.data.model.Track
import com.example.flowave.data.model.LrcLine
import com.example.flowave.utils.FloWaveConstants
import com.example.flowave.utils.YouTubeDecipherer
import com.example.flowave.diagnostics.FloWaveLogger
import com.example.flowave.audio.OnlinePlaybackPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val localStreamResolver = SealStyleDownloadEngine(appContext)
    private val poTokenGenerator = appContext?.let { com.example.flowave.data.remote.botguard.PoTokenGenerator(it) }
    private val client = OkHttpClient.Builder()
        .connectTimeout(FloWaveConstants.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(FloWaveConstants.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

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

    suspend fun getVisitorData(forceRefresh: Boolean = false): String? = withContext(Dispatchers.IO) {
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
                            put("clientVersion", "1.20250122.01.00")
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

            // Method 2: Fallback to https://www.youtube.com/sw.js_data
            try {
                val swUrl = "https://www.youtube.com/sw.js_data"
                val swReq = Request.Builder()
                    .url(swUrl)
                    .header("User-Agent", FloWaveConstants.USER_AGENT_DESKTOP)
                    .build()
                client.newCall(swReq).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        val match = Regex("\"visitorData\"\\s*:\\s*\"([^\"]+)\"").find(body)
                        val vd = match?.groupValues?.getOrNull(1)
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
                .also { logger?.info("online", "stream_resolved", context = mapOf(
                    "videoId" to videoId,
                    "resolverSuccess" to true,
                    "urlHost" to (runCatching { java.net.URI(it.url).host }.getOrNull().orEmpty())
                )) }
        } catch (error: kotlinx.coroutines.CancellationException) {
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
        } catch (e: Exception) {
            // Fallback
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
        var delayMs = 1000L
        for (attempt in 1..maxRetries) {
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    return@withContext response
                }
                
                val code = response.code
                android.util.Log.w("InnerTubeRepository", "HTTP call returned code $code on attempt $attempt")
                
                // If it's a non-retryable client error, do not retry
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
                android.util.Log.w("InnerTubeRepository", "Timeout on attempt $attempt: ${e.message}")
                if (attempt < maxRetries) {
                    delay(delayMs)
                    delayMs *= 2
                }
            } catch (e: IOException) {
                lastException = e
                android.util.Log.w("InnerTubeRepository", "I/O error on attempt $attempt: ${e.message}")
                if (attempt < maxRetries) {
                    delay(delayMs)
                    delayMs *= 2
                }
            }
        }
        throw lastException ?: IOException("Request execution failed after $maxRetries attempts")
    }

    private fun isStreamUrlExpired(url: String): Boolean =
        OnlinePlaybackPolicy.isExpired(url)

    suspend fun searchTracks(query: String): List<InnerTubeTrack> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        ensureKeysUpdated()
        val tracks = mutableListOf<InnerTubeTrack>()

        // ENGINE 1: YouTube Music InnerTube Search (WEB_REMIX client with Songs filter)
        try {
            val requestBodyJson = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", InnerTubeClients.WEB_REMIX.clientName)
                        put("clientVersion", InnerTubeClients.WEB_REMIX.clientVersion)
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("query", query)
                put("params", "EgWKAQIIAWoKEAkQBRAKEAMQBBAK") // Filter for Songs
            }

            val request = Request.Builder()
                .url(FloWaveConstants.INNERTUBE_SEARCH_URL)
                .post(requestBodyJson.toString().toRequestBody(jsonMediaType))
                .header("User-Agent", InnerTubeClients.WEB_REMIX.userAgent)
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .build()

            executeWithRetry(request, maxRetries = 2).use { response ->
                val bodyString = response.body?.string() ?: ""
                if (response.isSuccessful && bodyString.isNotEmpty()) {
                    val json = JSONObject(bodyString)
                    json.optJSONObject("responseContext")?.optString("visitorData")?.takeIf { it.isNotBlank() }?.let {
                        cachedVisitorData = it
                    }
                    val parsed = InnerTubeParser.parseInnerTubeSearchJson(json)
                    tracks.addAll(parsed)
                    if (parsed.isNotEmpty()) {
                        android.util.Log.d("InnerTubeRepository", "Search results fetched from InnerTube Music Search for query: $query (${parsed.size} tracks)")
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("InnerTubeRepository", "InnerTube music search notice: ${e.message}", e)
        }

        if (tracks.isNotEmpty()) return@withContext tracks

        // ENGINE 0: YouTube HTML Scraping Fallback (ytInitialData)
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
                        try {
                            val json = JSONObject(jsonString)
                            val scrapedTracks = InnerTubeParser.parseYtInitialData(json)
                            if (scrapedTracks.isNotEmpty()) {
                                tracks.addAll(scrapedTracks)
                            }
                        } catch (je: org.json.JSONException) {
                            android.util.Log.e("InnerTubeRepository", "Extracted JSON string is malformed.", je)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("InnerTubeRepository", "YouTube Scraper failed: ${e.message}", e)
        }

        if (tracks.isNotEmpty()) return@withContext tracks

        // ENGINE 2: Embedded yt-dlp search fallback (On-device fallback)
        if (YtDlpBinaryValidator.isRuntimeAvailable(appContext) && FloWaveRuntime.awaitReady(2_000L)) {
            try {
                val ytDlpResults = localStreamResolver.inspect("ytsearch15:$query", allowFallbackToNative = false).getOrNull()
                if (!ytDlpResults.isNullOrEmpty()) {
                    val mapped = ytDlpResults.mapNotNull { item ->
                        val id = item.id ?: item.webpageUrl.substringAfter("v=").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        InnerTubeTrack(
                            id = id,
                            title = item.title,
                            artist = item.creator.ifBlank { "YouTube" },
                            durationText = item.durationSeconds?.let { "%d:%02d".format(it / 60, it % 60) } ?: "3:30",
                            thumbnailUrl = item.thumbnailUrl ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
                        )
                    }
                    tracks.addAll(mapped)
                }
            } catch (e: Exception) {
                logger?.warn("online", "ytdlp_search_failed", context = mapOf("query" to query), throwable = e)
            }
        }

        tracks
    }

    suspend fun getTrackMetadata(videoId: String): InnerTubeTrack? = withContext(Dispatchers.IO) {
        for (clientConfig in InnerTubeClients.FALLBACK_CHAIN) {
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
                val requestBodyJson = JSONObject().apply {
                    put("context", JSONObject().apply {
                        put("client", clientJson)
                    })
                    put("videoId", videoId)
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                }

                val playerUrl = if (clientConfig.clientName.contains("WEB")) {
                    "https://www.youtube.com/youtubei/v1/player?key=${FloWaveConstants.INNERTUBE_KEY_WEB}&prettyPrint=false"
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
                                album = "YouTube Stream Extraction"
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
            val expired = isStreamUrlExpired(cached.second.url) || (System.currentTimeMillis() - cached.first) >= FloWaveConstants.STREAM_CACHE_DURATION_MS
            if (!expired) {
                android.util.Log.d("FloWaveInnerTube", "Stream resolution served from cache for $videoId")
                return@withContext cached.second
            } else {
                streamResolutionCache.remove(videoId)
                streamUrlCache.remove(videoId)
                android.util.Log.d("FloWaveInnerTube", "Cached resolution for $videoId expired. Refetching...")
            }
        }

        // 1. InnerTube Multi-Client Extraction Chain (Native direct extraction)
        // Never attach stub or placeholder tokens unless verified non-stub
        val poToken = try {
            withTimeoutOrNull(800L) {
                poTokenGenerator?.generateToken(videoId)?.takeIf { com.example.flowave.data.remote.botguard.PoTokenGenerator.isValidToken(it) }
            }
        } catch (e: Exception) {
            null
        }

        var visitorData = getVisitorData(forceRefresh = forceRefresh)
        var needFreshVisitorData = false

        for ((attempt, clientConfig) in InnerTubeClients.STREAM_FALLBACK_CHAIN.withIndex()) {
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

                val charset = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
                val cpn = (1..16).map { charset.random() }.joinToString("")

                val requestBodyJson = JSONObject().apply {
                    put("context", JSONObject().apply {
                        put("client", clientJson)
                    })
                    put("cpn", cpn)
                    put("videoId", videoId)
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)

                    // TVHTML5_SIMPLY_EMBEDDED_PLAYER requires thirdParty embedUrl
                    if (clientConfig.clientName == "TVHTML5_SIMPLY_EMBEDDED_PLAYER") {
                        put("thirdParty", JSONObject().apply {
                            put("embedUrl", "https://www.youtube.com/")
                        })
                    }

                    // Attach serviceIntegrityDimensions ONLY if a verified non-stub token is present
                    if (com.example.flowave.data.remote.botguard.PoTokenGenerator.isValidToken(poToken)) {
                        put("serviceIntegrityDimensions", JSONObject().apply {
                            put("poToken", poToken)
                        })
                    }
                }

                val playerUrl = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"

                val reqBuilder = Request.Builder()
                    .url(playerUrl)
                    .post(requestBodyJson.toString().toRequestBody(jsonMediaType))
                    .header("User-Agent", clientConfig.userAgent)
                    .header("Content-Type", "application/json")

                clientConfig.clientNumber?.let { reqBuilder.header("X-YouTube-Client-Name", it) }
                reqBuilder.header("X-YouTube-Client-Version", clientConfig.clientVersion)
                if (!visitorData.isNullOrBlank()) {
                    reqBuilder.header("X-Goog-Visitor-Id", visitorData)
                }
                if (clientConfig.clientName == "ANDROID_VR" || clientConfig.clientName == "TVHTML5_SIMPLY_EMBEDDED_PLAYER") {
                    reqBuilder.header("Origin", "https://www.youtube.com")
                    reqBuilder.header("Referer", "https://www.youtube.com/")
                }

                val request = reqBuilder.build()

                executeWithRetry(request, maxRetries = 1).use { response ->
                    val bodyString = response.body?.string() ?: ""
                    if (response.isSuccessful && bodyString.isNotEmpty()) {
                        val json = JSONObject(bodyString)

                        // Update visitorData if responseContext contains it
                        json.optJSONObject("responseContext")?.optString("visitorData")?.takeIf { it.isNotBlank() }?.let {
                            cachedVisitorData = it
                        }

                        val playabilityStatus = json.optJSONObject("playabilityStatus")
                        val status = playabilityStatus?.optString("status")
                        if (status != null && status != "OK") {
                            logger?.warn("online", "inner_tube_not_playable", context = mapOf(
                                "videoId" to videoId,
                                "client" to clientConfig.clientName,
                                "status" to status,
                                "reason" to (playabilityStatus.optString("reason").take(200))
                            ))
                            if (status == "LOGIN_REQUIRED") {
                                needFreshVisitorData = true
                                cachedVisitorData = null
                            }
                        } else {
                            val extractedUrl = InnerTubeParser.parsePlayerResponse(json, client)
                            if (!extractedUrl.isNullOrBlank()) {
                                logger?.info("online", "inner_tube_client_succeeded", context = mapOf(
                                    "videoId" to videoId,
                                    "client" to clientConfig.clientName
                                ))

                                val requestHeaders = mutableMapOf<String, String>(
                                    "User-Agent" to clientConfig.userAgent
                                )
                                clientConfig.clientNumber?.let { requestHeaders["X-YouTube-Client-Name"] = it }
                                requestHeaders["X-YouTube-Client-Version"] = clientConfig.clientVersion
                                if (!visitorData.isNullOrBlank()) {
                                    requestHeaders["X-Goog-Visitor-Id"] = visitorData
                                }
                                if (clientConfig.clientName == "ANDROID_VR" || clientConfig.clientName == "TVHTML5_SIMPLY_EMBEDDED_PLAYER") {
                                    requestHeaders["Origin"] = "https://www.youtube.com"
                                    requestHeaders["Referer"] = "https://www.youtube.com/"
                                }

                                val resolvedSource = ResolvedStreamSource(
                                    url = extractedUrl,
                                    resolver = "inner_tube",
                                    candidateKey = clientConfig.clientName,
                                    userAgent = clientConfig.userAgent,
                                    requestHeaders = requestHeaders
                                )

                                streamResolutionCache[videoId] = Pair(System.currentTimeMillis(), resolvedSource)
                                streamUrlCache[videoId] = Pair(System.currentTimeMillis(), extractedUrl)

                                val videoDetails = json.optJSONObject("videoDetails")
                                val lengthSecondsStr = videoDetails?.optString("lengthSeconds")
                                val durationSec = lengthSecondsStr?.toLongOrNull()
                                if (durationSec != null) {
                                    streamDurationCache[videoId] = durationSec * 1000L
                                }
                                return@withContext resolvedSource
                            } else {
                                // SABR / URL-less format response detected
                                logger?.warn("online", "inner_tube_formats_url_less_or_sabr", context = mapOf(
                                    "videoId" to videoId,
                                    "client" to clientConfig.clientName
                                ))
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
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

        // 2. Embedded yt-dlp resolver (Safe, on-device, handles BotGuard & signature challenges)
        if (FloWaveRuntime.ready || FloWaveRuntime.awaitReady(2_000L)) {
            val result = localStreamResolver.resolveAudioUrl("https://www.youtube.com/watch?v=$videoId", allowFallbackToNative = false)
            result.onSuccess { resolved ->
                logger?.info("online", "embedded_resolver_succeeded", context = mapOf("videoId" to videoId))
                val resolvedSource = ResolvedStreamSource(
                    url = resolved,
                    resolver = "embedded",
                    candidateKey = "yt_dlp",
                    userAgent = FloWaveConstants.USER_AGENT_DESKTOP,
                    requestHeaders = mapOf("User-Agent" to FloWaveConstants.USER_AGENT_DESKTOP)
                )
                streamResolutionCache[videoId] = Pair(System.currentTimeMillis(), resolvedSource)
                streamUrlCache[videoId] = Pair(System.currentTimeMillis(), resolved)
                return@withContext resolvedSource
            }.onFailure { error ->
                logger?.warn("online", "resolver_attempt_failed", context = mapOf(
                    "videoId" to videoId,
                    "resolver" to "embedded",
                    "attempt" to 1,
                    "failureClass" to OnlinePlaybackPolicy.classifyResolverFailure(error)
                ), throwable = error)
            }
        } else {
            logger?.warn("online", "embedded_resolver_skipped", context = mapOf(
                "videoId" to videoId,
                "reason" to "optional_runtime_unavailable"
            ))
        }

        throw java.io.IOException("All extraction attempts and fallback clients were exhausted for videoId: $videoId")
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

        if (startIndex == -1) {
            android.util.Log.w("InnerTubeRepository", "Scraped HTML structure warning: 'ytInitialData' variable not found in page. YouTube structure might have changed.")
            return null
        }

        val jsonStart = html.indexOf("{", startIndex + selectedPrefix.length)
        if (jsonStart == -1) {
            android.util.Log.w("InnerTubeRepository", "Scraped HTML structure error: Initial JSON brace '{' not found after prefix '$selectedPrefix'.")
            return null
        }

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

        android.util.Log.w("InnerTubeRepository", "Scraped HTML brace parsing warning: Could not find matching closing brace.")
        return null
    }

    suspend fun getTrendingTracks(): List<InnerTubeTrack> {
        return try {
            val tracks = searchTracks("Top Global Hits Music 2026")
            if (tracks.isNotEmpty()) {
                tracks.take(15)
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
                android.util.Log.d("InnerTubeRepository", "Successfully refreshed INNERTUBE_API_KEY")
            }
            if (!clientVersion.isNullOrBlank()) {
                scrapedClientVersion = clientVersion
                android.util.Log.d("InnerTubeRepository", "Successfully refreshed clientVersion: $clientVersion")
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
