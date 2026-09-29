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
import kotlinx.coroutines.withTimeoutOrNull
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
    val androidSdkVersion: Int? = null
)

data class ResolvedStreamSource(
    val url: String,
    val resolver: String,
    val candidateKey: String? = null
)

object InnerTubeClients {
    val ANDROID_MUSIC = InnerTubeClientConfig(
        clientName = "ANDROID_MUSIC",
        clientVersion = "5.01",
        androidSdkVersion = 30,
        userAgent = FloWaveConstants.USER_AGENT_ANDROID_MUSIC,
        osName = "Android",
        osVersion = "11"
    )
    val IOS_MUSIC = InnerTubeClientConfig(
        clientName = "IOS",
        clientVersion = "19.29.1",
        deviceMake = "Apple",
        deviceModel = "iPhone15,2",
        userAgent = FloWaveConstants.USER_AGENT_IOS,
        osName = "iPhone",
        osVersion = "16.4"
    )
    val TVHTML5_SIMPLY_EMBEDDED_PLAYER = InnerTubeClientConfig(
        clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
        clientVersion = "2.0",
        userAgent = FloWaveConstants.USER_AGENT_TVHTML5
    )
    val WEB_REMIX = InnerTubeClientConfig(
        clientName = "WEB_REMIX",
        clientVersion = "1.20250122.01.00",
        userAgent = FloWaveConstants.USER_AGENT_DESKTOP
    )

    // Using exact client hierarchy proven to bypass current anti-bot restrictions locally
    val FALLBACK_CHAIN = listOf(ANDROID_MUSIC, IOS_MUSIC, TVHTML5_SIMPLY_EMBEDDED_PLAYER, WEB_REMIX)
}

