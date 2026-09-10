package com.example.flowave.data.remote

import android.content.Context
import com.example.flowave.FloWaveRuntime
import com.example.flowave.downloader.SealStyleDownloadEngine
import com.example.flowave.data.model.InnerTubeTrack
import com.example.flowave.data.model.Track
import com.example.flowave.data.model.LrcLine
import com.example.flowave.utils.FloWaveConstants
import com.example.flowave.diagnostics.FloWaveLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
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
    val userAgent: String
)

object InnerTubeClients {
    val ANDROID_TESTSUITE = InnerTubeClientConfig(
        clientName = "ANDROID_TESTSUITE",
        clientVersion = "1.9",
        userAgent = "com.google.android.youtube.testsuite/1.9 (Linux; U; Android 5.0.1; en_US; One Plus One Build/LRX22C)"
    )
    val ANDROID_EMBEDDED = InnerTubeClientConfig(
        clientName = "ANDROID_EMBEDDED_PLAYER",
        clientVersion = "19.30.36",
        userAgent = "com.google.android.youtube.tv/19.30.36 (Linux; U; Android 12; en_US; Chromecast Build/STTE.220621.019)"
    )
    val ANDROID_VR = InnerTubeClientConfig(
        clientName = "ANDROID_VR",
        clientVersion = "1.54.45",
        userAgent = "Mozilla/5.0 (Linux; Android 10; Quest 2) AppleWebKit/537.36 (KHTML, like Gecko) OculusBrowser/15.3.0.0.3.284240751 SamsungBrowser/4.0 Chrome/89.0.4389.90 VR Mobile Safari/537.36"
    )
    val ANDROID_MUSIC = InnerTubeClientConfig(
        clientName = "ANDROID_MUSIC",
        clientVersion = "7.03.52",
        userAgent = "com.google.android.apps.youtube.music/7.03.52 (Linux; U; Android 13; US)"
    )
    val WEB_REMIX = InnerTubeClientConfig(
        clientName = "WEB_REMIX",
        clientVersion = "1.20240216.07.00",
        userAgent = com.example.flowave.utils.FloWaveConstants.USER_AGENT_DESKTOP
    )
    val WEB = InnerTubeClientConfig(
        clientName = "WEB",
        clientVersion = "2.20260114.00.00",
        userAgent = com.example.flowave.utils.FloWaveConstants.USER_AGENT_DESKTOP
    )
    val TVHTML5_SIMPLY_EMBEDDED = InnerTubeClientConfig(
        clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
        clientVersion = "2.0",
        userAgent = com.example.flowave.utils.FloWaveConstants.USER_AGENT_TVHTML5
    )
    val WEB_EMBEDDED = InnerTubeClientConfig(
        clientName = "WEB_EMBEDDED_PLAYER",
        clientVersion = "1.20240125.01.00",
        userAgent = com.example.flowave.utils.FloWaveConstants.USER_AGENT_WEB_EMBEDDED
    )

    val FALLBACK_CHAIN = listOf(ANDROID_EMBEDDED, ANDROID_TESTSUITE, ANDROID_VR, TVHTML5_SIMPLY_EMBEDDED, WEB_EMBEDDED, ANDROID_MUSIC, WEB_REMIX, WEB)
}

class InnerTubeRepository(context: Context? = null) {
    private val appContext = context?.applicationContext
    private val logger = appContext?.let { FloWaveLogger.getInstance(it) }
    private val localStreamResolver = appContext?.let { SealStyleDownloadEngine() }
    private val client = OkHttpClient.Builder()
        .connectTimeout(FloWaveConstants.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(FloWaveConstants.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // Cache stream URLs for 2 hours to avoid re-querying YouTube endpoints
    private val streamUrlCache = ConcurrentHashMap<String, Pair<Long, String>>()
    private val streamResolutionLocks = ConcurrentHashMap<String, Mutex>()
    val streamDurationCache = ConcurrentHashMap<String, Long>()

    fun getCachedDuration(videoId: String): Long {
        return streamDurationCache[videoId] ?: 0L
    }

    fun invalidateStreamUrl(videoId: String) {
        streamUrlCache.remove(videoId)
        logger?.debug("online", "stream_cache_invalidated", context = mapOf("videoId" to videoId))
    }

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
            // Keep the stable source identifier in the MediaItem. The data
            // source resolves a fresh expiring URL only when playback opens.
            mediaUri = "flowave://youtube/${track.id}",
            artworkUri = track.thumbnailUrl,
            isOnline = true,
            source = "YOUTUBE",
            sourceId = track.id
        )
    }

