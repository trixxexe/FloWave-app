package com.example.flowave.data.remote

import com.example.flowave.data.model.InnerTubeTrack
import com.example.flowave.data.model.LrcLine
import com.example.flowave.utils.FloWaveConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val userAgent: String
)

object InnerTubeClients {
    val ANDROID_MUSIC = InnerTubeClientConfig(
        clientName = "ANDROID_MUSIC",
        clientVersion = "6.25.52",
        userAgent = com.example.flowave.utils.FloWaveConstants.USER_AGENT_ANDROID_MUSIC
    )
    val WEB_REMIX = InnerTubeClientConfig(
        clientName = "WEB_REMIX",
        clientVersion = "1.20231218.01.00",
        userAgent = com.example.flowave.utils.FloWaveConstants.USER_AGENT_DESKTOP
    )
    val TVHTML5_SIMPLY_EMBEDDED = InnerTubeClientConfig(
        clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
        clientVersion = "2.0",
        userAgent = com.example.flowave.utils.FloWaveConstants.USER_AGENT_TVHTML5
    )
    val WEB_EMBEDDED = InnerTubeClientConfig(
        clientName = "WEB_EMBEDDED_PLAYER",
        clientVersion = "1.20230615.0.0",
        userAgent = com.example.flowave.utils.FloWaveConstants.USER_AGENT_WEB_EMBEDDED
    )

    val FALLBACK_CHAIN = listOf(TVHTML5_SIMPLY_EMBEDDED, WEB_EMBEDDED, ANDROID_MUSIC, WEB_REMIX)
}

class InnerTubeRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(FloWaveConstants.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(FloWaveConstants.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // Cache stream URLs for 2 hours to avoid re-querying YouTube endpoints
    private val streamUrlCache = ConcurrentHashMap<String, Pair<Long, String>>()

    private val fastClient = client.newBuilder()
        .connectTimeout(FloWaveConstants.FAST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(FloWaveConstants.FAST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private fun executeWithRetry(request: Request, maxRetries: Int = 3): Response {
        var lastException: IOException? = null
        var delayMs = 1000L
        for (attempt in 1..maxRetries) {
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    return response
                }
                
                val code = response.code
                android.util.Log.w("InnerTubeRepository", "HTTP call returned code $code on attempt $attempt")
                
                // If it's a non-retryable client error, do not retry
                if (code == 400 || code == 401 || code == 403 || code == 404) {
                    return response
                }
                
                // For 429 (Rate Limit) or server errors (500, 502, 503, 504), close response and retry
                response.close()
                if (attempt < maxRetries) {
                    Thread.sleep(delayMs)
                    delayMs *= 2
                }
            } catch (e: SocketTimeoutException) {
                lastException = e
                android.util.Log.w("InnerTubeRepository", "Timeout on attempt $attempt: ${e.message}")
                if (attempt < maxRetries) {
                    Thread.sleep(delayMs)
                    delayMs *= 2
                }
            } catch (e: IOException) {
                lastException = e
                android.util.Log.w("InnerTubeRepository", "I/O error on attempt $attempt: ${e.message}")
                if (attempt < maxRetries) {
                    Thread.sleep(delayMs)
                    delayMs *= 2
                }
            }
        }
        throw lastException ?: IOException("Request execution failed after $maxRetries attempts")
    }

    private fun isStreamUrlExpired(url: String): Boolean {
        if (!url.contains("expire=")) return false
        val expireStr = url.substringAfter("expire=").substringBefore("&")
        val expireTimeSec = expireStr.toLongOrNull() ?: return false
        // Expired if within 5 minutes of expiration
        return (expireTimeSec - 300) < (System.currentTimeMillis() / 1000)
    }

    suspend fun searchTracks(query: String): List<InnerTubeTrack> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
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

    suspend fun getStreamUrl(videoId: String, forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
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
                        return@withContext getStreamUrl(realVideoId, forceRefresh)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("InnerTubeRepository", "Failed to dynamically resolve featured track $videoId: ${e.message}", e)
                }
            }
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

        // Multi-Client Fallback Chain: ANDROID_MUSIC -> WEB_REMIX -> TVHTML5_SIMPLY_EMBEDDED
        for (clientConfig in InnerTubeClients.FALLBACK_CHAIN) {
            try {
                val requestBodyJson = JSONObject().apply {
                    put("context", JSONObject().apply {
                        put("client", JSONObject().apply {
                            put("clientName", clientConfig.clientName)
                            put("clientVersion", clientConfig.clientVersion)
                            put("hl", "en")
                            put("gl", "US")
                        })
                    })
                    put("videoId", videoId)
                }

                val request = Request.Builder()
                    .url(FloWaveConstants.INNERTUBE_PLAYER_URL)
                    .post(requestBodyJson.toString().toRequestBody(jsonMediaType))
                    .header("User-Agent", clientConfig.userAgent)
                    .build()

                val response = executeWithRetry(request, maxRetries = 2)
                val bodyString = response.body?.string() ?: ""
                if (response.isSuccessful && bodyString.isNotEmpty()) {
                    val json = JSONObject(bodyString)
                    val streamingData = json.optJSONObject("streamingData")
                    val adaptiveFormats = streamingData?.optJSONArray("adaptiveFormats")
                        ?: streamingData?.optJSONArray("formats")

                    if (adaptiveFormats != null) {
                        val extractedUrl = parseAudioUrl(adaptiveFormats)
                        if (extractedUrl != null) {
                            android.util.Log.d("FloWaveInnerTube", "Stream served by InnerTube client: ${clientConfig.clientName}")
                            streamUrlCache[videoId] = Pair(System.currentTimeMillis(), extractedUrl)
                            response.close()
                            return@withContext extractedUrl
                        }
                    }
                }
                response.close()
            } catch (e: Exception) {
                android.util.Log.w("FloWaveInnerTube", "InnerTube client ${clientConfig.clientName} failed for $videoId: ${e.message}", e)
            }
        }

        // Piped Public API Fallback Stream Extraction
        for (instance in FloWaveConstants.PIPED_STREAM_INSTANCES) {
            try {
                val request = Request.Builder()
                    .url("$instance$videoId")
                    .header("User-Agent", FloWaveConstants.USER_AGENT_DESKTOP)
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
                                android.util.Log.d("FloWaveInnerTube", "Stream served by Piped instance: $instance")
                                streamUrlCache[videoId] = Pair(System.currentTimeMillis(), bestPipedUrl)
                                return@withContext bestPipedUrl
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("FloWaveInnerTube", "Piped extraction failed for $instance: ${e.message}", e)
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

    private fun parseAudioUrl(formats: JSONArray): String? {
        var bestOpus: FormatCandidate? = null
        var bestAac: FormatCandidate? = null
        var bestOther: FormatCandidate? = null

        for (i in 0 until formats.length()) {
            val format = formats.optJSONObject(i) ?: continue

            // Reject ciphered formats completely
            if (format.has("signatureCipher") || format.has("cipher")) continue

            val url = format.optString("url")
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
            val response = executeWithRetry(request, maxRetries = 2)
            val bodyString = response.body?.string() ?: ""
            if (response.isSuccessful && bodyString.isNotEmpty()) {
                val json = JSONObject(bodyString)
                val syncedLyrics = json.optString("syncedLyrics")
                if (syncedLyrics.isNotEmpty()) {
                    lrcLines.addAll(parseLrc(syncedLyrics))
                }
            }
            response.close()

            // 2. Search endpoint fallback if direct get returned empty
            if (lrcLines.isEmpty()) {
                val query = java.net.URLEncoder.encode("$cleanTitleRaw $cleanArtistRaw", "UTF-8")
                val searchUrl = "https://lrclib.net/api/search?q=$query"
                val searchRequest = Request.Builder().url(searchUrl).header("User-Agent", FloWaveConstants.USER_AGENT_FLOWAVE_APP).build()
                val searchResponse = executeWithRetry(searchRequest, maxRetries = 2)
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
                searchResponse.close()
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
}