open class InnerTubeRepository(context: Context? = null) {
    private val appContext = context?.applicationContext
    private val logger = appContext?.let { FloWaveLogger.getInstance(it) }
    private val localStreamResolver = appContext?.let { SealStyleDownloadEngine(it) }
    private val client = OkHttpClient.Builder()
        .connectTimeout(FloWaveConstants.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(FloWaveConstants.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // Cache stream URLs for 2 hours to avoid re-querying YouTube endpoints
    private val streamUrlCache = ConcurrentHashMap<String, Pair<Long, String>>()
    private val selectedFallbackSources = ConcurrentHashMap<String, ResolverCandidate>()
    private val streamResolutionLocks = ConcurrentHashMap<String, Mutex>()
    val streamDurationCache = ConcurrentHashMap<String, Long>()
    private val fallbackPool = ResolverPool().apply {
        seed(ResolverType.PIPED, FloWaveConstants.PIPED_STREAM_INSTANCES, "curated", System.currentTimeMillis())
        seed(ResolverType.PIPED, FloWaveConstants.PIPED_SEARCH_INSTANCES, "curated", System.currentTimeMillis())
        seed(ResolverType.INVIDIOUS, FloWaveConstants.INVIDIOUS_SEARCH_INSTANCES, "curated", System.currentTimeMillis())
    }
    private val fallbackPersistence = appContext?.let { ResolverPoolPersistence(it) }
    private val invidiousDiscovery = appContext?.let { InvidiousInstanceDiscovery(client) }
    private val discoveryMutex = Mutex()
    private var lastDiscoveryAttemptMs = 0L

    init {
        fallbackPersistence?.load()?.takeIf { it.isNotBlank() }?.let { fallbackPool.restore(it) }
    }

    fun getCachedDuration(videoId: String): Long {
        return streamDurationCache[videoId] ?: 0L
    }

    fun invalidateStreamUrl(videoId: String) {
        streamUrlCache.remove(videoId)
        selectedFallbackSources.remove(videoId)
        logger?.debug("online", "stream_cache_invalidated", context = mapOf("videoId" to videoId))
    }

    open suspend fun getStreamResolution(videoId: String, forceRefresh: Boolean = false): ResolvedStreamSource {
        val url = getStreamUrl(videoId, forceRefresh)
        val candidate = selectedFallbackSources[videoId]
        return ResolvedStreamSource(
            url = url,
            resolver = candidate?.type?.name?.lowercase() ?: "inner_tube",
            candidateKey = candidate?.key
        )
    }

    fun markPlayableStream(videoId: String, latencyMs: Long) {
        val candidate = selectedFallbackSources[videoId] ?: return
        val now = System.currentTimeMillis()
        fallbackPool.markSuccess(candidate.key, latencyMs, now)
        fallbackPersistence?.save(fallbackPool.serialize())
        fallbackPersistence?.savePreferred(candidate.type, candidate.key)
        logger?.info("online", "resolver_promoted_after_media_open", context = mapOf(
            "resolver" to candidate.type.name.lowercase(),
            "host" to candidate.host,
            "latencyMs" to latencyMs,
            "candidateKey" to candidate.key
        ))
    }

    fun markUnplayableStream(videoId: String, failureClass: String = "media_open_failure") {
        val candidate = selectedFallbackSources.remove(videoId) ?: return
        val now = System.currentTimeMillis()
        val cooldown = com.example.flowave.audio.OnlinePlaybackPolicy.hostCooldownMs(failureClass)
        fallbackPool.markFailure(candidate.key, failureClass, cooldown, now)
        fallbackPersistence?.save(fallbackPool.serialize())
        fallbackPersistence?.clearPreferred(candidate.type, candidate.key)
        logger?.warn("online", "resolver_demoted_after_media_open", context = mapOf(
            "resolver" to candidate.type.name.lowercase(),
            "host" to candidate.host,
            "failureClass" to failureClass,
            "cooldownMs" to cooldown,
            "candidateKey" to candidate.key
        ))
    }

    private fun preferredCandidates(type: ResolverType, nowMs: Long): List<ResolverCandidate> {
        val preferredKey = fallbackPersistence?.loadPreferred(type)
        val preferred = preferredKey?.let { fallbackPool.get(it) }
            ?.takeIf { it.type == type && it.cooldownUntilMs <= nowMs && it.retiredUntilMs <= nowMs }
        return listOfNotNull(preferred) + fallbackPool.ranked(type, nowMs).filter { it.key != preferred?.key }
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

    private fun recordFallbackFailure(
        videoId: String,
        resolver: String,
        candidate: ResolverCandidate,
        attempt: Int,
        startedAtNs: Long,
        status: Int? = null,
        error: Throwable? = null,
        failureClassOverride: String? = null,
        penalizeHost: Boolean = true,
        retryAfterHeader: String? = null
    ) {
        val failureClass = failureClassOverride
            ?: status?.let { com.example.flowave.audio.OnlinePlaybackPolicy.classifyHttpStatus(it) }
            ?: error?.let { com.example.flowave.audio.OnlinePlaybackPolicy.classifyResolverFailure(it) }
            ?: "resolver_error"
        if (failureClass == "cancelled") return
        val now = System.currentTimeMillis()
        val policyCooldownMs = com.example.flowave.audio.OnlinePlaybackPolicy.hostCooldownMs(failureClass)
        val retryAfterMs = retryAfterHeader?.toLongOrNull()
            ?.coerceIn(1L, 3600L)
            ?.times(1000L)
            ?.coerceAtLeast(policyCooldownMs)
            ?: policyCooldownMs
        if (penalizeHost && policyCooldownMs > 0L) {
            fallbackPool.markFailure(candidate.key, failureClass, retryAfterMs, now)
            fallbackPersistence?.save(fallbackPool.serialize())
        }
        val updated = fallbackPool.get(candidate.key)
        logger?.warn("online", "resolver_attempt_failed", context = mapOf(
            "videoId" to videoId,
            "resolver" to resolver,
            "host" to candidate.host,
            "attempt" to attempt,
            "failureClass" to failureClass,
            "httpStatus" to status,
            "durationMs" to ((System.nanoTime() - startedAtNs) / 1_000_000L),
            "retryAfterMs" to retryAfterMs,
            "consecutiveFailures" to (updated?.consecutiveFailures ?: candidate.consecutiveFailures),
            "retiredUntilMs" to (updated?.retiredUntilMs ?: candidate.retiredUntilMs),
            "retired" to ((updated?.retiredUntilMs ?: 0L) > now)
        ), throwable = error)
    }

    private suspend fun discoverFallbacks(): Boolean = discoveryMutex.withLock {
        val now = System.currentTimeMillis()
        if (now - lastDiscoveryAttemptMs < DISCOVERY_MIN_INTERVAL_MS) return@withLock false
        lastDiscoveryAttemptMs = now
        val discovery = invidiousDiscovery ?: return@withLock false
        val startedAt = System.nanoTime()
        logger?.info("online", "resolver_discovery_started", context = mapOf("source" to "invidious_registry"))
        return@withLock try {
            val discovered = withTimeoutOrNull(DISCOVERY_TOTAL_TIMEOUT_MS) { discovery.discover() }
            if (discovered == null) {
                logger?.warn("online", "resolver_discovery_failed", context = mapOf(
                    "source" to "invidious_registry",
                    "failureClass" to "timeout",
                    "durationMs" to ((System.nanoTime() - startedAt) / 1_000_000L)
                ))
                return@withLock false
            }
            var validated = 0
            discovered.take(MAX_DISCOVERED_CANDIDATES).forEach { candidate ->
                try {
                    val latency = withTimeoutOrNull(DISCOVERY_VALIDATION_TIMEOUT_MS) {
                        discovery.validate(candidate)
                    }
                    if (latency != null) {
                        val validatedCandidate = candidate.copy(validationMs = System.currentTimeMillis())
                        fallbackPool.upsert(validatedCandidate)
                        fallbackPool.markSuccess(validatedCandidate.key, latency, System.currentTimeMillis())
                        validated++
                        logger?.info("online", "resolver_candidate_promoted", context = mapOf(
                            "resolver" to "invidious",
                            "host" to candidate.host,
                            "source" to candidate.source,
                            "durationMs" to latency
                        ))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    logger?.debug("online", "resolver_candidate_rejected", context = mapOf(
                        "resolver" to "invidious",
                        "host" to candidate.host,
                        "failureClass" to com.example.flowave.audio.OnlinePlaybackPolicy.classifyResolverFailure(error)
                    ))
                }
            }
            fallbackPersistence?.save(fallbackPool.serialize())
            logger?.info("online", "resolver_discovery_finished", context = mapOf(
                "source" to "invidious_registry",
                "candidateCount" to discovered.size,
                "validatedCount" to validated,
                "poolSize" to fallbackPool.all().size,
                "healthyInvidious" to fallbackPool.ranked(ResolverType.INVIDIOUS, System.currentTimeMillis()).size,
                "durationMs" to ((System.nanoTime() - startedAt) / 1_000_000L)
            ))
            validated > 0
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger?.warn("online", "resolver_discovery_failed", context = mapOf(
                "source" to "invidious_registry",
                "failureClass" to com.example.flowave.audio.OnlinePlaybackPolicy.classifyResolverFailure(error),
                "durationMs" to ((System.nanoTime() - startedAt) / 1_000_000L)
            ), throwable = error)
            false
        }
    }

    suspend fun searchTracks(query: String): List<InnerTubeTrack> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        ensureKeysUpdated()
        val tracks = mutableListOf<InnerTubeTrack>()

        // ENGINE 1: YouTube Music InnerTube POST Endpoint with Songs Filter (Primary, Fast & High Quality)
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
            val searchUrl = "https://www.youtube.com/results?search_query=$encodedQuery&sp=EgIQAQ%253D%253D" // Filtered for videos
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
                            android.util.Log.e("InnerTubeRepository", "Extracted JSON string is malformed or invalid JSON syntax.", je)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("InnerTubeRepository", "YouTube Scraper failed: ${e.message}", e)
        }

        if (tracks.isNotEmpty()) return@withContext tracks

        // ENGINE 2: Public Piped Search API Instances
        for (candidate in fallbackPool.ranked(ResolverType.PIPED, System.currentTimeMillis()).take(3)) {
            try {
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val requestUrl = "https://${candidate.host}/search?q=$encodedQuery&filter=music_songs"
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
                    fallbackPool.markSuccess(candidate.key, 0L, System.currentTimeMillis())
                }
                response.close()
            } catch (e: Exception) {
                val failureClass = com.example.flowave.audio.OnlinePlaybackPolicy.classifyResolverFailure(e)
                if (failureClass != "cancelled") {
                    fallbackPool.markFailure(candidate.key, failureClass, com.example.flowave.audio.OnlinePlaybackPolicy.hostCooldownMs(failureClass), System.currentTimeMillis())
                    fallbackPersistence?.save(fallbackPool.serialize())
                }
                android.util.Log.w("InnerTubeRepository", "Piped search instance failed: ${e.message}", e)
            }
            if (tracks.isNotEmpty()) return@withContext tracks
        }

        // ENGINE 3: Public Invidious Search API Instances
        for (candidate in fallbackPool.ranked(ResolverType.INVIDIOUS, System.currentTimeMillis()).take(3)) {
            try {
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val request = Request.Builder()
                    .url("https://${candidate.host}/api/v1/search?q=$encodedQuery&type=video")
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
                    fallbackPool.markSuccess(candidate.key, 0L, System.currentTimeMillis())
                }
                response.close()
            } catch (e: Exception) {
                val failureClass = com.example.flowave.audio.OnlinePlaybackPolicy.classifyResolverFailure(e)
                if (failureClass != "cancelled") {
                    fallbackPool.markFailure(candidate.key, failureClass, com.example.flowave.audio.OnlinePlaybackPolicy.hostCooldownMs(failureClass), System.currentTimeMillis())
                    fallbackPersistence?.save(fallbackPool.serialize())
                }
                android.util.Log.w("InnerTubeRepository", "Invidious search instance failed: ${e.message}", e)
            }
            if (tracks.isNotEmpty()) return@withContext tracks
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

                if (clientConfig.clientName.contains("WEB")) {
                    reqBuilder.header("Origin", "https://music.youtube.com")
                    reqBuilder.header("Referer", "https://music.youtube.com/")
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
                .also { logger?.info("online", "stream_resolved", context = mapOf(
                    "videoId" to videoId,
                    "resolverSuccess" to true,
                    "playableValidation" to false,
                    "urlHost" to (runCatching { java.net.URI(it).host }.getOrNull().orEmpty()),
                    "urlPath" to (runCatching { java.net.URI(it).path }.getOrNull().orEmpty().take(80))
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
                "failureClass" to com.example.flowave.audio.OnlinePlaybackPolicy.classifyResolverFailure(error)
            ), error)
            throw error
        }
    }

    private suspend fun getStreamUrlInternal(
        videoId: String,
        forceRefresh: Boolean = false,
        allowDiscovery: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        cleanExpiredStreamCache()
        if (forceRefresh) {
            streamUrlCache.remove(videoId)
        }


        // Check cache first (valid for 30 minutes)
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

        // STRATEGY: Try Piped first (returns pre-resolved working URLs), then
        // InnerTube direct (which may require po_token/DroidGuard attestation
        // that third-party apps cannot provide), then Invidious, then yt-dlp.

        // 1. Piped Public API Fallback Stream Extraction (PRIMARY)
        for ((attempt, candidate) in preferredCandidates(ResolverType.PIPED, System.currentTimeMillis()).take(4).withIndex()) {
            val host = candidate.host
            val instance = candidate.endpoint()
            val startedAtNs = System.nanoTime()
            try {
                val request = Request.Builder()
                    .url("$instance$videoId")
                    .header("User-Agent", com.example.flowave.utils.FloWaveConstants.USER_AGENT_DESKTOP)
                    .build()
                fastClient.newCall(request).execute().use { response ->
                    val bodyString = response.body?.string() ?: ""
                    if (response.isSuccessful && bodyString.isNotEmpty()) {
                        val json = JSONObject(bodyString)
                        val selected = ResolverStreamSelector.selectPiped(json)
                        if (selected != null) {
                                logger?.info("online", "piped_stream_selected", context = mapOf(
                                    "videoId" to videoId,
                                    "host" to host,
                                    "attempt" to attempt + 1,
                                    "mimeType" to selected.mimeType,
                                    "container" to selected.container,
                                    "codec" to selected.codec,
                                    "bitrate" to selected.bitrate,
                                    "contentLength" to selected.contentLength,
                                    "audioOnly" to selected.audioOnly,
                                    "playableValidation" to false,
                                    "durationMs" to ((System.nanoTime() - startedAtNs) / 1_000_000L)
                                ))
                                selectedFallbackSources[videoId] = candidate
                                streamUrlCache[videoId] = Pair(System.currentTimeMillis(), selected.url)

                                // Also cache duration from Piped response
                                val durationSec = json.optLong("duration", 0L)
                                if (durationSec > 0L) {
                                    streamDurationCache[videoId] = durationSec * 1000L
                                }

                                return@withContext selected.url
                        }
                    }
                    recordFallbackFailure(
                        videoId = videoId,
                        resolver = "piped",
                        candidate = candidate,
                        attempt = attempt + 1,
                        startedAtNs = startedAtNs,
                        status = response.code.takeIf { !response.isSuccessful },
                        error = IOException("Piped response contained no usable audio stream"),
                        failureClassOverride = if (response.isSuccessful) "extraction_failure" else null,
                        penalizeHost = response.isSuccessful.not(),
                        retryAfterHeader = response.header("Retry-After")
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                recordFallbackFailure(videoId, "piped", candidate, attempt + 1, startedAtNs, error = e)
            }
        }

        // 2. InnerTube Multi-Client Fallback Chain: Native FOSS Direct Extraction
        for ((attempt, clientConfig) in InnerTubeClients.FALLBACK_CHAIN.withIndex()) {
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
                        put("clientScreen", "MOBILE")
                    })
                    put("playbackContext", JSONObject().apply {
                        put("contentPlaybackContext", JSONObject().apply {
                            put("signatureTimestamp", 20110)
                            put("html5Preference", "HTML5_PREF_WANTS")
                        })
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

                if (clientConfig.clientName.contains("WEB")) {
                    reqBuilder.header("Origin", "https://music.youtube.com")
                    reqBuilder.header("Referer", "https://music.youtube.com/")
                }

                val request = reqBuilder.build()

                executeWithRetry(request, maxRetries = 1).use { response ->
                    val bodyString = response.body?.string() ?: ""
                    if (response.isSuccessful && bodyString.isNotEmpty()) {
                        val json = JSONObject(bodyString)

                        // Check playability status first
                        val playabilityStatus = json.optJSONObject("playabilityStatus")
                        val status = playabilityStatus?.optString("status")
                        if (status != null && status != "OK") {
                            logger?.warn("online", "inner_tube_not_playable", context = mapOf(
                                "videoId" to videoId,
                                "client" to clientConfig.clientName,
                                "status" to status,
                                "reason" to (playabilityStatus.optString("reason").take(200))
                            ))
                            // Don't throw — try the next client in the chain
                        } else {
                            val streamingData = json.optJSONObject("streamingData")
                            val adaptiveFormats = streamingData?.optJSONArray("adaptiveFormats")
                                ?: streamingData?.optJSONArray("formats")

                            if (adaptiveFormats != null) {
                                val extractedUrl = parseAudioUrl(adaptiveFormats)
                                if (extractedUrl != null) {
                                    logger?.info("online", "inner_tube_client_succeeded", context = mapOf("videoId" to videoId, "client" to clientConfig.clientName))
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
                    "failureClass" to com.example.flowave.audio.OnlinePlaybackPolicy.classifyResolverFailure(e)
                ), throwable = e)
            }
        }

        // 3. Embedded yt-dlp optional resolver
        if (FloWaveRuntime.ready) {
            localStreamResolver?.resolveAudioUrl("https://www.youtube.com/watch?v=$videoId")?.let { result ->
                result.onSuccess { resolved ->
                    streamUrlCache[videoId] = System.currentTimeMillis() to resolved
                }.onFailure { error ->
                    logger?.warn("online", "resolver_attempt_failed", context = mapOf(
                        "videoId" to videoId,
                        "resolver" to "embedded",
                        "attempt" to 1,
                        "failureClass" to com.example.flowave.audio.OnlinePlaybackPolicy.classifyResolverFailure(error)
                    ), throwable = error)
                }
                result.getOrNull()?.let { return@withContext it }
            }
        } else {
            logger?.debug("online", "embedded_resolver_skipped", context = mapOf(
                "videoId" to videoId,
                "reason" to "optional_runtime_unavailable"
            ))
        }

        // 4. Invidious Direct Fallback Stream Extraction
        for ((attempt, candidate) in preferredCandidates(ResolverType.INVIDIOUS, System.currentTimeMillis()).take(2).withIndex()) {
            val host = candidate.host
                val startedAtNs = System.nanoTime()
                try {
                val apiUrl = "https://$host/api/v1/videos/$videoId"
                val request = Request.Builder()
                    .url(apiUrl)
                    .get()
                    .header("User-Agent", com.example.flowave.utils.FloWaveConstants.USER_AGENT_DESKTOP)
                    .build()
                fastClient.newCall(request).execute().use { response ->
                    val body = response.body?.source()?.readUtf8(4L * 1024L * 1024L).orEmpty()
                    val selected = if (response.isSuccessful && body.isNotBlank()) {
                        runCatching { ResolverStreamSelector.selectInvidious(JSONObject(body)) }.getOrNull()
                    } else null
                    if (selected != null) {
                        logger?.info("online", "invidious_stream_selected", context = mapOf(
                            "videoId" to videoId,
                            "host" to host,
                            "attempt" to attempt + 1,
                            "mimeType" to selected.mimeType,
                            "container" to selected.container,
                            "codec" to selected.codec,
                            "bitrate" to selected.bitrate,
                            "contentLength" to selected.contentLength,
                            "audioOnly" to selected.audioOnly,
                            "playableValidation" to false,
                            "durationMs" to ((System.nanoTime() - startedAtNs) / 1_000_000L)
                        ))
                        selectedFallbackSources[videoId] = candidate
                        streamUrlCache[videoId] = Pair(System.currentTimeMillis(), selected.url)
                        return@withContext selected.url
                    }
                    recordFallbackFailure(
                        videoId = videoId,
                        resolver = "invidious",
                        candidate = candidate,
                        attempt = attempt + 1,
                        startedAtNs = startedAtNs,
                        status = response.code,
                        error = IOException("Invidious response did not provide a stream"),
                        failureClassOverride = if (response.isSuccessful) "extraction_failure" else null,
                        penalizeHost = response.isSuccessful.not(),
                        retryAfterHeader = response.header("Retry-After")
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                recordFallbackFailure(videoId, "invidious", candidate, attempt + 1, startedAtNs, error = e)
            }
        }

        // All extraction candidates exhausted, throw a clear extraction exception to fail cleanly without fake test streams
        if (allowDiscovery && discoverFallbacks()) {
            return@withContext getStreamUrlInternal(videoId, forceRefresh, allowDiscovery = false)
        }
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
        private const val DISCOVERY_MIN_INTERVAL_MS = 15 * 60 * 1000L
        private const val DISCOVERY_TOTAL_TIMEOUT_MS = 20_000L
        private const val DISCOVERY_VALIDATION_TIMEOUT_MS = 4_000L
        private const val MAX_DISCOVERED_CANDIDATES = 6

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