    private val fastClient = client.newBuilder()
        .connectTimeout(FloWaveConstants.FAST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(FloWaveConstants.FAST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

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
                
                // For 429 (Rate Limit) or server errors (500, 502, 503, 504), close response and retry
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
        com.example.flowave.audio.OnlinePlaybackPolicy.isExpired(url)

    suspend fun searchTracks(query: String): List<InnerTubeTrack> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        ensureKeysUpdated()
        val tracks = mutableListOf<InnerTubeTrack>()

        // ENGINE 0: YouTube HTML Scraping (ytInitialData) - Extremely robust, zero-key, unfailing
        try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val searchUrl = "https://www.youtube.com/results?search_query=$encodedQuery&sp=EgIQAQ%253D%253D" // Filtered for videos
            val request = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", FloWaveConstants.USER_AGENT_DESKTOP)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            executeWithRetry(request).use { response ->
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
                            } else {
                                android.util.Log.d("InnerTubeRepository", "Scraped tracks parsed empty from extracted ytInitialData JSON.")
                            }
                        } catch (je: org.json.JSONException) {
                            android.util.Log.e("InnerTubeRepository", "Extracted JSON string is malformed or invalid JSON syntax.", je)
                        }
                    }
                } else {
                    android.util.Log.w("InnerTubeRepository", "HTTP request to search scrape failed with code ${response.code} or empty response.")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("InnerTubeRepository", "YouTube Scraper failed: ${e.message}", e)
        }

        if (tracks.isNotEmpty()) return@withContext tracks

        // ENGINE 1: YouTube Music InnerTube POST Endpoint
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
            }

            val request = Request.Builder()
                .url(FloWaveConstants.INNERTUBE_SEARCH_URL)
                .post(requestBodyJson.toString().toRequestBody(jsonMediaType))
                .header("User-Agent", InnerTubeClients.WEB_REMIX.userAgent)
                .header("Origin", "https://music.youtube.com")
                .build()

            val response = executeWithRetry(request)
            val bodyString = response.body?.string() ?: ""
            if (response.isSuccessful && bodyString.isNotEmpty()) {
                val json = JSONObject(bodyString)
                val parsed = InnerTubeParser.parseInnerTubeSearchJson(json)
                tracks.addAll(parsed)
                if (parsed.isNotEmpty()) {
                    android.util.Log.d("InnerTubeRepository", "Search results fetched from Engine 1 (Official InnerTube Search) for query: $query")
                }
            }
            response.close()
        } catch (e: Exception) {
            android.util.Log.w("InnerTubeRepository", "InnerTube search failed: ${e.message}", e)
        }

        if (tracks.isNotEmpty()) return@withContext tracks

        // ENGINE 2: Public Piped Search API Instances
        for (instance in FloWaveConstants.PIPED_SEARCH_INSTANCES) {
            try {
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val requestUrl = if (instance.contains("?")) "${instance}q=$encodedQuery&filter=music_songs" else "$instance$encodedQuery&filter=music_songs"
                val request = Request.Builder()
                    .url(requestUrl)
                    .header("User-Agent", FloWaveConstants.USER_AGENT_DESKTOP)
                    .build()
                val response = executeWithRetry(request)
                val bodyString = response.body?.string() ?: ""
                if (response.isSuccessful && bodyString.isNotEmpty()) {
                    val root = JSONObject(bodyString)
                    val items = root.optJSONArray("items")
                    if (items != null && items.length() > 0) {
                        for (i in 0 until items.length()) {
                            val item = items.optJSONObject(i) ?: continue
                            val url = item.optString("url")
                            val videoId = if (url.contains("v=")) url.substringAfter("v=").substringBefore("&") else url.substringAfterLast("/")
                            val title = item.optString("title")
                            val uploaderName = item.optString("uploaderName")
                            val thumbnail = item.optString("thumbnail")
                            val durationSec = item.optLong("duration", 210L)
                            val durationText = "%d:%02d".format(durationSec / 60, durationSec % 60)

                            if (videoId.isNotEmpty() && title.isNotEmpty()) {
                                tracks.add(
                                    InnerTubeTrack(
                                        id = videoId,
                                        title = title,
                                        artist = if (uploaderName.isNotEmpty()) uploaderName else "YouTube Artist",
                                        durationText = durationText,
                                        thumbnailUrl = if (thumbnail.isNotEmpty()) thumbnail else "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                                    )
                                )
                            }
                        }
                    }
                }
                response.close()
            } catch (e: Exception) {
                android.util.Log.w("InnerTubeRepository", "Piped search instance failed: ${e.message}", e)
            }
            if (tracks.isNotEmpty()) return@withContext tracks
        }

        // ENGINE 3: Public Invidious Search API Instances
        for (instance in FloWaveConstants.INVIDIOUS_SEARCH_INSTANCES) {
            try {
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val request = Request.Builder()
                    .url("$instance$encodedQuery&type=video")
                    .header("User-Agent", FloWaveConstants.USER_AGENT_DESKTOP)
                    .build()
                val response = executeWithRetry(request)
                val bodyString = response.body?.string() ?: ""
                if (response.isSuccessful && bodyString.isNotEmpty()) {
                    val items = JSONArray(bodyString)
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        val videoId = item.optString("videoId")
                        val title = item.optString("title")
                        val author = item.optString("author")
                        val durationSec = item.optLong("lengthSeconds", 210L)
                        val durationText = "%d:%02d".format(durationSec / 60, durationSec % 60)

                        if (videoId.isNotEmpty() && title.isNotEmpty()) {
                            tracks.add(
                                InnerTubeTrack(
                                    id = videoId,
                                    title = title,
                                    artist = if (author.isNotEmpty()) author else "YouTube Artist",
                                    durationText = durationText,
                                    thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                                )
                            )
                        }
                    }
                }
                response.close()
            } catch (e: Exception) {
                android.util.Log.w("InnerTubeRepository", "Invidious search instance failed: ${e.message}", e)
            }
            if (tracks.isNotEmpty()) return@withContext tracks
        }

        tracks
    }

    suspend fun getTrackMetadata(videoId: String): InnerTubeTrack? = withContext(Dispatchers.IO) {
        ensureKeysUpdated()
        for (clientConfig in InnerTubeClients.FALLBACK_CHAIN) {
            try {
                val requestBodyJson = JSONObject().apply {
                    put("context", JSONObject().apply {
                        put("client", JSONObject().apply {
                            put("clientName", clientConfig.clientName)
                            val version = if (!scrapedClientVersion.isNullOrBlank() && (clientConfig.clientName.contains("WEB") || clientConfig.clientName.contains("TV"))) {
                                scrapedClientVersion ?: clientConfig.clientVersion
                            } else {
                                clientConfig.clientVersion
                            }
                            put("clientVersion", version)
                            put("hl", "en")
                            put("gl", "US")
                        })
                    })
                    put("videoId", videoId)
                }

                val apiKey = if (clientConfig.clientName.contains("MUSIC") || clientConfig.clientName.contains("ANDROID")) {
                    com.example.flowave.utils.FloWaveConstants.INNERTUBE_KEY_MUSIC
                } else {
                    scrapedApiKey ?: com.example.flowave.utils.FloWaveConstants.INNERTUBE_KEY_WEB
                }
                val playerUrl = "https://www.youtube.com/youtubei/v1/player?key=$apiKey"

                val request = Request.Builder()
                    .url(playerUrl)
                    .post(requestBodyJson.toString().toRequestBody(jsonMediaType))
                    .header("User-Agent", clientConfig.userAgent)
                    .build()

                executeWithRetry(request, maxRetries = 2).use { response ->
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
        val iterator = streamUrlCache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.first > FloWaveConstants.STREAM_CACHE_DURATION_MS) {
                iterator.remove()
            }
        }
    }

    suspend fun getStreamUrl(videoId: String, forceRefresh: Boolean = false): String {
        logger?.info("online", "stream_resolution_started", context = mapOf("videoId" to videoId, "forceRefresh" to forceRefresh))
        val lock = streamResolutionLocks.computeIfAbsent(videoId) { Mutex() }
        return try {
            lock.withLock { getStreamUrlInternal(videoId, forceRefresh) }
                .also { logger?.info("online", "stream_resolution_succeeded", context = mapOf("videoId" to videoId)) }
        } catch (error: Exception) {
            logger?.error("online", "stream_resolution_failed", error.message.orEmpty(), mapOf("videoId" to videoId), error)
            throw error
        }
    }

    private suspend fun getStreamUrlInternal(videoId: String, forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        cleanExpiredStreamCache()
        if (forceRefresh) {
            streamUrlCache.remove(videoId)
        }
        // Direct local stream definitions for featured audio items resolved dynamically to actual streams
        when (videoId) {
            "synthwave_pulse", "chill_lofi", "ambient_space", "electronic_beat" -> {
                val queryText = when (videoId) {
                    "synthwave_pulse" -> "Midnight Cyber Pulse Neon Wave"
                    "chill_lofi" -> "Acoustic Rain Echoes LoFi Chill Studio"
                    "ambient_space" -> "Starlight Continuum Aether Void"
                    "electronic_beat" -> "Hyperdrive Resonance Quantum Bass"
                    else -> ""
                }
                android.util.Log.d("InnerTubeRepository", "Dynamically resolving featured stream: $videoId using search query '$queryText'")
                try {
                    val resolvedTracks = searchTracks(queryText)
                    if (resolvedTracks.isNotEmpty()) {
                        val realVideoId = resolvedTracks.first().id
                        android.util.Log.d("InnerTubeRepository", "Resolved featured track $videoId to YouTube video ID: $realVideoId")
                        return@withContext getStreamUrlInternal(realVideoId, forceRefresh)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("InnerTubeRepository", "Failed to dynamically resolve featured track $videoId: ${e.message}", e)
                }
            }
        }

        // Prefer the embedded yt-dlp resolver. It follows the current YouTube
        // player-client/signature rules and avoids stale hard-coded InnerTube
        // client versions. The legacy InnerTube chain remains a fallback for
        // devices where the optional runtime could not initialize.
        if (FloWaveRuntime.ready) {
            streamUrlCache[videoId]?.second?.let { cachedUrl ->
                if (!isStreamUrlExpired(cachedUrl)) {
                    logger?.debug("online", "resolver_cache_hit", context = mapOf("videoId" to videoId))
                    return@withContext cachedUrl
                }
            }
        }
        localStreamResolver?.resolveAudioUrl("https://www.youtube.com/watch?v=$videoId")
            ?.getOrNull()
            ?.let { resolved ->
                streamUrlCache[videoId] = System.currentTimeMillis() to resolved
                return@withContext resolved
            }

        // Check cache first (valid for 2 hours)
        val cached = streamUrlCache[videoId]
        if (cached != null) {
            val expired = isStreamUrlExpired(cached.second) || (System.currentTimeMillis() - cached.first) >= FloWaveConstants.STREAM_CACHE_DURATION_MS
            if (!expired) {
                android.util.Log.d("FloWaveInnerTube", "Stream URL served from cache for $videoId")
                return@withContext cached.second
            } else {
                streamUrlCache.remove(videoId)
                android.util.Log.d("FloWaveInnerTube", "Cached URL for $videoId expired. Refetching...")
            }
        }

        ensureKeysUpdated()

        // Multi-Client Fallback Chain: ANDROID_MUSIC -> WEB_REMIX -> TVHTML5_SIMPLY_EMBEDDED
        for (clientConfig in InnerTubeClients.FALLBACK_CHAIN) {
            try {
                val requestBodyJson = JSONObject().apply {
                    put("context", JSONObject().apply {
                        put("client", JSONObject().apply {
                            put("clientName", clientConfig.clientName)
                            val version = if (!scrapedClientVersion.isNullOrBlank() && (clientConfig.clientName.contains("WEB") || clientConfig.clientName.contains("TV"))) {
                                scrapedClientVersion ?: clientConfig.clientVersion
                            } else {
                                clientConfig.clientVersion
                            }
                            put("clientVersion", version)
                            put("hl", "en")
                            put("gl", "US")
                        })
                    })
                    put("videoId", videoId)
                    // These flags are required by current player clients and
                    // are also used by Velune's InnerTube request model.
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                }

                val apiKey = if (clientConfig.clientName.contains("MUSIC") || clientConfig.clientName.contains("ANDROID")) {
                    scrapedApiKey?.takeIf { scrapedClientVersion?.contains("MUSIC", ignoreCase = true) == true }
                        ?: com.example.flowave.utils.FloWaveConstants.INNERTUBE_KEY_MUSIC
                } else {
                    scrapedApiKey ?: com.example.flowave.utils.FloWaveConstants.INNERTUBE_KEY_WEB
                }
                val playerUrl = "https://www.youtube.com/youtubei/v1/player?key=$apiKey"

                val request = Request.Builder()
                    .url(playerUrl)
                    .post(requestBodyJson.toString().toRequestBody(jsonMediaType))
                    .header("User-Agent", clientConfig.userAgent)
                    .build()

                executeWithRetry(request, maxRetries = 2).use { response ->
                    val bodyString = response.body?.string() ?: ""
                    if (response.isSuccessful && bodyString.isNotEmpty()) {
                        val json = JSONObject(bodyString)
                        val streamingData = json.optJSONObject("streamingData")
                        val adaptiveFormats = streamingData?.optJSONArray("adaptiveFormats")
                            ?: streamingData?.optJSONArray("formats")

                        if (adaptiveFormats != null) {
                            val extractedUrl = parseAudioUrl(adaptiveFormats)
                            if (extractedUrl != null) {
                                logger?.info("online", "inner_tube_client_succeeded", context = mapOf("videoId" to videoId, "client" to clientConfig.clientName))
                                android.util.Log.d("FloWaveInnerTube", "Stream served by InnerTube client: ${clientConfig.clientName}")
                                android.util.Log.d("FloWaveInnerTube", "Stream served by InnerTube client: ${clientConfig.clientName}")
                                streamUrlCache[videoId] = Pair(System.currentTimeMillis(), extractedUrl)
                                val videoDetails = json.optJSONObject("videoDetails")
                                val lengthSecondsStr = videoDetails?.optString("lengthSeconds")
                                val durationSec = lengthSecondsStr?.toLongOrNull()
                                if (durationSec != null) {
                                    streamDurationCache[videoId] = durationSec * 1000L
                                }
                                return@withContext extractedUrl
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logger?.warn("online", "inner_tube_client_failed", context = mapOf("videoId" to videoId, "client" to clientConfig.clientName), throwable = e)
                android.util.Log.w("FloWaveInnerTube", "InnerTube client ${clientConfig.clientName} failed for ${videoId}: ${e.message}", e)
                android.util.Log.w("FloWaveInnerTube", "InnerTube client ${clientConfig.clientName} failed for $videoId: ${e.message}", e)
            }
        }

        // Piped Public API Fallback Stream Extraction
        for (instance in com.example.flowave.utils.FloWaveConstants.PIPED_STREAM_INSTANCES) {
            try {
                val request = Request.Builder()
                    .url("$instance$videoId")
                    .header("User-Agent", com.example.flowave.utils.FloWaveConstants.USER_AGENT_DESKTOP)
                    .build()
                fastClient.newCall(request).execute().use { response ->
                    val bodyString = response.body?.string() ?: ""
                    if (response.isSuccessful && bodyString.isNotEmpty()) {
                        val json = JSONObject(bodyString)
                        val audioStreams = json.optJSONArray("audioStreams")
                        if (audioStreams != null && audioStreams.length() > 0) {
                            var bestPipedUrl: String? = null
                            var maxBitrate = 0
                            for (i in 0 until audioStreams.length()) {
                                val stream = audioStreams.optJSONObject(i) ?: continue
                                val url = stream.optString("url")
                                val bitrate = stream.optInt("bitrate", 0)
                                if (url.isNotEmpty() && bitrate >= maxBitrate) {
                                    maxBitrate = bitrate
                                    bestPipedUrl = url
                                }
                            }
                            if (bestPipedUrl != null) {
                                logger?.info("online", "piped_fallback_succeeded", context = mapOf("videoId" to videoId))
                                android.util.Log.d("FloWaveInnerTube", "Stream served by Piped instance: ${instance}")
                                android.util.Log.d("FloWaveInnerTube", "Stream served by Piped instance: $instance")
                                streamUrlCache[videoId] = Pair(System.currentTimeMillis(), bestPipedUrl)
                                return@withContext bestPipedUrl
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logger?.warn("online", "piped_fallback_failed", context = mapOf("videoId" to videoId), throwable = e)
                android.util.Log.w("FloWaveInnerTube", "Piped extraction failed for ${instance}: ${e.message}", e)
                android.util.Log.w("FloWaveInnerTube", "Piped extraction failed for $instance: ${e.message}", e)
            }
        }

        // Invidious Direct Fallback Stream Extraction
        for (searchInstance in com.example.flowave.utils.FloWaveConstants.INVIDIOUS_SEARCH_INSTANCES) {
            try {
                val baseUrl = if (searchInstance.contains("api/v1/")) {
                    searchInstance.substringBefore("api/v1/")
                } else {
                    searchInstance
                }
                val testUrl = "${baseUrl}latest_version?id=$videoId&itag=140"
                val request = Request.Builder()
                    .url(testUrl)
                    .head()
                    .header("User-Agent", com.example.flowave.utils.FloWaveConstants.USER_AGENT_DESKTOP)
                    .build()
                fastClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful || response.code in 300..399) {
                        logger?.info("online", "invidious_fallback_succeeded", context = mapOf("videoId" to videoId))
                        android.util.Log.d("FloWaveInnerTube", "Stream served by Invidious instance: ${baseUrl}")
                        android.util.Log.d("FloWaveInnerTube", "Stream served by Invidious instance: $baseUrl")
                        streamUrlCache[videoId] = Pair(System.currentTimeMillis(), testUrl)
                        return@withContext testUrl
                    }
                }
            } catch (e: Exception) {
                logger?.warn("online", "invidious_fallback_failed", context = mapOf("videoId" to videoId), throwable = e)
                android.util.Log.w("FloWaveInnerTube", "Invidious extraction failed for ${searchInstance}: ${e.message}")
                android.util.Log.w("FloWaveInnerTube", "Invidious extraction failed for $searchInstance: ${e.message}")
            }
        }

        // All extraction candidates exhausted, throw a clear extraction exception to fail cleanly without fake test streams
        throw java.io.IOException("All extraction attempts and fallback clients were exhausted for videoId: $videoId")
    }

    private data class FormatCandidate(
        val url: String,
        val bitrate: Int,
        val mimeType: String,
        val itag: Int
    )

    private suspend fun parseAudioUrl(formats: JSONArray): String? {
        var bestOpus: FormatCandidate? = null
        var bestAac: FormatCandidate? = null
        var bestOther: FormatCandidate? = null

        for (i in 0 until formats.length()) {
            val format = formats.optJSONObject(i) ?: continue

            val url = if (format.has("url")) {
                format.optString("url")
            } else {
                val cipherText = format.optString("signatureCipher").takeIf { it.isNotEmpty() }
                    ?: format.optString("cipher").takeIf { it.isNotEmpty() }
                if (!cipherText.isNullOrEmpty()) {
                    try {
                        com.example.flowave.utils.YouTubeDecipherer.decipher(cipherText, client)
                    } catch (e: Exception) {
                        android.util.Log.e("InnerTubeRepository", "Failed to decipher format: ${e.message}")
                        ""
                    }
                } else {
                    ""
                }
            }

            if (url.isEmpty() || !url.startsWith("http")) continue

            val mimeType = format.optString("mimeType")
            if (!mimeType.contains("audio/")) continue

            val bitrate = format.optInt("bitrate", 0)
            val itag = format.optInt("itag", 0)
            val candidate = FormatCandidate(url, bitrate, mimeType, itag)

            if (mimeType.contains("opus") || itag == 251) {
                if (bestOpus == null || bitrate > bestOpus.bitrate) {
                    bestOpus = candidate
                }
            } else if (mimeType.contains("mp4a") || itag == 140) {
                if (bestAac == null || bitrate > bestAac.bitrate) {
                    bestAac = candidate
                }
            } else {
                if (bestOther == null || bitrate > bestOther.bitrate) {
                    bestOther = candidate
                }
            }
        }

        return bestOpus?.url ?: bestAac?.url ?: bestOther?.url
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

    private fun parseLrc(lrcContent: String): List<LrcLine> {
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

        // Balanced brace parsing to extract the exact JSON object
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

        android.util.Log.w("InnerTubeRepository", "Scraped HTML brace parsing warning: Could not find matching closing brace. Falling back to index-of parsing.")
        return null
    }

    fun getFeaturedAudioStreams(): List<InnerTubeTrack> {
        return listOf(
            InnerTubeTrack(
                id = "synthwave_pulse",
                title = "Midnight Cyber Pulse",
                artist = "Neon Wave",
                durationText = "6:12",
                thumbnailUrl = "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?q=80&w=600",
                album = "Neon Horizon"
            ),
            InnerTubeTrack(
                id = "chill_lofi",
                title = "Acoustic Rain Echoes",
                artist = "LoFi Chill Studio",
                durationText = "7:04",
                thumbnailUrl = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?q=80&w=600",
                album = "Midnight Rain"
            ),
            InnerTubeTrack(
                id = "ambient_space",
                title = "Starlight Continuum",
                artist = "Aether Void",
                durationText = "5:45",
                thumbnailUrl = "https://images.unsplash.com/photo-1451187580459-43490279c0fa?q=80&w=600",
                album = "Cosmic Journey"
            ),
            InnerTubeTrack(
                id = "electronic_beat",
                title = "Hyperdrive Resonance",
                artist = "Quantum Bass",
                durationText = "4:50",
                thumbnailUrl = "https://images.unsplash.com/photo-1508700115892-45ecd05ae2ad?q=80&w=600",
                album = "Cybernetic Beats"
            )
        )
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
